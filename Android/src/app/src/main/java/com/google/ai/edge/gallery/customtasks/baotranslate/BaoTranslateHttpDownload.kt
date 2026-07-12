package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.Context
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

private const val HTTP_TAG = "BaoTranslateModels"
internal const val MODEL_DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000
internal const val MODEL_DOWNLOAD_READ_TIMEOUT_MS = 300_000
internal const val MODEL_DOWNLOAD_MAX_ATTEMPTS = 5

/**
 * Wrapper that disconnects the underlying [HttpURLConnection] on [close]. We use this to release
 * the connection back to the pool on every exit path (success, exception, cancellation) so we
 * don't leak sockets when a download is cancelled mid-flight and the use{} block never closes.
 */
private class AutoDisconnectConnection(private val connection: HttpURLConnection) : AutoCloseable {
  val inputStream get() = connection.inputStream
  val responseCode get() = connection.responseCode
  val contentLengthLong get() = connection.contentLengthLong

  override fun close() {
    connection.disconnect()
  }
}

internal suspend fun downloadFileWithProgress(
  context: Context,
  url: String,
  targetFile: File,
  expectedSize: Long,
  reserveExtraBytes: Long = 0L,
  onProgress: (downloaded: Long, total: Long) -> Unit,
): Result<Unit> = withContext(Dispatchers.IO) {
  var attempt = 1
  var lastFailure: Throwable? = null
  while (attempt <= MODEL_DOWNLOAD_MAX_ATTEMPTS) {
    val result =
      runCatchingCancellable {
          downloadFileWithProgressAttempt(
            context,
            url,
            targetFile,
            expectedSize,
            reserveExtraBytes,
            onProgress,
          )
        }
        .getOrElse { Result.failure(it) }
    if (result.isSuccess) return@withContext result

    lastFailure = result.exceptionOrNull()
    if (attempt < MODEL_DOWNLOAD_MAX_ATTEMPTS) {
      val retryDelayMs = attempt * 1_000L
      BaoLog.w(
        HTTP_TAG,
        "Download attempt $attempt/$MODEL_DOWNLOAD_MAX_ATTEMPTS failed for $url: " +
          "${lastFailure?.message}; retrying with resume in ${retryDelayMs}ms",
      )
      delay(retryDelayMs)
    }
    attempt += 1
  }
  Result.failure(lastFailure ?: Exception("Download failed for $url"))
}

internal suspend fun downloadFileWithProgressAttempt(
  context: Context,
  url: String,
  targetFile: File,
  expectedSize: Long,
  reserveExtraBytes: Long = 0L,
  onProgress: (downloaded: Long, total: Long) -> Unit,
): Result<Unit> = withContext(Dispatchers.IO) {
  BaoLog.i(HTTP_TAG, "Downloading $url")
  val parentDir = targetFile.parentFile
  parentDir?.mkdirs()

  var resumeFrom = 0L
  if (targetFile.exists() && targetFile.length() > 0) {
    resumeFrom = targetFile.length()
    BaoLog.i(HTTP_TAG, "Resuming download from $resumeFrom bytes")
  }

  val usableSpace = parentDir?.usableSpace ?: targetFile.usableSpace
  val remainingBytes = (expectedSize - resumeFrom.coerceAtMost(expectedSize)).coerceAtLeast(0L)
  val minimumSpace = remainingBytes + reserveExtraBytes
  if (minimumSpace > 0 && usableSpace in 1 until minimumSpace) {
    return@withContext Result.failure(
      Exception(context.getString(R.string.bao_translate_error_storage_required, minimumSpace / (1024 * 1024)))
    )
  }

  val parsedUrl = URL(url)
  if (parsedUrl.protocol != "https" && parsedUrl.protocol != "http") {
    return@withContext Result.failure(IllegalArgumentException("Invalid URL scheme: ${parsedUrl.protocol}"))
  }

  val connection = com.google.ai.edge.gallery.common.network.HttpClient.openConnectionWithHeaders(
    url = parsedUrl,
    headers = if (resumeFrom > 0) mapOf("Range" to "bytes=$resumeFrom-") else emptyMap(),
    connectTimeout = MODEL_DOWNLOAD_CONNECT_TIMEOUT_MS,
    readTimeout = MODEL_DOWNLOAD_READ_TIMEOUT_MS,
  )
  connection.instanceFollowRedirects = true

  connection.connect()

  AutoDisconnectConnection(connection).use { conn ->
    val responseCode = conn.responseCode
    if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
      val errorMsg = when (responseCode) {
        401, 403 -> context.getString(R.string.bao_error_license_required)
        404 -> context.getString(R.string.bao_error_model_not_found, url)
        else -> "HTTP $responseCode for $url"
      }
      return@withContext Result.failure(Exception(errorMsg))
    }

    val supportsRange = responseCode == HttpURLConnection.HTTP_PARTIAL
    if (resumeFrom > 0 && !supportsRange) {
      targetFile.delete()
      resumeFrom = 0L
      BaoLog.i(HTTP_TAG, "Server does not support Range; restarting download")
    }

    val totalSize = conn.contentLengthLong.takeIf { it > 0 }?.let {
      if (supportsRange) it + resumeFrom else it
    } ?: expectedSize
    var downloaded = if (supportsRange) resumeFrom else 0L
    var lastEmitTime = System.currentTimeMillis()
    var lastEmittedProgress = -1f

    conn.inputStream.use { input ->
      FileOutputStream(targetFile, supportsRange).use { output ->
        val buffer = ByteArray(8192)
        var bytesRead: Int

        while (input.read(buffer).also { bytesRead = it } != -1) {
          // Cooperative cancellation: a delete-during-download cancels this coroutine; check each
          // chunk so cancel()/join() returns promptly instead of blocking on the full transfer.
          coroutineContext.ensureActive()
          output.write(buffer, 0, bytesRead)
          downloaded += bytesRead
          if (totalSize > 0) {
            val progress = downloaded.toFloat() / totalSize
            val now = System.currentTimeMillis()
            if (now - lastEmitTime >= 100 || progress - lastEmittedProgress >= 0.01f) {
              onProgress(downloaded, totalSize)
              lastEmitTime = now
              lastEmittedProgress = progress
            }
          }
        }
      }
    }

    if (totalSize > 0 && downloaded < totalSize) {
      return@withContext Result.failure(
        Exception(context.getString(R.string.bao_error_incomplete_download, downloaded, totalSize))
      )
    }

    BaoLog.i(HTTP_TAG, "Downloaded: $downloaded bytes -> ${targetFile.absolutePath}")
    Result.success(Unit)
  }
}