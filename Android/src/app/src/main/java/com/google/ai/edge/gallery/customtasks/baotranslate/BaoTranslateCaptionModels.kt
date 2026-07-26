package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import java.io.File

/**
 * Caption model registry + path/state helpers.
 *
 * The translation loop's live caption has two engines:
 *   - [CaptionEngine.SHERPA] — streaming zipformer transducer (re-uses the shared sherpa dir)
 *   - [CaptionEngine.VOSK] — Vosk acoustic model (downloaded lazily per language)
 *
 * Caption specs are intentionally separate from [ARCHIVES] / [FILES] because they are NOT
 * auto-provisioned; the user opts in by selecting a target language. Adding a new caption
 * language means adding an entry to [CAPTION_MODELS] — nothing else.
 */

internal const val CAPTION_TAG = "BaoTranslateCaptions"

internal enum class CaptionEngine {
  SHERPA,
  VOSK,
}

internal data class CaptionModelSpec(
  val langCode: String,
  val modelId: String,
  val engine: CaptionEngine,
  val downloadUrl: String,
  val archiveFileName: String,
  val extractDirName: String,
  val sizeBytes: Long,
)

internal val CAPTION_MODELS: Map<String, CaptionModelSpec> = mapOf(
  // English re-uses the sherpa streaming ASR archive that's already auto-provisioned. Listing it
  // here as a caption spec just routes the lookup through the same download path; the file is NOT
  // re-downloaded, since downloadAndExtractArchive is no-op when the extraction check passes.
  "en" to CaptionModelSpec(
    langCode = "en",
    modelId = "streaming_asr",
    engine = CaptionEngine.SHERPA,
    downloadUrl = STREAMING_ASR_URL,
    archiveFileName = "$STREAMING_ASR_DIR.tar.bz2",
    extractDirName = STREAMING_ASR_DIR,
    sizeBytes = 44_000_000L,
  ),
  "es" to CaptionModelSpec(
    langCode = "es",
    modelId = "vosk_es_caption",
    engine = CaptionEngine.VOSK,
    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
    archiveFileName = "vosk-model-small-es-0.42.zip",
    extractDirName = "vosk-model-small-es-0.42",
    sizeBytes = 39_000_000L,
  ),
  "fr" to CaptionModelSpec(
    langCode = "fr",
    modelId = "vosk_fr_caption",
    engine = CaptionEngine.VOSK,
    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
    archiveFileName = "vosk-model-small-fr-0.22.zip",
    extractDirName = "vosk-model-small-fr-0.22",
    sizeBytes = 41_000_000L,
  ),
  "de" to CaptionModelSpec(
    langCode = "de",
    modelId = "vosk_de_caption",
    engine = CaptionEngine.VOSK,
    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
    archiveFileName = "vosk-model-small-de-0.15.zip",
    extractDirName = "vosk-model-small-de-0.15",
    sizeBytes = 48_000_000L,
  ),
  "it" to CaptionModelSpec(
    langCode = "it",
    modelId = "vosk_it_caption",
    engine = CaptionEngine.VOSK,
    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
    archiveFileName = "vosk-model-small-it-0.22.zip",
    extractDirName = "vosk-model-small-it-0.22",
    sizeBytes = 47_000_000L,
  ),
  "pt" to CaptionModelSpec(
    langCode = "pt",
    modelId = "vosk_pt_caption",
    engine = CaptionEngine.VOSK,
    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip",
    archiveFileName = "vosk-model-small-pt-0.3.zip",
    extractDirName = "vosk-model-small-pt-0.3",
    sizeBytes = 31_000_000L,
  ),
  "ru" to CaptionModelSpec(
    langCode = "ru",
    modelId = "vosk_ru_caption",
    engine = CaptionEngine.VOSK,
    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
    archiveFileName = "vosk-model-small-ru-0.22.zip",
    extractDirName = "vosk-model-small-ru-0.22",
    sizeBytes = 45_000_000L,
  ),
  "zh" to CaptionModelSpec(
    langCode = "zh",
    modelId = "vosk_zh_caption",
    engine = CaptionEngine.VOSK,
    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
    archiveFileName = "vosk-model-small-cn-0.22.zip",
    extractDirName = "vosk-model-small-cn-0.22",
    sizeBytes = 42_000_000L,
  ),
  "ja" to CaptionModelSpec(
    langCode = "ja",
    modelId = "vosk_ja_caption",
    engine = CaptionEngine.VOSK,
    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
    archiveFileName = "vosk-model-small-ja-0.22.zip",
    extractDirName = "vosk-model-small-ja-0.22",
    sizeBytes = 48_000_000L,
  ),
  "ko" to CaptionModelSpec(
    langCode = "ko",
    modelId = "vosk_ko_caption",
    engine = CaptionEngine.VOSK,
    downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip",
    archiveFileName = "vosk-model-small-ko-0.22.zip",
    extractDirName = "vosk-model-small-ko-0.22",
    sizeBytes = 42_000_000L,
  ),
)

internal fun voskCaptionSpec(langCode: String): CaptionModelSpec {
  val spec = CAPTION_MODELS[langCode]
  require(spec != null && spec.engine == CaptionEngine.VOSK) {
    "No Vosk caption spec for language '$langCode'"
  }
  return spec
}

internal fun captionEngineFor(langCode: String): CaptionEngine? =
  CAPTION_MODELS[langCode]?.engine

internal fun getCaptionModelDir(context: Context, langCode: String): File? =
  CAPTION_MODELS[langCode]?.extractDirName?.let { File(getSherpaOnnxDir(context), it) }

internal fun isCaptionModelReady(context: Context, langCode: String): Boolean {
  val spec = CAPTION_MODELS[langCode] ?: return false
  val dir = File(getSherpaOnnxDir(context), spec.extractDirName)
  if (!dir.exists()) return false
  // Vosk models extract an `am/` and `conf/` directory plus a top-level mfcc.conf; sherpa extracts
  // the files listed in the archive spec. A directory-with-files check is sufficient to catch
  // both partial extractions (kills mid-untwice will leave at least one missing file) and a killed
  // directory delete (the dir is gone entirely).
  return dir.listFiles().orEmpty().isNotEmpty()
}

internal fun deleteCaptionModel(context: Context, modelId: String) {
  val spec = CAPTION_MODELS.values.firstOrNull { it.modelId == modelId } ?: return
  if (spec.engine == CaptionEngine.SHERPA) {
    // sherpa is the auto-provisioned streaming model — removing it here would break the english
    // captioning path even when the user only asked to clear captions. Log and skip.
    BaoLog.w(CAPTION_TAG, "Refusing to delete sherpa-backed caption model $modelId")
    return
  }
  val dir = File(getSherpaOnnxDir(context), spec.extractDirName)
  if (dir.exists()) {
    dir.deleteRecursively()
    BaoLog.i(CAPTION_TAG, "Deleted Vosk caption model $modelId at ${dir.absolutePath}")
  }
}