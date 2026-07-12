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
package com.google.ai.edge.gallery.ui.common

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.google.ai.edge.gallery.common.BaoLog

private const val TAG = "CameraBinding"

/**
 * Unbinds any prior use cases and rebinds [useCases] to [lifecycleOwner]. Binding can fail when no
 * camera is present, the lens is unavailable, or the requested use cases exceed device capability.
 * Per official CameraX guidance, failures are logged at error level rather than swallowed — a
 * silent failure leaves the preview black with no diagnostic trail.
 *
 * Returns the bound [Camera] on success, or `null` on failure (caller keeps the provider handle
 * for a later rebind attempt).
 */
internal fun ProcessCameraProvider.rebindSafely(
  lifecycleOwner: LifecycleOwner,
  cameraSelector: CameraSelector,
  vararg useCases: UseCase,
): Camera? =
  runCatching {
      unbindAll()
      bindToLifecycle(lifecycleOwner, cameraSelector, *useCases)
    }
    .onFailure { exc -> BaoLog.e(TAG, "Failed to bind camera use cases to lifecycle", exc) }
    .getOrNull()
