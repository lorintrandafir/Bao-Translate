/*
 * Copyright 2025 Google LLC
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

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.ui.common.rebindSafely
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

private const val TAG = "AGMessageInputCamera"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraCaptureBottomSheet(
  showCameraCaptureBottomSheet: Boolean,
  hasFrontCamera: Boolean,
  sensorObserver: SensorObserver,
  onPickedImagesChanged: (List<Bitmap>) -> Unit,
  onDismiss: () -> Unit,
  onSheetHidden: () -> Unit,
) {
  if (showCameraCaptureBottomSheet) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewUseCase = remember { Preview.Builder().build() }
    val imageCaptureUseCase = remember {
      // Try to limit the image size.
      val preferredSize = Size(512, 512)
      val resolutionStrategy =
        ResolutionStrategy(
          preferredSize,
          ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
        )
      val resolutionSelector =
        ResolutionSelector.Builder()
          .setResolutionStrategy(resolutionStrategy)
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          .build()

      ImageCapture.Builder().setResolutionSelector(resolutionSelector).build()
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    val localContext = LocalContext.current
    var cameraSide by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun rebindCameraProvider() {
      cameraProvider?.let { provider ->
        val cameraSelector = CameraSelector.Builder().requireLensFacing(cameraSide).build()
        val camera =
          provider.rebindSafely(
            lifecycleOwner = lifecycleOwner,
            cameraSelector = cameraSelector,
            previewUseCase,
            imageCaptureUseCase,
          )
        cameraControl = camera?.cameraControl
      }
    }

    LaunchedEffect(Unit) {
      cameraProvider = ProcessCameraProvider.awaitInstance(localContext)
      rebindCameraProvider()
    }

    LaunchedEffect(cameraSide) { rebindCameraProvider() }

    DisposableEffect(Unit) {
      onDispose {
        cameraProvider?.unbindAll()
        if (!executor.isShutdown) {
          executor.shutdown()
        }
      }
    }

    ModalBottomSheet(
      sheetState = sheetState,
      onDismissRequest = { onDismiss() },
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        // PreviewView for the camera feed.
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            PreviewView(ctx).also {
              previewUseCase.surfaceProvider = it.surfaceProvider
              rebindCameraProvider()
            }
          },
        )

        // Close button.
        IconButton(
          onClick = {
            scope.launch {
              sheetState.hide()
              onSheetHidden()
            }
          },
          colors =
            IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
          modifier = Modifier.offset(x = (-8).dp, y = 8.dp).align(Alignment.TopEnd),
        ) {
          Icon(
            Icons.Rounded.Close,
            contentDescription = stringResource(R.string.cd_close_icon),
            tint = MaterialTheme.colorScheme.primary,
          )
        }

        // Button that triggers the image capture process
        IconButton(
          colors =
            IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.primary
            ),
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .padding(bottom = 32.dp)
              .size(size = 64.dp)
              .border(width = 2.dp, color = MaterialTheme.colorScheme.onPrimary, CircleShape),
          onClick = {
            val callback =
              object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                  val captureOutcome =
                    runCatching {
                      var bitmap = image.toBitmap()
                      val rotation =
                        sensorObserver.currentRotation + image.imageInfo.rotationDegrees
                      bitmap =
                        if (rotation != 0) {
                          val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                          Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            matrix,
                            true,
                          )
                        } else bitmap
                      bitmap = resizeBitmap(originalBitmap = bitmap)
                      onPickedImagesChanged(listOf(bitmap))
                    }
                  image.close()
                  captureOutcome.onFailure { e ->
                    BaoLog.e(TAG, "Failed to process image", e)
                  }
                  scope.launch {
                    sheetState.hide()
                    onSheetHidden()
                  }
                }
              }
            imageCaptureUseCase.takePicture(executor, callback)
          },
        ) {
          Icon(
            Icons.Rounded.PhotoCamera,
            contentDescription = stringResource(R.string.cd_camera_shutter_icon),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(36.dp),
          )
        }

        // Button that toggles the front and back camera.
        if (hasFrontCamera) {
          IconButton(
            colors =
              IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
              ),
            modifier =
              Modifier.align(Alignment.BottomEnd)
                .padding(bottom = 40.dp, end = 32.dp)
                .size(48.dp),
            onClick = {
              cameraSide =
                when (cameraSide) {
                  CameraSelector.LENS_FACING_BACK -> CameraSelector.LENS_FACING_FRONT
                  else -> CameraSelector.LENS_FACING_BACK
                }
            },
          ) {
            Icon(
              Icons.Rounded.FlipCameraAndroid,
              contentDescription = stringResource(R.string.cd_toggle_front_back_camera_icon),
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.size(24.dp),
            )
          }
        }
      }
    }
  }
}
