package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.Context
import android.os.storage.StorageManager
import com.google.ai.edge.gallery.R
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Builds the "incomplete download" failure. The message is a plural resource: the byte counts are
 * quantities, and several supported locales inflect on them, so a single %d-formatted string would
 * be ungrammatical in those languages.
 */
internal fun incompleteDownloadException(context: Context, actual: Long, expected: Long): Exception =
  Exception(
    context.resources.getQuantityString(
      R.plurals.bao_error_incomplete_download,
      quantityFor(actual),
      actual,
      expected,
    ),
  )

/** Clamps a byte count into the Int range `getQuantityString` requires, without overflowing. */
internal fun quantityFor(count: Long): Int = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/**
 * Free space the app may actually claim at [path], via StorageManager rather than [File.usableSpace].
 * usableSpace ignores the reclaimable cache other apps hold, so it under-reports and aborts
 * multi-GB model downloads that would in fact fit. Returns failure if the query throws.
 */
internal fun allocatableBytes(context: Context, path: File): Result<Long> =
  runCatching {
    val storageManager = context.getSystemService(StorageManager::class.java)
    storageManager.getAllocatableBytes(storageManager.getUuidForPath(path))
  }

/**
 * Filesystem utilities + readiness-check helpers for BaoTranslate model files. These were
 * previously embedded in [BaoTranslateModelDownloads.kt] but moved here so the downloads file
 * stays focused on orchestration. They are internal so the manager and download fns can share them.
 */

internal fun isArchiveExtracted(context: Context, archive: ArchiveSpec): Boolean =
  requiredFilesComplete(getSherpaOnnxDir(context), archive.requiredFiles)

// Existence alone is insufficient: an interrupted untar (or process kill) leaves
// espeak-ng-data present-but-empty, or files zero-length. Require directories to be
// non-empty and files to be non-zero, so a truncated/partial extraction reports NotDownloaded and
// re-downloads instead of feeding a corrupt model into native sherpa-onnx (SIGSEGV / garbage TTS).
// A trailing slash on a required entry denotes a directory entry (archive convention). Java's
// File silently strips the trailing separator, so a path like "model.onnx/" would otherwise match
// a regular file. Detect the marker before normalization and require such an entry to resolve to a
// non-empty directory, never a file — a directory entry pointing at a plain file is a type mismatch.
internal fun requiredFilesComplete(baseDir: File, requiredFiles: List<String>): Boolean =
  requiredFiles.all { rel ->
    val expectsDirectory = rel.endsWith("/") || rel.endsWith(File.separator)
    val f = File(baseDir, rel)
    when {
      !f.exists() -> false
      f.isDirectory -> f.listFiles()?.isNotEmpty() == true
      expectsDirectory -> false
      else -> f.length() > 0
    }
  }

internal fun isFileDownloaded(context: Context, file: FileSpec): Boolean {
  val target = File(getSherpaOnnxDir(context), file.fileName)
  return target.exists() && target.length() > 0
}

internal fun translationSpec(modelId: String): TranslationModelSpec =
  TRANSLATION_MODELS.first { it.modelId == modelId }

internal fun isTranslationModelDownloaded(context: Context, modelId: String): Boolean {
  val dir = getTranslationModelDir(context, modelId)
  val spec = translationSpec(modelId)
  val builtIn = File(dir, spec.fileName)
  val builtInReady = builtIn.exists() && builtIn.length() >= spec.sizeBytes
  val customTaskReady = dir
    .listFiles { file -> file.extension == "task" && file.length() > 0 }
    ?.isNotEmpty() == true

  return builtInReady || customTaskReady
}

internal fun dirSize(dir: File): Long {
  if (!dir.exists()) return 0L
  return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

internal fun isInsideDir(baseDir: File, candidate: File): Boolean {
  val basePath = baseDir.canonicalFile.toPath()
  val candidatePath = candidate.canonicalFile.toPath()
  return candidatePath.startsWith(basePath)
}

internal fun hasSymlinks(dir: File): Boolean {
  if (!dir.exists()) return false
  return dir.walkTopDown().any { java.nio.file.Files.isSymbolicLink(it.toPath()) }
}

internal fun isWifiConnected(context: Context): Boolean {
  val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
  val network = connectivityManager.activeNetwork ?: return false
  val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
  return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
}

// Like runCatching, but never swallows CancellationException — rethrowing it preserves structured
// concurrency so a cancelled (e.g. delete-triggered) download unwinds instead of being captured as
// a Result.failure and mislabeled an Error.
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
  runCatching(block).onFailure { if (it is CancellationException) throw it }