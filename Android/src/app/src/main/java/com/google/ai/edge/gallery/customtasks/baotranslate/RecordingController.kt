package com.google.ai.edge.gallery.customtasks.baotranslate

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioRecord
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioCache
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.DeviceProbe
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.WAVEFORM_HISTORY
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.waveformAmplitude
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.EmptyTranscriptionException
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.StreamingCaptioner
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.StreamingSttPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VadInitResult
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VadProcessor
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VoskStreamingPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.KokoroTtsPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.TtsEngine
import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isValidTranscription
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleConversationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import java.util.UUID

@SuppressLint("RestrictedApi")
internal class RecordingController(
  internal val pipelines: PipelineLifecycleManager,
  internal val audioRouter: AudioRouter,
  internal val bleManager: BleConversationManager,
  internal val uiState: MutableStateFlow<BaoTranslateUiState>,
  private val viewModelScope: CoroutineScope,
  internal val getApp: () -> Application,
  internal val voiceProfileManager: com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfileManager? = null,
  internal val onAutoAcceptDetectedLanguage: ((String) -> Unit)? = null,
) {
  private val recordingMutex = Mutex()
  internal val segmentProcessingMutex = Mutex()
  // Guards against overlapping streaming partial-caption decodes (at most one in flight).
  private val partialCaptionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

  // Test-only seam: when set, the real recording read loop sources its frames from this injected PCM
  // instead of the mic, so instrumentation exercises the PRODUCTION VAD -> turn-endpoint -> streaming
  // partial -> translation loop (not a parallel reimplementation). Null in production. Frames are
  // returned as fast as the loop reads (no real-time pacing); exhaustion returns 0 and the test calls
  // stopRecording() to trigger the final-segment flush.
  @Volatile internal var injectedFrameSource: InjectedFrameSource? = null

  /**
   * Real-time-paced frame reader moved to [RecordingControllerSupport.InjectedFrameSource]. The
   * field below stays in this file because it is read by the loop on every frame; the class is
   * defined next to the public [RecordingController] for readability.
   */
  @Volatile private var holdsRecordingLock = false
  @Volatile internal var currentRecordingSessionId = 0L
  var recordingJob: Job? = null
  var audioRecord: AudioRecord? = null

  // Dedicated scope for the recording session and its live-window translation segments.
  // Cancelling this scope stops the mic loop AND any queued-but-unstarted (or mid-flight)
  // translation/TTS jobs, preventing stale transcripts from firing after the user stops.
  private var recordingScope: CoroutineScope? = null

  // Hands-free conversation guard: true while the app is playing a translation aloud. The capture
  // loop discards mic input during that window, so continuous (no-button) listening never re-hears
  // and re-translates its own spoken output — the feedback loop that otherwise breaks bidirectional
  // face-to-face mode. This is the portable guarantee; hardware AEC (below) handles residual leakage.
  @Volatile internal var capturePaused = false

  @Volatile internal var discardRecordingOnStop = false

internal val captionController =
  StreamingCaptionController(getApp, uiState) { isStaleRecordingSegment(it) }

  private val conversationManager = ConversationManager()

  // Single seam for phase changes: every transition is mirrored into UiState so the UI surfaces
  // the live Listening / Translating / Speaking turn state without a second source of truth.
  internal fun conversationEvent(event: ConversationManager.() -> Unit) {
    val before = conversationManager.phase
    conversationManager.event()
    val phase = conversationManager.phase
    if (phase != before) {
      BaoLog.i(REC_TAG, "Conversation phase $before -> $phase")
    }
    uiState.update { if (it.conversationPhase == phase) it else it.copy(conversationPhase = phase) }
  }

  @SuppressLint("RestrictedApi")
  internal companion object {
    // Test-only injection point. When non-null, the next startRecording() sources audio from this
    // 16 kHz mono PCM instead of the microphone, then clears it. Set only by instrumentation tests
    // (the device echo canceller makes acoustic speaker->own-mic loopback unusable for STT).
    @JvmStatic
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    @Volatile
    internal var testPcmSource: ShortArray? = null

    /** Last target speaker embedding passed to OpenVoice convert, or null if no clone was attempted. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    @Volatile
    internal var testLastCloneTargetSe: FloatArray? = null

    /** True when [OpenVoiceVoiceConverter.convert] was attempted with a non-null target embedding. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    @Volatile
    internal var testLastWasCloned: Boolean = false

    /** Per-turn output-device override last passed to playback, or null if the global route was used. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.NONE)
    @Volatile
    internal var testLastOutputOverride: AudioDevice? = null
  }

  fun startRecording() {
    if (uiState.value.isRecordingActive) return
    val app = getApp()
    if (!pipelines.requiredPipelinesReady()) {
      uiState.update { it.copy(
        pipelineStatus = PipelineStatus.ModelsNotReady,
        errorMessage = app.getString(R.string.bao_translate_error_models_not_ready),
      ) }
      return
    }

    if (!recordingMutex.tryLock()) return
    holdsRecordingLock = true
    val recordingSessionId = currentRecordingSessionId + 1
    currentRecordingSessionId = recordingSessionId
    discardRecordingOnStop = false
    conversationEvent { onRecordingStart() }

    uiState.update {
      it.copy(
        pipelineStatus = PipelineStatus.StartingRecording,
        errorMessage = null,
        liveTranslationPreview = null,
        liveSourcePreview = null,
        amplitudes = emptyList(),
        elapsedSeconds = 0f,
      )
    }

    // Test-only seam: drive the REAL read loop from injected PCM instead of the mic (the loop reads
    // frames from injectedFrameSource when set). A device's echo canceller cancels its own speaker
    // output captured by its own mic, so an automated speaker->mic self-loopback cannot feed STT;
    // this routes clean PCM through the exact production VAD -> turn-endpoint -> streaming-partial ->
    // translate -> UI loop. Never set outside instrumentation tests.
    val injectedPcm = testPcmSource
    if (injectedPcm != null) {
      testPcmSource = null
      injectedFrameSource = InjectedFrameSource(injectedPcm, PipelineConfig.STT_SAMPLE_RATE)
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    recordingScope = scope
    // Multilingual caption provisioning, OFF the audio thread: if the streaming model for the
    // speaker's language is present, pre-warm it so its ~1s load never stalls first-turn capture; if
    // it's a supported language not yet downloaded, fetch it in the background (this session captions
    // via chunked-Whisper, the next streams). Languages with no streaming model just use chunked.
    captionController.captionLanguageCode()?.let { lang ->
      if (isCaptionModelReady(app, lang)) {
        scope.launch(Dispatchers.IO) { captionController.ensureCaptioner(lang) }
      } else {
        // Fetch in the background, then pre-warm so it streams later THIS session once it lands.
        // The Result is logged (not swallowed) so a failed caption download is observable; the model
        // manager already publishes Downloading/Error status for the vosk_<lang> id.
        scope.launch(Dispatchers.IO) {
          val result = BaoTranslateModelManager.downloadCaptionModel(app, lang)
          if (result.isSuccess) {
            captionController.ensureCaptioner(lang)
          } else {
            BaoLog.w(REC_TAG, "Background caption-model download for '$lang' failed: ${result.exceptionOrNull()?.message}")
          }
        }
      }
    }
    recordingJob = scope.launch { runReadLoop(recordingSessionId) }
  }

  fun stopRecording() {
    if (!uiState.value.isRecordingActive) return
    discardRecordingOnStop = false
    captionController.reset()
    // GRACEFUL stop: flip to Idle so the read loop's `while (isRecordingActive)` exits on its next
    // iteration, runs its trailing-tail flush (committing a short utterance's translation), and
    // dispatches onRecordingStop itself. We must NOT cancel the scope here — cancelling throws
    // CancellationException at the loop's next suspend point, unwinding past the tail flush and
    // silently dropping that final segment (a <8s utterance with no window/endpoint). The loop holds
    // the recording mutex until it finishes, so a new startRecording waits for the flush to complete.
    uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
  }

  /** Cancel recording and discard in-flight audio — no final-segment flush. */
  fun cancelRecording() {
    if (!uiState.value.isRecordingActive) return
    discardRecordingOnStop = true
    uiState.update {
      it.copy(
        pipelineStatus = PipelineStatus.Idle,
        liveTranslationPreview = null,
        elapsedSeconds = 0f,
        amplitudes = emptyList(),
      )
    }
    recordingScope?.cancel()
    recordingScope = null
    recordingJob = null
    captionController.reset()
    conversationEvent { onRecordingStop() }
  }

  /** Release native streaming captioner (sherpa/Vosk). Call on ViewModel clear. */
  fun releaseStreamingStt() = captionController.releaseStreamingStt()

  internal fun unlockRecordingIfHeld() {
    if (holdsRecordingLock) {
      holdsRecordingLock = false
      recordingMutex.unlock()
    }
  }

  internal fun queueRealtimeTranslationSegment(recordingSessionId: Long, audioSamples: ShortArray) {
    BaoLog.i(
      REC_TAG,
      "Queue live segment session=$recordingSessionId ${audioSamples.audioStats()}",
    )
    val scope = recordingScope ?: return
    scope.launch(Dispatchers.IO) {
      segmentProcessingMutex.withLock {
        processAudioSegment(
          audioSamples = audioSamples,
          recordingSessionId = recordingSessionId,
          preserveRecordingStatus = true,
          reportEmptySpeech = false,
        )
      }
    }
  }

  // Streaming partial caption: while a turn is still being spoken, decode the audio heard SO FAR and
  // surface it as the live recognized text — so the caption streams as you talk instead of only
  // appearing at end-of-turn. Best-effort and non-destructive: it never translates, commits a
  // transcript, or speaks. At most one runs at a time (compareAndSet), and it shares
  // segmentProcessingMutex with the final decode so the single Whisper context is never used
  // concurrently; the in-flight flag is cleared on completion (success, failure, or cancel).
  internal fun queuePartialCaption(recordingSessionId: Long, audioSamples: ShortArray) {
    if (!partialCaptionInFlight.compareAndSet(false, true)) return
    val scope = recordingScope
    if (scope == null) {
      partialCaptionInFlight.set(false)
      return
    }
    val job =
      scope.launch(Dispatchers.IO) {
        if (isStaleRecordingSegment(recordingSessionId)) return@launch
        val whisper = pipelines.pipelineMutex.withLock { pipelines.whisperPipeline } ?: return@launch
        val text =
          segmentProcessingMutex.withLock {
            if (isStaleRecordingSegment(recordingSessionId)) null
            else whisper.transcribeBlocking(audioSamples).getOrNull()?.text
          }
        if (text != null && isValidTranscription(text) && !isStaleRecordingSegment(recordingSessionId)) {
          BaoLog.i(REC_TAG, "Partial caption chars=${text.length} session=$recordingSessionId")
          uiState.update { if (it.isRecordingActive) it.copy(liveSourcePreview = text) else it }
        }
      }
    job.invokeOnCompletion { partialCaptionInFlight.set(false) }
  }

  // The ISO code of the language to caption this session — the speaker's configured source language.
  // null when AUTO or a language with no streaming model (-> chunked-Whisper caption instead).

  internal fun statusAfterSegment(preserveRecordingStatus: Boolean): PipelineStatus =
    if (preserveRecordingStatus && uiState.value.isRecording) {
      PipelineStatus.Recording
    } else {
      PipelineStatus.Idle
    }

  internal fun isStaleRecordingSegment(recordingSessionId: Long?): Boolean =
    recordingSessionId != null && recordingSessionId != currentRecordingSessionId

  internal suspend fun processAudioSegment(
    audioSamples: ShortArray,
    recordingSessionId: Long? = null,
    preserveRecordingStatus: Boolean = false,
    reportEmptySpeech: Boolean = true,
  ) {
    if (isStaleRecordingSegment(recordingSessionId)) return
    conversationEvent { onProcessingStart() }
    try {
      runSegmentPipeline(audioSamples, recordingSessionId, preserveRecordingStatus, reportEmptySpeech)
    } finally {
      // Segments that end without playback (VAD-empty, blank decode, errors) return the phase to
      // Listening while the mic loop is still live. After playback, the tail-mute chain already
      // returned to Listening; after session stop, Idle remains authoritative.
      conversationEvent { onSegmentComplete() }
    }
  }



  internal fun AudioRecord.waitForPreferredInputRoute(deviceId: Int): Boolean {
    val deadline = System.currentTimeMillis() + INPUT_ROUTE_TIMEOUT_MS
    do {
      if (routedDevice?.id == deviceId) return true
      Thread.sleep(50)
    } while (System.currentTimeMillis() < deadline)
    return routedDevice?.id == deviceId
  }

}
