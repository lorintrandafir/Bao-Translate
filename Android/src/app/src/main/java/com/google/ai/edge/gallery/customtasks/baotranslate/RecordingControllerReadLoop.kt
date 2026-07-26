package com.google.ai.edge.gallery.customtasks.baotranslate

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.BluetoothTransport
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WAVEFORM_HISTORY
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.waveformAmplitude
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Mic read loop for [RecordingController.startRecording]. Owns the three nested try/finally layers
 * (capture-session -> AudioRecord -> acoustic effects) so the surrounding launch block stays a
 * single line and the read-loop body's release semantics stay self-contained.
 */
internal suspend fun RecordingController.runReadLoop(recordingSessionId: Long) {
  val app = getApp()
  try {
    if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      unlockRecordingIfHeld()
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = app.getString(R.string.bao_translate_permission_mic_denied),
      ) }
      return
    }

    val sampleRate = PipelineConfig.STT_SAMPLE_RATE
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    if (minBufferSize <= 0) {
      BaoLog.e(REC_TAG, "AudioRecord returned invalid min buffer size: $minBufferSize")
      unlockRecordingIfHeld()
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
      ) }
      return
    }

    val currentOutput = uiState.value.currentAudioDevice
    val preferredInput = uiState.value.preferredInputDevice
    val audioSource = when {
      currentOutput is AudioDevice.BluetoothHeadset &&
        (currentOutput.transport == BluetoothTransport.BLE_AUDIO ||
          currentOutput.transport == BluetoothTransport.SCO) ->
        MediaRecorder.AudioSource.VOICE_COMMUNICATION
      preferredInput != null -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
      else -> MediaRecorder.AudioSource.MIC
    }

    val preferredInputInfo = preferredInput?.let { audioRouter.getInputDeviceInfo(it) }
    if (preferredInput != null && preferredInputInfo == null) {
      BaoLog.e(REC_TAG, "Selected Bluetooth input is no longer available: $preferredInput")
      unlockRecordingIfHeld()
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
      ) }
      return
    }

    // Capture into a generous ring buffer — 0.5 s of PCM16, never below 4x the OS minimum — so a
    // transient stall in the VAD/STT/translation consumer never overflows AudioRecord and drops
    // samples (the cause of scratchy/glitchy capture). Mirrors the 4x sizing on the playback track.
    val captureBufferBytes = maxOf(minBufferSize * 4, sampleRate * Short.SIZE_BYTES / 2)
    val recorder = AudioRecord(
      audioSource,
      sampleRate,
      channelConfig,
      audioFormat,
      captureBufferBytes,
    )

    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      BaoLog.e(REC_TAG, "AudioRecord failed to initialize")
      recorder.release()
      unlockRecordingIfHeld()
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
      ) }
      return
    }

    if (preferredInputInfo != null && !recorder.setPreferredDevice(preferredInputInfo)) {
      BaoLog.e(REC_TAG, "AudioRecord rejected preferred Bluetooth input: ${preferredInputInfo.productName}")
      recorder.release()
      unlockRecordingIfHeld()
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
      ) }
      return
    }

    audioRecord = recorder
    val buffer = ShortArray((minBufferSize / 2).coerceAtLeast(sampleRate / 10))
    val startTime = System.currentTimeMillis()
    val liveWindowSamples = sampleRate * LIVE_TRANSLATION_WINDOW_SECONDS
    val liveStrideSamples = sampleRate * LIVE_TRANSLATION_STRIDE_SECONDS
    val minSegmentSamples = sampleRate * MIN_TRANSLATION_SEGMENT_SECONDS
    val pendingSamples = ShortArray(liveWindowSamples)
    val partialCaptionStepSamples = sampleRate * 3 / 2 // 1.5s streaming-partial cadence
    var sampleCount = 0
    var samplesSinceLastLiveWindow = 0
    var samplesSinceLastPartial = 0
    var streamingTurnPrimed = false
    // The language to caption this session (speaker's source language), fixed for the recording.
    val captionLang = captionController.captionLanguageCode()
    var queuedLiveSegment = false
    var recordingFailed = false
    var publishedRecordingState = false
    var lastPositiveReadAt = System.currentTimeMillis()
    // Silence auto-stop: tracks when speech activity was last detected (live segment queued or
    // non-silent mic frame). If SILENCE_AUTO_STOP_MS elapses with no activity, recording stops
    // automatically — enables buttonless continuous conversation.
    var lastSpeechActivityAt = System.currentTimeMillis()
    // Face-to-face turn endpointing: true once the current turn has heard speech, so a trailing
    // F2F_TURN_END_SILENCE_MS of quiet flushes the whole utterance as one segment.
    var turnSpeechHeard = false

    recorder.startRecording()

    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
      BaoLog.e(REC_TAG, "AudioRecord did not enter recording state")
      recorder.release()
      audioRecord = null
      unlockRecordingIfHeld()
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
      ) }
      return
    }
    BaoLog.i(
      REC_TAG,
      "AudioRecord started session=$recordingSessionId source=$audioSource captureBufferBytes=$captureBufferBytes minBufferBytes=$minBufferSize preferredInputId=${preferredInputInfo?.id}",
    )

    if (preferredInputInfo != null && !recorder.waitForPreferredInputRoute(preferredInputInfo.id)) {
      BaoLog.e(
        REC_TAG,
        "AudioRecord routed to ${recorder.routedDevice?.productName} instead of selected Bluetooth input ${preferredInputInfo.productName}",
      )
      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        recorder.stop()
      }
      recorder.release()
      audioRecord = null
      unlockRecordingIfHeld()
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
      ) }
      return
    }

    // `isActive` lets the read loop exit on coroutine cancellation (e.g. ViewModel cleared
    // mid-recording) so the AudioRecord below is released and the mic doesn't stay live.
    try {
    // Hardware echo cancellation + noise suppression on this capture session. Best-effort: the
    // factory returns null on devices without the effect, in which case the capturePaused gate
    // (which discards mic input during playback) is the sole — and sufficient — feedback guard.
    val echoCanceler =
      if (AcousticEchoCanceler.isAvailable())
        AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true }
      else null
    val noiseSuppressor =
      if (NoiseSuppressor.isAvailable())
        NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true }
      else null
    try {
    readLoop@ while (coroutineContext.isActive && uiState.value.isRecordingActive) {
      // Silence auto-stop: if no speech activity for SILENCE_AUTO_STOP_MS after at least one
      // translation was committed, stop recording. This enables buttonless conversation — the
      // user starts once, speaks, and the system auto-stops after a natural pause.
      // Face-to-face mode is fully hands-free: the session stays live across long pauses for as
      // long as the screen is open (leaving the screen stops it), so neither speaker ever has to
      // re-arm the mic mid-conversation.
      if (queuedLiveSegment && !capturePaused && !uiState.value.faceToFaceMode) {
        val silenceMs = System.currentTimeMillis() - lastSpeechActivityAt
        if (silenceMs >= SILENCE_AUTO_STOP_MS) {
          BaoLog.i(
            REC_TAG,
            "Silence auto-stop after ${silenceMs}ms session=$recordingSessionId f2f=${uiState.value.faceToFaceMode}",
          )
          break
        }
      }

      val readCount =
        injectedFrameSource?.read(buffer)
          ?: recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)

      when {
        readCount > 0 -> {
          lastPositiveReadAt = System.currentTimeMillis()
          if (capturePaused) {
            // App is speaking its own translation: drop this audio and reset the live window so the
            // echo (and any half-captured pre-playback speech) never mixes into the next speaker's
            // turn. This is what makes continuous, no-button bidirectional conversation usable.
            sampleCount = 0
            samplesSinceLastLiveWindow = 0
            turnSpeechHeard = false
            streamingTurnPrimed = false
            captionController.reset()
            continue@readLoop
          }

          // Track speech activity: if the mic frame has meaningful amplitude, update the
          // last-speech timestamp. This prevents silence auto-stop during quiet but active speech.
          val frameAmplitude = waveformAmplitude(buffer, readCount)
          if (frameAmplitude > SPEECH_AMPLITUDE_THRESHOLD) {
            lastSpeechActivityAt = System.currentTimeMillis()
            turnSpeechHeard = true
          }
          val faceToFace = uiState.value.faceToFaceMode

          var bufferOffset = 0
          while (bufferOffset < readCount) {
            val copyCount = minOf(readCount - bufferOffset, liveWindowSamples - sampleCount)
            System.arraycopy(buffer, bufferOffset, pendingSamples, sampleCount, copyCount)
            sampleCount += copyCount
            samplesSinceLastLiveWindow += copyCount
            bufferOffset += copyCount

            if (sampleCount == liveWindowSamples) {
              if (faceToFace) {
                // Buffer full mid-monologue: flush the whole window as one turn, no overlap.
                // Overlapping windows re-transcribe and re-speak the shared 4s — the duplicate
                // spam this turn-based path exists to eliminate.
                if (turnSpeechHeard) {
                  queueRealtimeTranslationSegment(recordingSessionId, pendingSamples.copyOf(sampleCount))
                  queuedLiveSegment = true
                  lastSpeechActivityAt = System.currentTimeMillis()
                }
                sampleCount = 0
                samplesSinceLastLiveWindow = 0
                turnSpeechHeard = false
              } else {
                queueRealtimeTranslationSegment(recordingSessionId, pendingSamples.copyOf(sampleCount))
                queuedLiveSegment = true
                lastSpeechActivityAt = System.currentTimeMillis()
                // Restart the live caption for the next window so it tracks current speech, not the
                // whole session (the recognizer is stateful and would grow unbounded otherwise).
                streamingTurnPrimed = false
                captionController.reset()
                val overlapSamples = liveWindowSamples - liveStrideSamples
                System.arraycopy(pendingSamples, liveStrideSamples, pendingSamples, 0, overlapSamples)
                sampleCount = overlapSamples
                samplesSinceLastLiveWindow = 0
              }
            }
          }

          // Streaming partial caption from the FIRST detected speech (not gated on the 1s
          // translation minimum), so the recognized caption appears word-by-word as you talk — in
          // BOTH face-to-face and single-speaker continuous mode. Industry-standard live captioning.
          if (turnSpeechHeard) {
            if (captionLang != null && captionController.isCaptionViable(captionLang)) {
              // TRUE streaming ASR for this language (sherpa for English, Vosk otherwise),
              // pre-warmed off the audio thread at recording start: prime with everything heard so
              // far this turn, then feed each newly-read frame so the hypothesis grows token-by-
              // token. While the recognizer is still warming, skip the frame rather than stalling
              // capture.
              if (captionController.isCaptionerLoaded(captionLang)) {
                if (!streamingTurnPrimed) {
                  BaoLog.i(REC_TAG, "LIVE_PARTIAL_BRANCH=streaming:$captionLang session=$recordingSessionId")
                  captionController.feedCaptionPartial(recordingSessionId, captionLang, pendingSamples.copyOf(sampleCount))
                  streamingTurnPrimed = true
                } else {
                  captionController.feedCaptionPartial(recordingSessionId, captionLang, buffer.copyOf(readCount))
                }
              }
            } else {
              // No streaming model for this language (AUTO / unsupported / load failed): periodic
              // chunked-Whisper re-decode, which is multilingual via the offline Whisper model.
              if (!streamingTurnPrimed) {
                BaoLog.i(REC_TAG, "LIVE_PARTIAL_BRANCH=chunked session=$recordingSessionId")
                streamingTurnPrimed = true
              }
              samplesSinceLastPartial += readCount
              if (samplesSinceLastPartial >= partialCaptionStepSamples) {
                samplesSinceLastPartial = 0
                queuePartialCaption(recordingSessionId, pendingSamples.copyOf(sampleCount))
              }
            }
          }

          // Face-to-face turn endpoint: speech followed by a natural pause flushes the whole
          // utterance as ONE segment, which is then translated and spoken before the next turn.
          if (
            faceToFace &&
            turnSpeechHeard &&
            sampleCount >= minSegmentSamples &&
            System.currentTimeMillis() - lastSpeechActivityAt >= F2F_TURN_END_SILENCE_MS
          ) {
            BaoLog.i(REC_TAG, "F2F turn endpoint samples=$sampleCount session=$recordingSessionId")
            queueRealtimeTranslationSegment(recordingSessionId, pendingSamples.copyOf(sampleCount))
            queuedLiveSegment = true
            sampleCount = 0
            samplesSinceLastLiveWindow = 0
            samplesSinceLastPartial = 0
            streamingTurnPrimed = false
            captionController.reset()
            turnSpeechHeard = false
          }

          val elapsed = (System.currentTimeMillis() - startTime) / 1000f
          val newAmplitudes = (uiState.value.amplitudes + frameAmplitude).takeLast(WAVEFORM_HISTORY)
          if (!publishedRecordingState) {
            val stats = buffer.copyOf(readCount).audioStats()
            BaoLog.i(
              REC_TAG,
              "First mic frame session=$recordingSessionId read=$readCount rms=${stats.rms} peak=${stats.peak}",
            )
            publishedRecordingState = true
          }

          uiState.update { state ->
            if (!state.isRecordingActive) {
              state
            } else {
              state.copy(
                pipelineStatus = PipelineStatus.Recording,
                elapsedSeconds = elapsed,
                amplitudes = newAmplitudes,
              )
            }
          }
        }
        readCount == 0 -> {
          val now = System.currentTimeMillis()
          if (capturePaused) {
            // The device audio HAL can starve the mic while the app's own TTS plays through the
            // speaker. Capture is deliberately gated during playback anyway, so an empty read
            // here is expected — keep the stall reference fresh or the watchdog kills the
            // hands-free session at the first spoken translation longer than the timeout.
            lastPositiveReadAt = now
            delay(AUDIO_READ_EMPTY_SLEEP_MS)
            continue@readLoop
          }
          if (now - lastPositiveReadAt >= AUDIO_READ_STALL_TIMEOUT_MS && injectedFrameSource == null) {
            BaoLog.e(
              REC_TAG,
              "AudioRecord produced no mic frames for ${now - lastPositiveReadAt}ms session=$recordingSessionId",
            )
            uiState.update { it.copy(
              pipelineStatus = PipelineStatus.Idle,
              errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
            ) }
            recordingFailed = true
            break
          }
          delay(AUDIO_READ_EMPTY_SLEEP_MS)
        }
        readCount < 0 -> {
          BaoLog.e(REC_TAG, "AudioRecord read returned error: $readCount")
          uiState.update { it.copy(
            pipelineStatus = PipelineStatus.Idle,
            errorMessage = app.getString(R.string.bao_translate_error_microphone_init),
          ) }
          recordingFailed = true
          break
        }
      }
    }

    if (recordingFailed) {
      // The watchdog/read-error exits skip the normal end-of-loop stop event; without this the
      // turn control keeps showing a live phase for a session whose mic is already dead.
      conversationEvent { onRecordingStop() }
      return
    }

    // Process the trailing tail regardless of whether live windows already fired. Gating the
    // final flush on a full stride (4s) silently dropped the last <4s of speech: that tail past
    // the last window boundary was never part of any emitted window, so use the 1s minimum.
    val finalSampleCount = if (queuedLiveSegment) samplesSinceLastLiveWindow else sampleCount
    if (!discardRecordingOnStop && finalSampleCount >= minSegmentSamples) {
      val finalStart = sampleCount - finalSampleCount
      // NonCancellable: stopRecording() cancels this coroutine's scope, but the tail flush is the
      // ONLY commit path for a short utterance ended by tapping stop (no live window fired, and the
      // turn endpoint is face-to-face only). Without this, that translation is silently lost.
      withContext(NonCancellable) {
        segmentProcessingMutex.withLock {
          processAudioSegment(
            pendingSamples.copyOfRange(finalStart, sampleCount),
            recordingSessionId = recordingSessionId,
            preserveRecordingStatus = false,
            reportEmptySpeech = !queuedLiveSegment,
          )
        }
      }
    } else if (!discardRecordingOnStop && sampleCount > 0 && !queuedLiveSegment) {
      uiState.update { it.copy(errorMessage = app.getString(R.string.bao_translate_error_recording_short)) }
    }
    conversationEvent { onRecordingStop() }
    // The loop can exit on its own (silence auto-stop) while UiState still says Recording —
    // without this the mic icon would show a live session whose AudioRecord is already released.
    uiState.update {
      if (it.isRecordingActive) it.copy(pipelineStatus = PipelineStatus.Idle) else it
    }
    } finally {
      echoCanceler?.release()
      noiseSuppressor?.release()
    }
    } finally {
      if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        recorder.stop()
      }
      recorder.release()
      audioRecord = null
    }
  } finally {
    injectedFrameSource = null
    unlockRecordingIfHeld()
  }
}