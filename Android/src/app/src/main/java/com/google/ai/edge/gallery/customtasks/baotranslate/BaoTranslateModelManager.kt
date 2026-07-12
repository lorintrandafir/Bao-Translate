package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.Context
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ModelStatus {
  data object NotDownloaded : ModelStatus
  data class Downloading(
    val progress: Float,
    val bytesReceived: Long,
    val totalBytes: Long,
  ) : ModelStatus
  data object Extracting : ModelStatus
  data object Ready : ModelStatus
  data class Error(val reason: String) : ModelStatus
}

data class ModelInfo(
  val id: String,
  val displayNameRes: Int,
  val category: ModelCategory,
  val estimatedSizeMb: Long,
)

enum class ModelCategory {
  STT, TRANSLATION, TTS, VAD, VOICE_CLONE
}

/**
 * Singleton manager for BaoTranslate ML models.
 *
 * Lifecycle: This object persists for the entire process lifetime and survives
 * Activity configuration changes. The [modelStatuses] StateFlow maintains download
 * progress across screen rotations and navigation.
 *
 * Thread safety: All public methods are safe to call from any thread. Internal
 * state updates use [MutableStateFlow.update] for atomic operations.
 *
 * Memory: Holds references to download jobs and status maps. Call [deleteAllModels] when
 * BaoTranslate feature is no longer needed to release resources.
 *
 * File layout: this object is the public API + status state. Spec tables live in
 * [BaoTranslateModelSpecs.kt], directory/path helpers in [BaoTranslateModelPaths.kt], download/
 * extract machinery in [BaoTranslateModelDownloads.kt], and caption registry helpers in
 * [BaoTranslateCaptionModels.kt].
 */
object BaoTranslateModelManager {
  private const val TAG = "BaoTranslateModels"

  private val _modelStatuses = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())
  val modelStatuses: StateFlow<Map<String, ModelStatus>> = _modelStatuses.asStateFlow()

  fun updateStatusExternal(modelId: String, status: ModelStatus) {
    _modelStatuses.update { current ->
      current.toMutableMap().apply {
        this[modelId] = status
      }
    }
  }

  // Promoted from private → internal so the extension download fns in
  // BaoTranslateModelDownloads.kt can emit status updates on the singleton.
  internal fun updateStatus(modelId: String, status: ModelStatus) {
    _modelStatuses.update { it + (modelId to status) }
  }

  val ALL_MODELS = listOf(
    ModelInfo(
      id = "kokoro_tts",
      displayNameRes = R.string.bao_model_kokoro_tts,
      category = ModelCategory.TTS,
      estimatedSizeMb = 142,
    ),
    ModelInfo(
      id = "silero_vad",
      displayNameRes = R.string.bao_model_silero_vad,
      category = ModelCategory.VAD,
      estimatedSizeMb = 2,
    ),
    ModelInfo(
      id = "whisper_base",
      displayNameRes = R.string.bao_model_whisper_base,
      category = ModelCategory.STT,
      estimatedSizeMb = 148,
    ),
    ModelInfo(
      id = "streaming_asr",
      displayNameRes = R.string.bao_model_streaming_asr,
      category = ModelCategory.STT,
      estimatedSizeMb = 44,
    ),
    ModelInfo(
      id = "qwen25_1b",
      displayNameRes = R.string.bao_model_qwen25_1b,
      category = ModelCategory.TRANSLATION,
      estimatedSizeMb = 1523,
    ),
    ModelInfo(
      id = "gemma4_e2b",
      displayNameRes = R.string.bao_model_gemma4_e2b,
      category = ModelCategory.TRANSLATION,
      estimatedSizeMb = 2468,
    ),
    ModelInfo(
      id = "openvoice",
      displayNameRes = R.string.bao_model_openvoice,
      category = ModelCategory.VOICE_CLONE,
      estimatedSizeMb = 125,
    ),
    ModelInfo(
      id = "supertonic_tts",
      displayNameRes = R.string.bao_model_supertonic_tts,
      category = ModelCategory.TTS,
      estimatedSizeMb = 80,
    ),
  )

  // The required model set — the SINGLE SOURCE OF TRUTH for auto-provisioning, "Required" settings
  // grouping, and readiness. Every member is treated identically: a download failure for ANY of them
  // is fatal to the provisioning run (downloadModel), and areRequiredModelsReady() requires
  // all of them. OpenVoice voice cloning is a first-class member — not an optional upgrade — so it is
  // always provisioned; the cloned voice activates the moment the user enrolls.
  val REQUIRED_MODEL_IDS = listOf("whisper_base", "qwen25_1b", "silero_vad", "kokoro_tts", "openvoice")

  // Models auto-provisioned for the full first-class experience, but NOT gating core translation
  // readiness: streaming_asr upgrades the live caption to a true streaming transducer, and the live
  // loop degrades gracefully to chunked-Whisper captions until it lands — so a user who could
  // translate yesterday is never blocked waiting for it after an upgrade.
  val AUTO_PROVISION_MODEL_IDS = REQUIRED_MODEL_IDS + "streaming_asr"

  fun refreshStatuses(context: Context) {
    // Merge-preserve transient in-flight statuses. A full replace from on-disk presence would
    // clobber a model that is actively Downloading/Extracting back to NotDownloaded (the files are
    // not fully present yet) if refreshStatuses runs during a download, hiding the progress UI even
    // though the background download is still running.
    _modelStatuses.update { current ->
      ALL_MODELS.associate { model ->
        val existing = current[model.id]
        model.id to if (existing is ModelStatus.Downloading || existing is ModelStatus.Extracting) {
          existing
        } else {
          checkModelStatus(context, model.id)
        }
      }
    }
  }

  fun checkModelStatus(context: Context, modelId: String): ModelStatus {
    return when (modelId) {
      "kokoro_tts" -> {
        val archive = ARCHIVES.first { it.modelId == modelId }
        if (isArchiveExtracted(context, archive)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "silero_vad" -> {
        val file = FILES.first { it.modelId == modelId }
        if (isFileDownloaded(context, file)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "whisper_base" -> {
        val dir = getWhisperModelDir(context)
        val encoderFile = File(dir, "base-encoder.int8.onnx")
        val decoderFile = File(dir, "base-decoder.int8.onnx")
        val tokensFile = File(dir, "base-tokens.txt")
        // length()>0 (not just exists()): a zero-byte file left by a killed write must not be Ready.
        if (encoderFile.length() > 0 && decoderFile.length() > 0 && tokensFile.length() > 0) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "qwen25_1b", "gemma4_e2b" -> {
        if (isTranslationModelDownloaded(context, modelId)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "openvoice" -> {
        if (isOpenVoiceCloneAvailable(context)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      "supertonic_tts", "streaming_asr" -> {
        val archive = ARCHIVES.first { it.modelId == modelId }
        if (isArchiveExtracted(context, archive)) ModelStatus.Ready
        else ModelStatus.NotDownloaded
      }
      else -> ModelStatus.NotDownloaded
    }
  }

  fun areRequiredModelsReady(context: Context): Boolean =
    REQUIRED_MODEL_IDS.all { checkModelStatus(context, it) == ModelStatus.Ready }

  fun areAllModelsReady(context: Context): Boolean =
    ALL_MODELS.all { checkModelStatus(context, it.id) == ModelStatus.Ready }

  fun getStorageBreakdown(context: Context): Map<String, Long> {
    val baseDir = getSherpaOnnxDir(context)

    val core =
      ALL_MODELS.associate { model ->
        val size = when (model.id) {
          "kokoro_tts" -> dirSize(File(baseDir, "kokoro-multi-lang-v1_0"))
          "silero_vad" -> File(baseDir, "silero_vad.onnx").takeIf { it.exists() }?.length() ?: 0L
          "whisper_base" -> dirSize(getWhisperModelDir(context))
          "streaming_asr" -> dirSize(getStreamingAsrModelDir(context))
          "qwen25_1b", "gemma4_e2b" -> dirSize(getTranslationModelDir(context, model.id))
          "openvoice" -> dirSize(getOpenVoiceDir(context))
          "supertonic_tts" -> dirSize(getSupertonicModelDir(context))
          else -> 0L
        }
        model.id to size
      }
    // Lazily-provisioned Vosk caption models are NOT in ALL_MODELS; include the ones actually on disk
    // so the storage total the user sees isn't undercounted by up to ~500MB.
    val captions =
      CAPTION_MODELS.values
        .filter { it.engine == CaptionEngine.VOSK }
        .associate { it.modelId to dirSize(File(baseDir, it.extractDirName)) }
        .filterValues { it > 0L }
    return core + captions
  }

  fun getDownloadedSizeBytes(context: Context): Long =
    getStorageBreakdown(context).values.sum()

  fun getTotalSizeBytes(): Long =
    ALL_MODELS.sumOf { it.estimatedSizeMb * 1024L * 1024L }

  /**
   * Download a single model by id. Returns success once the model is fully on disk (downloaded,
   * extracted if archived, and validated against the spec's required files). Returns failure if
   * any step fails or if storage is insufficient.
   *
   * Cancellation: passing a CoroutineScope that gets cancelled cancels the download mid-flight.
   */
  suspend fun downloadModel(
    context: Context,
    modelId: String,
    wifiOnly: Boolean = false,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    if (wifiOnly && !isWifiConnected(context)) {
      return@withContext Result.failure(Exception(context.getString(R.string.bao_translate_error_wifi_required)))
    }
    updateStatus(modelId, ModelStatus.Downloading(0f, 0L, 0L))

    // A mid-stream network drop or disk error during a multi-GB download throws (connect/read/
    // write/responseCode), which would otherwise escape `withContext`, bypass the fold below, and
    // crash the app while leaving the status stuck on Downloading. runCatchingCancellable converts
    // any such throw into the Result.failure the fold handles — but RETHROWS CancellationException
    // so a delete-triggered cancel actually cancels (and is not mislabeled as a download Error).
    val result =
      runCatchingCancellable {
          when (modelId) {
            "kokoro_tts" -> {
              val archive = ARCHIVES.first { it.modelId == modelId }
              downloadAndExtractArchive(context, archive, modelId)
            }
            "supertonic_tts" -> {
              val archive = ARCHIVES.first { it.modelId == modelId }
              downloadAndExtractArchive(context, archive, modelId)
            }
            "streaming_asr" -> {
              val archive = ARCHIVES.first { it.modelId == modelId }
              downloadAndExtractArchive(context, archive, modelId)
            }
            "silero_vad" -> {
              val file = FILES.first { it.modelId == modelId }
              downloadSingleFile(context, file, modelId)
            }
            "whisper_base" -> downloadWhisperModel(context, modelId)
            "qwen25_1b", "gemma4_e2b" -> downloadTranslationModel(context, modelId)
            "openvoice" -> downloadOpenVoiceModels(context, modelId)
            else -> Result.failure(
              IllegalArgumentException(context.getString(R.string.bao_translate_error_unknown_model, modelId))
            )
          }
        }
        .getOrElse { Result.failure(it) }

    result.fold(
      onSuccess = {
        updateStatus(modelId, ModelStatus.Ready)
        Result.success(Unit)
      },
      onFailure = { e ->
        updateStatus(modelId, ModelStatus.Error(e.message ?: context.getString(R.string.bao_error_unknown)))
        Result.failure(e)
      },
    )
  }

  /**
   * Auto-provision every model in [AUTO_PROVISION_MODEL_IDS] that is not already Ready. Returns
   * failure if ANY required model fails (mirrors the all-or-nothing contract callers expect).
   * Already-Ready models are skipped silently.
   */
  suspend fun downloadRequiredModels(
    context: Context,
    wifiOnly: Boolean = false,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    AUTO_PROVISION_MODEL_IDS.mapNotNull { id -> ALL_MODELS.firstOrNull { it.id == id } }.forEach { model ->
      if (checkModelStatus(context, model.id) != ModelStatus.Ready) {
        val result = downloadModel(context, model.id, wifiOnly = wifiOnly)
        if (result.isFailure) return@withContext result
      }
    }
    Result.success(Unit)
  }

  /**
   * Delete a single model from disk and reset its status to NotDownloaded. Safe to call when the
   * model is already missing (no-op).
   */
  fun deleteModel(context: Context, modelId: String) {
    when (modelId) {
      "kokoro_tts" -> File(getSherpaOnnxDir(context), "kokoro-multi-lang-v1_0").deleteRecursively()
      "silero_vad" -> File(getSherpaOnnxDir(context), "silero_vad.onnx").delete()
      "whisper_base" -> getWhisperModelDir(context).deleteRecursively()
      "streaming_asr" -> getStreamingAsrModelDir(context).deleteRecursively()
      "qwen25_1b", "gemma4_e2b" -> getTranslationModelDir(context, modelId).deleteRecursively()
      "openvoice" -> getOpenVoiceDir(context).deleteRecursively()
      "supertonic_tts" -> getSupertonicModelDir(context).deleteRecursively()
    }
    _modelStatuses.update { it + (modelId to ModelStatus.NotDownloaded) }
    BaoLog.i(TAG, "Deleted model: $modelId")
  }

  /** Delete every model. Used when the user resets the app or removes the BaoTranslate feature. */
  fun deleteAllModels(context: Context) {
    // Removes the Vosk caption models too — they live under the sherpa-onnx dir that is cleared here.
    getSherpaOnnxDir(context).deleteRecursively()
    getTranslationDir(context).deleteRecursively()
    // Reset the status of BOTH the core models AND the lazily-provisioned caption models, so a stale
    // "Ready" for a just-deleted vosk_<lang> can't linger in the status map.
    _modelStatuses.value =
      (ALL_MODELS.map { it.id } + CAPTION_MODELS.values.map { it.modelId })
        .distinct()
        .associateWith { ModelStatus.NotDownloaded }
    BaoLog.i(TAG, "Deleted all models")
  }
}