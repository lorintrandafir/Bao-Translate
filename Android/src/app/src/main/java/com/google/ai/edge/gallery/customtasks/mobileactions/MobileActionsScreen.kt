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
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.ToolProvider

/**
 * A Composable function that displays the MobileActions screen.
 *
 * This screen allows users to interact with an AI model using voice or text input to perform
 * various actions on their device.
 */
@Composable
fun MobileActionsScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  mobileActionsViewModel: MobileActionsViewModel = hiltViewModel(),
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  curActions: SnapshotStateList<Action>,
  tools: List<ToolProvider>,
  onProcessingStarted: () -> Unit,
) {
  var recordAudioPermissionGranted by remember { mutableStateOf(false) }
  val context = LocalContext.current

  // Permission request when recording audio clips.
  val recordAudioClipsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        recordAudioPermissionGranted = true
      }
    }

  // Ask for audio recording permission.
  LaunchedEffect(Unit) {
    // Check permission.
    when (PackageManager.PERMISSION_GRANTED) {
      // Already got permission. Call the lambda.
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
        recordAudioPermissionGranted = true
      }

      // Otherwise, ask for permission
      else -> {
        recordAudioClipsPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  if (recordAudioPermissionGranted) {
    Column(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()
    ) {
      MainUi(
        task = task,
        modelManagerViewModel = modelManagerViewModel,
        tools = tools,
        bottomPadding = bottomPadding,
        viewModel = mobileActionsViewModel,
        curActions = curActions,
        setAppBarControlsDisabled = setAppBarControlsDisabled,
        onProcessingStarted = onProcessingStarted,
      )
    }
  }
}
