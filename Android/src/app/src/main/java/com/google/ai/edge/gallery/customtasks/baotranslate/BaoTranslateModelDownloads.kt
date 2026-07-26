package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.Context
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

private const val DL_TAG = "BaoTranslateModels"

/**
 * downloadAndExtractArchive — shared by kokoro_tts, streaming_asr, supertonic_tts.
 */
internal suspend fun BaoTranslateModelManager.downloadAndExtractArchive(
  context: Context,
  archive: ArchiveSpec,
  modelId: String,
): Result<Unit> = withContext(Dispatchers.IO) {
  if (isArchiveExtracted(context, archive)) {
    BaoLog.i(DL_TAG, "${archive.modelId} already extracted, skipping")
    return@withContext Result.success(Unit)
  }

  val baseDir = getSherpaOnnxDir(context)
  val archiveFile = File(baseDir, archive.archiveFileName)

  // We are here only because the model is NOT fully extracted. A leftover archive + a partial
  // extraction means a prior run was interrupted and the archive is likely truncated (extracting it
  // throws "Unexpected end of stream", leaving e.g. a missing vocoder) — so discard both and
  // re-download fresh instead of skipping the download and failing extraction forever.
  if (archiveFile.exists()) {
    BaoLog.w(DL_TAG, "${archive.modelId}: stale archive from an interrupted run; re-downloading clean")
    archiveFile.delete()
    File(baseDir, archive.extractDir).deleteRecursively()
  }
  val downloadResult = downloadFileWithProgress(
    context,
    archive.downloadUrl, archiveFile, archive.sizeBytes, archive.sizeBytes,
  ) { downloaded, total ->
    val progress = if (total > 0) downloaded.toFloat() / total else 0f
    updateStatus(modelId, ModelStatus.Downloading(progress, downloaded, total))
  }
  if (downloadResult.isFailure) {
    archiveFile.delete()
    return@withContext downloadResult
  }

  updateStatus(modelId, ModelStatus.Extracting)
  BaoLog.i(DL_TAG, "Extracting ${archive.modelId}...")

  if (hasSymlinks(baseDir)) {
    return@withContext Result.failure(SecurityException("Symlinks detected in extraction directory"))
  }

  TarArchiveInputStream(
    BZip2CompressorInputStream(
      BufferedInputStream(archiveFile.inputStream())
    )
  ).use { tar ->
    var entry = tar.nextEntry
    while (entry != null) {
      val outFile = File(baseDir, entry.name)
      if (!isInsideDir(baseDir, outFile)) {
        return@withContext Result.failure(SecurityException("Path traversal in archive: ${entry.name}"))
      }

      if (entry.isDirectory) {
        if (!outFile.mkdirs() && !outFile.exists()) {
          return@withContext Result.failure(Exception("Failed to create directory: ${entry.name}"))
        }
      } else {
        outFile.parentFile?.let { parent ->
          if (!parent.mkdirs() && !parent.exists()) {
            return@withContext Result.failure(Exception("Failed to create parent directory for: ${entry.name}"))
          }
        }
        FileOutputStream(outFile).use { output ->
          tar.copyTo(output)
        }
      }

      entry = tar.nextEntry
    }
  }

  archiveFile.delete()

  val missing = archive.requiredFiles.filter { !File(baseDir, it).exists() }
  if (missing.isNotEmpty()) {
    return@withContext Result.failure(Exception("Missing files after extraction: $missing"))
  }

  BaoLog.i(DL_TAG, "${archive.modelId} extracted successfully")
  Result.success(Unit)
}

/**
 * Lazily provisions the live-caption streaming model for a language. English reuses the eagerly
 * provisioned sherpa transducer; every other supported language downloads its Vosk model on demand.
 */
internal suspend fun BaoTranslateModelManager.downloadCaptionModel(
  context: Context,
  langCode: String,
): Result<Unit> = withContext(Dispatchers.IO) {
  val spec =
    CAPTION_MODELS[langCode]
      ?: return@withContext Result.failure(
        IllegalArgumentException("No streaming caption model for language '$langCode'")
      )
  if (isCaptionModelReady(context, langCode)) return@withContext Result.success(Unit)
  when (spec.engine) {
    CaptionEngine.SHERPA -> downloadModel(context, spec.modelId)
    CaptionEngine.VOSK -> downloadVoskCaptionModel(context, spec)
  }
}

internal suspend fun BaoTranslateModelManager.downloadVoskCaptionModel(
  context: Context,
  spec: CaptionModelSpec,
): Result<Unit> = withContext(Dispatchers.IO) {
  updateStatus(spec.modelId, ModelStatus.Downloading(0f, 0L, spec.sizeBytes))
  val baseDir = getSherpaOnnxDir(context)
  val archiveFile = File(baseDir, spec.archiveFileName)
  val extractDir = File(baseDir, spec.extractDirName)

  val result =
    runCatchingCancellable {
        // We are here only because the caption model is NOT ready. A leftover ZIP and partial
        // extraction can only be trusted after the readiness check passes; otherwise a killed
        // download/extract would make the next run reuse a truncated archive forever.
        if (archiveFile.exists()) {
          BaoLog.w(DL_TAG, "${spec.modelId}: stale caption archive from an interrupted run; re-downloading clean")
          archiveFile.delete()
          extractDir.deleteRecursively()
        }
        val dl =
          downloadFileWithProgress(
            context,
            spec.downloadUrl,
            archiveFile,
            spec.sizeBytes,
            spec.sizeBytes,
          ) { downloaded, total ->
            val progress = if (total > 0) downloaded.toFloat() / total else 0f
            updateStatus(spec.modelId, ModelStatus.Downloading(progress, downloaded, total))
          }
        if (dl.isFailure) {
          archiveFile.delete()
          return@runCatchingCancellable dl
        }
        updateStatus(spec.modelId, ModelStatus.Extracting)
        if (hasSymlinks(baseDir)) {
          return@runCatchingCancellable Result.failure<Unit>(
            SecurityException("Symlinks detected in extraction directory")
          )
        }
        val extracted = extractZip(archiveFile, baseDir)
        archiveFile.delete()
        if (extracted.isFailure) return@runCatchingCancellable extracted
        if (!isCaptionModelReady(context, spec.langCode)) {
          return@runCatchingCancellable Result.failure<Unit>(
            Exception("Vosk caption model ${spec.modelId} incomplete after extraction")
          )
        }
        BaoLog.i(DL_TAG, "Caption model ${spec.modelId} extracted successfully")
        Result.success(Unit)
      }
      .getOrElse { Result.failure(it) }

  result.fold(
    onSuccess = {
      updateStatus(spec.modelId, ModelStatus.Ready)
      Result.success(Unit)
    },
    onFailure = { e ->
      updateStatus(
        spec.modelId,
        ModelStatus.Error(e.message ?: context.getString(R.string.bao_error_unknown)),
      )
      Result.failure(e)
    },
  )
}

// Extracts a .zip into baseDir with the same path-traversal + symlink guards as the tar extractor.
internal fun extractZip(archiveFile: File, baseDir: File): Result<Unit> {
  ZipArchiveInputStream(BufferedInputStream(archiveFile.inputStream())).use { zip ->
    var entry = zip.nextEntry
    while (entry != null) {
      val outFile = File(baseDir, entry.name)
      if (!isInsideDir(baseDir, outFile)) {
        return Result.failure(SecurityException("Path traversal in archive: ${entry.name}"))
      }
      if (entry.isDirectory) {
        if (!outFile.mkdirs() && !outFile.exists()) {
          return Result.failure(Exception("Failed to create directory: ${entry.name}"))
        }
      } else {
        outFile.parentFile?.let { parent ->
          if (!parent.mkdirs() && !parent.exists()) {
            return Result.failure(Exception("Failed to create parent directory for: ${entry.name}"))
          }
        }
        FileOutputStream(outFile).use { output -> zip.copyTo(output) }
      }
      entry = zip.nextEntry
    }
  }
  return Result.success(Unit)
}

internal suspend fun BaoTranslateModelManager.downloadSingleFile(
  context: Context,
  file: FileSpec,
  modelId: String,
): Result<Unit> = withContext(Dispatchers.IO) {
  if (isFileDownloaded(context, file)) {
    BaoLog.i(DL_TAG, "${file.modelId} already downloaded, skipping")
    return@withContext Result.success(Unit)
  }

  val targetFile = File(getSherpaOnnxDir(context), file.fileName)
  targetFile.parentFile?.mkdirs()

  val result = downloadFileWithProgress(
    context,
    file.downloadUrl, targetFile, file.sizeBytes,
  ) { downloaded, total ->
    val progress = if (total > 0) downloaded.toFloat() / total else 0f
    updateStatus(modelId, ModelStatus.Downloading(progress, downloaded, total))
  }

  if (result.isFailure) {
    targetFile.delete()
  }
  result
}

internal suspend fun BaoTranslateModelManager.downloadWhisperModel(
  context: Context,
  modelId: String,
): Result<Unit> = withContext(Dispatchers.IO) {
  val modelDir = getWhisperModelDir(context)
  val encoderFile = File(modelDir, "base-encoder.int8.onnx")
  val decoderFile = File(modelDir, "base-decoder.int8.onnx")
  val tokensFile = File(modelDir, "base-tokens.txt")

  if (encoderFile.exists() && encoderFile.length() > 0 &&
    decoderFile.exists() && decoderFile.length() > 0 &&
    tokensFile.exists() && tokensFile.length() > 0
  ) {
    return@withContext Result.success(Unit)
  }

  modelDir.mkdirs()

  val whisperArchiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
  val archiveFile = File(modelDir.parentFile, "sherpa-onnx-whisper-base.tar.bz2")

  val downloadResult = downloadFileWithProgress(
    context,
    whisperArchiveUrl, archiveFile, 148_000_000L, 148_000_000L,
  ) { downloaded, total ->
    val progress = if (total > 0) downloaded.toFloat() / total else 0f
    updateStatus(modelId, ModelStatus.Downloading(progress, downloaded, total))
  }

  if (downloadResult.isFailure) {
    archiveFile.delete()
    return@withContext downloadResult
  }

  updateStatus(modelId, ModelStatus.Extracting)

  val baseDir = getSherpaOnnxDir(context)
  if (hasSymlinks(baseDir)) {
    return@withContext Result.failure(SecurityException("Symlinks detected in extraction directory"))
  }

  TarArchiveInputStream(
    BZip2CompressorInputStream(
      BufferedInputStream(archiveFile.inputStream())
    )
  ).use { tar ->
    var entry = tar.nextEntry
    while (entry != null) {
      val outFile = File(baseDir, entry.name)
      if (!isInsideDir(baseDir, outFile)) {
        return@withContext Result.failure(SecurityException("Path traversal: ${entry.name}"))
      }
      if (entry.isDirectory) {
        outFile.mkdirs()
      } else {
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { output -> tar.copyTo(output) }
      }
      entry = tar.nextEntry
    }
  }

  File(modelDir, "base-encoder.onnx").delete()
  File(modelDir, "base-decoder.onnx").delete()
  archiveFile.delete()

  if (encoderFile.exists() && decoderFile.exists() && tokensFile.exists()) {
    Result.success(Unit)
  } else {
    Result.failure(Exception("Whisper model files not found after extraction"))
  }
}

internal suspend fun BaoTranslateModelManager.downloadTranslationModel(
  context: Context,
  modelId: String,
): Result<Unit> = withContext(Dispatchers.IO) {
  if (isTranslationModelDownloaded(context, modelId)) {
    return@withContext Result.success(Unit)
  }

  val modelDir = getTranslationModelDir(context, modelId)
  modelDir.mkdirs()

  val spec = translationSpec(modelId)
  val targetFile = File(modelDir, spec.fileName)

  val downloadResult = downloadFileWithProgress(
    context,
    spec.downloadUrl, targetFile, spec.sizeBytes,
  ) { downloaded, total ->
    val progress = if (total > 0) downloaded.toFloat() / total else 0f
    updateStatus(modelId, ModelStatus.Downloading(progress, downloaded, total))
  }

  if (downloadResult.isFailure) {
    return@withContext downloadResult
  }

  if (targetFile.length() < spec.sizeBytes) {
    val actualSize = targetFile.length()
    targetFile.delete()
    return@withContext Result.failure(
      incompleteDownloadException(context, actualSize, spec.sizeBytes)
    )
  }

  Result.success(Unit)
}

internal suspend fun BaoTranslateModelManager.downloadOpenVoiceModels(
  context: Context,
  modelId: String,
): Result<Unit> = withContext(Dispatchers.IO) {
  if (isOpenVoiceCloneAvailable(context)) {
    BaoLog.i(DL_TAG, "$modelId already downloaded, skipping")
    return@withContext Result.success(Unit)
  }
  getOpenVoiceDir(context).mkdirs()

  // Combined progress across both files (converter ~128 MB + ref encoder ~3 MB) so the UI shows
  // one monotonic bar for the logical "voice cloning" model.
  val totalBytes = OPENVOICE_FILES.sumOf { it.sizeBytes }
  var completedBytes = 0L
  for (spec in OPENVOICE_FILES) {
    val target = File(getOpenVoiceDir(context), spec.targetName)
    val result = downloadFileWithProgress(
      context, spec.downloadUrl, target, spec.sizeBytes,
    ) { downloaded, _ ->
      val overall = completedBytes + downloaded
      val progress = if (totalBytes > 0) overall.toFloat() / totalBytes else 0f
      updateStatus(modelId, ModelStatus.Downloading(progress, overall, totalBytes))
    }
    if (result.isFailure) {
      target.delete()
      return@withContext result
    }
    // Reject a short file (200-with-wrong-length / silent truncation) so a corrupt ONNX never
    // reaches the runtime; sizeBytes is the pinned HF LFS blob size.
    if (target.length() < spec.sizeBytes) {
      val actual = target.length()
      target.delete()
      return@withContext Result.failure(
        incompleteDownloadException(context, actual, spec.sizeBytes)
      )
    }
    completedBytes += spec.sizeBytes
  }
  Result.success(Unit)
}