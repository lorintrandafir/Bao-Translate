/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.ui.common.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import com.google.ai.edge.gallery.R
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ai.edge.gallery.common.BaoLog

internal const val INPUT_TEXT_TAG = "MessageInputText"

/**
 * Closes the currently-expanded media panel. Pulled out of [MessageInputText] so that the
 * primary composable stays focused on the input surface and the call-site is a single line.
 */
@Composable
internal fun MediaPanelCloseButton(onClicked: () -> Unit) {
  Box(
    modifier =
      Modifier.offset(x = 10.dp, y = (-10).dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surface)
        .border((1.5).dp, MaterialTheme.colorScheme.outline, CircleShape)
        .clickable { onClicked() }
  ) {
    Icon(
      Icons.Rounded.Close,
      contentDescription = stringResource(R.string.cd_delete_icon),
      modifier = Modifier.padding(3.dp).size(16.dp),
    )
  }
}

/** Resize a bitmap to fit a long-edge bound while preserving the aspect ratio. */
internal fun resizeBitmap(originalBitmap: Bitmap, size: Int = 1024): Bitmap {
  val originalWidth = originalBitmap.width
  val originalHeight = originalBitmap.height

  // Return the original bitmap if it's already within the specified size.
  if (originalWidth <= size && originalHeight <= size) {
    return originalBitmap
  }

  val aspectRatio: Float = originalWidth.toFloat() / originalHeight.toFloat()
  val newWidth: Int
  val newHeight: Int

  if (aspectRatio > 1) {
    // Landscape or square orientation
    newWidth = size
    newHeight = (size / aspectRatio).toInt()
  } else {
    // Portrait orientation
    newHeight = size
    newWidth = (size * aspectRatio).toInt()
  }

  BaoLog.d(INPUT_TEXT_TAG, "Resizing image from $originalWidth x $originalHeight to $newWidth x $newHeight")

  // Create a new scaled bitmap using the calculated dimensions
  return originalBitmap.scale(newWidth, newHeight)
}

/**
 * Probe whether the device exposes a front-facing camera. Used by [MessageInputText] before
 * flipping to the camera-based capture branch. Falls back to `false` when [ProcessCameraProvider]
 * initialization throws — the caller treats that as "no front camera" rather than crashing.
 */
internal fun checkFrontCamera(context: Context, callback: (Boolean) -> Unit) {
  val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
  cameraProviderFuture.addListener(
    {
      val cameraProvider = cameraProviderFuture.get()
      runCatching {
        val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        callback(hasFront)
      }.onFailure { e ->
        BaoLog.e(INPUT_TEXT_TAG, "Failed to process audio input", e)
        callback(false)
      }
    },
    ContextCompat.getMainExecutor(context),
  )
}

/**
 * Lifecycle-aware accelerometer observer for inferring device rotation (0, 90, 180, -90). The
 * 7.0 m/s^2 axis threshold corresponds to gravity's pull along the cardinal directions; the
 * dead-zone between thresholds keeps the rotation stable when the device is held at an angle.
 * Unregistered on pause to avoid draining the battery.
 */
internal class SensorObserver(context: Context) :
  DefaultLifecycleObserver, SensorEventListener {
  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  var currentRotation = 0

  override fun onResume(owner: LifecycleOwner) {
    super.onResume(owner)
    accelerometer?.let {
      sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
    }
  }

  override fun onPause(owner: LifecycleOwner) {
    super.onPause(owner)
    sensorManager.unregisterListener(this)
  }

  override fun onSensorChanged(event: SensorEvent?) {
    if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
      val x = event.values[0]
      val y = event.values[1]

      // When the phone is on its side, gravity acts primarily along the x-axis.
      // When the phone is upright, gravity acts primarily along the y-axis.
      val newOrientation =
        when {
          x < -7.0 -> 90
          x > 7.0 -> -90
          y < -7.0 -> 180
          y > 7.0 -> 0
          else -> currentRotation // Keep the last known orientation
        }

      if (newOrientation != currentRotation) {
        currentRotation = newOrientation
      }
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
