package com.google.ai.edge.gallery.customtasks.baotranslate

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.waveformAmplitude
import com.google.ai.edge.gallery.customtasks.baotranslate.config.PipelineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ENROLLMENT_SAMPLE_RATE = PipelineConfig.STT_SAMPLE_RATE

internal fun startRecording(
  context: android.content.Context,
  scope: kotlinx.coroutines.CoroutineScope,
  onAmplitudeUpdate: (Float) -> Unit,
  onDurationUpdate: (Long) -> Unit,
  onSamplesReady: (ShortArray) -> Unit,
  onRecordingStarted: (AudioRecord) -> Unit,
  onJobStarted: (Job) -> Unit,
  onRecordingFailed: () -> Unit,
) {
  val minBufferSize = AudioRecord.getMinBufferSize(
    ENROLLMENT_SAMPLE_RATE,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
  )
  if (minBufferSize <= 0) {
    onRecordingFailed()
    return
  }

  if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
    PackageManager.PERMISSION_GRANTED
  ) {
    onRecordingFailed()
    return
  }

  // Oversize the capture buffer (0.5 s of PCM16, never below 4x the OS minimum) so stalls in the
  // enrollment read loop never overrun AudioRecord and drop frames — corrupt enrollment audio
  // permanently degrades the cloned-voice embedding. Mirrors RecordingController's capture sizing.
  val captureBufferBytes = maxOf(minBufferSize * 4, ENROLLMENT_SAMPLE_RATE * Short.SIZE_BYTES / 2)
  val recorder = AudioRecord(
    MediaRecorder.AudioSource.MIC,
    ENROLLMENT_SAMPLE_RATE,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    captureBufferBytes,
  )

  if (recorder.state != AudioRecord.STATE_INITIALIZED) {
    recorder.release()
    onRecordingFailed()
    return
  }

  val buffer = ShortArray((minBufferSize / 2).coerceAtLeast(ENROLLMENT_SAMPLE_RATE / 10))
  val maxSamples = ENROLLMENT_SAMPLE_RATE * 30
  val allSamples = ShortArray(maxSamples)
  var sampleCount = 0

  recorder.startRecording()

  if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
    recorder.release()
    onRecordingFailed()
    return
  }

  onRecordingStarted(recorder)

  val job = scope.launch(Dispatchers.IO) {
    while (isActive) {
      val readCount = recorder.read(buffer, 0, buffer.size)
      if (readCount > 0) {
        val copyCount = minOf(readCount, maxSamples - sampleCount)
        if (copyCount > 0) {
          System.arraycopy(buffer, 0, allSamples, sampleCount, copyCount)
          sampleCount += copyCount
        }

        val amplitude = waveformAmplitude(buffer, readCount)

        withContext(Dispatchers.Main) {
          onAmplitudeUpdate(amplitude)
          onDurationUpdate((sampleCount * 1000L) / ENROLLMENT_SAMPLE_RATE)
          onSamplesReady(allSamples.copyOf(sampleCount))
        }
      } else if (readCount == AudioRecord.ERROR_INVALID_OPERATION || readCount == AudioRecord.ERROR_BAD_VALUE) {
        break
      }
    }

    withContext(Dispatchers.Main) {
      onSamplesReady(allSamples.copyOf(sampleCount))
    }
  }

  onJobStarted(job)
}
