package com.google.ai.edge.gallery.customtasks.baotranslate

import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioCache
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.DeviceProbe
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal suspend fun RecordingController.synthesizeSpeech(
    text: String,
    language: String,
    recordingSessionId: Long? = null,
    preserveRecordingStatus: Boolean = false,
    // A connected peer's shared voice timbre (256-d OpenVoice speaker embedding). When non-null, the
    // output is cloned into THAT speaker's voice so a multi-speaker conversation is heard in each
    // person's own voice; null => the locally-enrolled user's timbre (the normal local-speech path).
    speakerSe: FloatArray? = null,
    timbre: SpeechTimbre = SpeechTimbre.LocalEnrolled,
  ): Boolean {
    RecordingController.testLastCloneTargetSe = null
    RecordingController.testLastWasCloned = false

    if (isStaleRecordingSegment(recordingSessionId)) return false

    if (!preserveRecordingStatus) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Speaking) }
    }

    val state = uiState.value
    val ovConverter = pipelines.openVoiceConverter

    // Cache key: voice profile enrollment state determines whether cloning is applied, so include
    // a fingerprint of the enrolled embedding so cache hits are correct across profile changes.
    val voiceId = when {
      // Each connected peer has their OWN shared timbre, so the cache must key on a fingerprint of THAT
      // peer's embedding — a bare "peer" key collides across peers, so peer B saying the same phrase as
      // peer A would be served peer A's cached cloned audio (heard in the wrong voice).
      timbre == SpeechTimbre.PeerOnly -> "peer:${speakerSe?.contentHashCode() ?: 0}"
      state.voiceProfileEnrolled -> state.activeVoiceProfileId
      else -> "default"
    }

    // Prosody speed adjustment: when a voice profile is enrolled, use the user's natural speaking
    // rate to adjust Kokoro's speed parameter. This makes the cloned output sound more natural by
    // matching the user's cadence rather than Kokoro's default rate.
    val prosodySpeed = if (state.voiceProfileEnrolled && timbre != SpeechTimbre.PeerOnly) {
      val profileId = state.activeVoiceProfileId
      voiceProfileManager?.loadProsody(profileId)?.speedMultiplier ?: 1.0f
    } else 1.0f

    // Check cache BEFORE synthesis so replay of previously translated messages is instant.
    val cached = AudioCache.get(text = text, language = language, voiceId = voiceId, speed = prosodySpeed)
    val audio = if (cached != null) {
      cached
    } else {
      val kokoroVoice = KokoroTtsPipeline.getVoiceForLanguage(language)
      val base = pipelines.ttsRouter().synthesize(
        text = text,
        language = language,
        kokoroVoiceId = kokoroVoice,
        speed = prosodySpeed,
      )

      // Voice clone: the OpenVoice tone-color converter re-times the base audio into a target timbre.
      // It is language- AND source-agnostic, so this clones EVERY supported language — including the
      // platform-TTS fallback languages (de/ko/ru/ar), not just Kokoro's. [speakerSe] (a peer's shared
      // timbre) takes precedence so a multi-speaker conversation is heard in each speaker's own voice;
      // otherwise the locally-enrolled user's timbre is used when enrolled. Falls back to the un-cloned
      // base when no timbre applies or a conversion fails.
      val targetSe = when (timbre) {
        SpeechTimbre.PeerOnly -> speakerSe
        SpeechTimbre.LocalEnrolled ->
          speakerSe ?: pipelines.openVoiceTargetSe?.takeIf { state.voiceProfileEnrolled }
      }
      if (targetSe != null) {
        RecordingController.testLastCloneTargetSe = targetSe.copyOf()
      }
      val synthesized = if (ovConverter != null && targetSe != null && base != null) {
        RecordingController.testLastWasCloned = true
        ovConverter.convert(base, targetSe) ?: base
      } else {
        base
      }

      // Store in cache for instant replay later.
      if (synthesized != null) {
        AudioCache.put(text = text, language = language, voiceId = voiceId, speed = prosodySpeed, audio = synthesized)
      }
      synthesized
    }

    var played = false
    if (audio != null) {
      if (isStaleRecordingSegment(recordingSessionId)) return false
      withContext(Dispatchers.Default) {
        // Gate the capture loop for the duration of playback (+ a short acoustic-decay tail) so the
        // live mic never hears and re-translates this output. finally guarantees the gate reopens even
        // if playback is interrupted, otherwise the mic would stay deaf for the rest of the session.
        capturePaused = true
        conversationEvent { onPlaybackStart() }
        // Face-to-face per-speaker output: route this translation to the LISTENER's assigned device
        // (the listener is whoever speaks [language]); null falls back to the global output route.
        val outputOverride =
          DeviceProbe.resolveOutputOverride(
            faceToFaceMode = uiState.value.faceToFaceMode,
            perSpeakerOutputs = uiState.value.faceToFaceOutputs,
            language = language,
          )
        RecordingController.testLastOutputOverride = outputOverride
        try {
          audioRouter.play(audio.samples, sampleRate = audio.sampleRate, override = outputOverride)
          conversationEvent { onPlaybackEnd() }
          delay(PLAYBACK_TAIL_MUTE_MS)
        } finally {
          // Cancellation can skip onPlaybackEnd above; emit it here first so the
          // Speaking -> Cooldown -> Listening chain stays valid on every exit path.
          conversationEvent { onPlaybackEnd() }
          conversationEvent { onTailMuteComplete() }
          capturePaused = false
        }
      }
      played = true
    } else {
      if (!isStaleRecordingSegment(recordingSessionId)) {
        uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_tts_engine_not_ready)) }
      }
    }

    if (!preserveRecordingStatus && !isStaleRecordingSegment(recordingSessionId)) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
    }
    return played
  }
