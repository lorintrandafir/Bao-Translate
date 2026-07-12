package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.StreamingCaptioner
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.StreamingSttPipeline
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VoskStreamingPipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the streaming captioner state and lifecycle for [RecordingController]. Extracted so the
 * controller can stay focused on capture/playback sequencing; this class handles the per-language
 * model load/rebuild/release loop and surfaces live partial captions into the shared UiState.
 *
 * State (captioner, captionerLang, captionerChecked) is held only here; the controller reads live
 * captions via [feedCaptionPartial] and asks [reset] / [release] on session boundary or
 * ViewModel clear.
 */
internal class StreamingCaptionController(
  private val getApp: () -> Application,
  private val uiState: MutableStateFlow<BaoTranslateUiState>,
  private val isStale: (Long?) -> Boolean,
) {
  @Volatile private var captioner: StreamingCaptioner? = null
  @Volatile private var captionerLang: String? = null
  @Volatile private var captionerChecked = false

  // The ISO code of the language to caption this session — the speaker's configured source language.
  // null when AUTO or a language with no streaming model (-> chunked-Whisper caption instead).
  fun captionLanguageCode(): String? {
    val sourceKey = uiState.value.sourceLanguage
    if (sourceKey == SupportedLanguages.AUTO.key) return null
    val code = SupportedLanguages.CODE_MAP[sourceKey] ?: return null
    return if (captionEngineFor(code) != null) code else null
  }

  // True when a provisioned streaming model exists for [langCode] AND a prior load attempt for this
  // language has not failed. A present-but-unloadable model resolves to false so the read loop falls
  // back to chunked-Whisper instead of showing nothing.
  fun isCaptionViable(langCode: String): Boolean =
    isCaptionModelReady(getApp(), langCode) &&
      !(captionerLang == langCode && captionerChecked && captioner == null)

  /**
   * True when the streaming captioner for [langCode] is loaded AND its native pipeline is ready
   * (not just provisioned). Mirrors the gating the live read loop needs before feeding incremental
   * audio: a viable-but-still-warming model must stay silent until the ~1s load resolves, so
   * [feedCaptionPartial] is skipped and `streamingTurnPrimed` stays false until ready.
   */
  fun isCaptionerLoaded(langCode: String): Boolean =
    captioner?.takeIf { captionerLang == langCode && it.isReady } != null

  // Loads the streaming captioner for [langCode] (blocking native load, ~1s). MUST be called OFF the
  // audio read thread — pre-warmed at recording start so the first turn never stalls capture. Builds
  // the sherpa transducer for English and Vosk for the other languages; caches one captioner at a
  // time and rebuilds when the caption language changes.
  @Synchronized
  fun ensureCaptioner(langCode: String): StreamingCaptioner? {
    if (captionerLang == langCode) {
      captioner?.let { return it }
      if (captionerChecked) return null
    } else {
      captioner?.release()
      captioner = null
      captionerLang = langCode
      captionerChecked = false
    }
    val dir = getCaptionModelDir(getApp(), langCode)
    val pipeline: StreamingCaptioner? =
      when (captionEngineFor(langCode)) {
        CaptionEngine.SHERPA ->
          dir?.let { StreamingSttPipeline(it.absolutePath) }?.takeIf { it.initialize() }
        CaptionEngine.VOSK ->
          dir?.let { VoskStreamingPipeline(it.absolutePath) }?.takeIf { it.initialize() }
        null -> null
      }
    // Mark checked only AFTER the load attempt resolves (warming vs failed distinction).
    captionerChecked = true
    if (pipeline == null) {
      BaoLog.w(REC_TAG, "Caption model for '$langCode' present but failed to load; using chunked")
    }
    captioner = pipeline
    return pipeline
  }

  // Feeds an INCREMENTAL audio chunk to the (pre-warmed) captioner for [langCode] and surfaces its
  // growing hypothesis as the live caption. Non-blocking: skips the frame while warming rather than
  // loading on the audio thread. Returns false if no loaded captioner for this language.
  fun feedCaptionPartial(recordingSessionId: Long, langCode: String, samples: ShortArray): Boolean {
    val cap = captioner?.takeIf { captionerLang == langCode && it.isReady } ?: return false
    if (isStale(recordingSessionId)) return true
    val text = cap.acceptAndDecode(samples).trim()
    if (text.isNotBlank() && !isStale(recordingSessionId)) {
      uiState.update { if (it.isRecordingActive) it.copy(liveSourcePreview = text) else it }
    }
    return true
  }

  // Resets the active captioner's incremental state at session boundary (stop/cancel) without
  // releasing the native model — the next session reuses the loaded engine.
  fun reset() {
    captioner?.reset()
  }

  /** Releases the native streaming captioner; call when the owning ViewModel is cleared. */
  fun releaseStreamingStt() {
    captioner?.release()
    captioner = null
    captionerLang = null
    captionerChecked = false
  }
}
