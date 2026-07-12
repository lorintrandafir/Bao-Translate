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

import android.content.res.Resources
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatus
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGMAScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainUi(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  tools: List<ToolProvider>,
  bottomPadding: Dp,
  viewModel: MobileActionsViewModel,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  curActions: SnapshotStateList<Action>,
  holdToDictateViewModel: HoldToDictateViewModel = hiltViewModel(),
  onProcessingStarted: () -> Unit,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val initialModelConfigValues = remember { model.configValues }
  val uiState by viewModel.uiState.collectAsState()
  var clearInputTextTrigger by remember { mutableLongStateOf(0L) }
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  var doneGeneratingResponse by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var errorDialogContent by remember { mutableStateOf("") }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val focusManager = LocalFocusManager.current
  val resources = LocalResources.current
  val taskColor = getTaskBgGradientColors(task = task)[1]

  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]?.status
  setAppBarControlsDisabled(
    curDownloadStatus == ModelDownloadStatusType.SUCCEEDED &&
      (!modelManagerUiState.isModelInitialized(model = model) || uiState.processing)
  )

  // Reset states on config changes.
  LaunchedEffect(model.configValues) {
    if (model.configValues != initialModelConfigValues) {
      BaoLog.d(TAG, "model config values changed.")
      modelManagerViewModel.setInitializationStatus(
        model = model,
        status = ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED),
      )
      viewModel.reset()
    }
  }

  DisposableEffect(Unit) { onDispose { viewModel.cleanUp() } }

  // Show a loading indicator before the model is initialized.
  if (!modelManagerUiState.isModelInitialized(model = model)) {
    Row(
      modifier = Modifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator(
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = 3.dp,
        modifier = Modifier.size(24.dp),
      )
    }
  }
  // Main UI.
  else {
    val noFunctionCallSnackbarMessage = stringResource(R.string.snackbar_no_function_call)

    val send: (String) -> Unit = { text ->
      scope.launch(Dispatchers.Main) {
        selectedTabIndex = 0
        clearInputTextTrigger = System.currentTimeMillis()
        focusManager.clearFocus()
      }

      onProcessingStarted()

      // Figure out the correct action from user prompt.
      doneGeneratingResponse = false
      viewModel.processUserPrompt(
        model = model,
        userPrompt = text,
        tools = tools,
        onProcessDone = {
          doneGeneratingResponse = true
          BaoLog.d(TAG, "Actions count: ${curActions.size}")

          // Execute functions.
          if (curActions.isNotEmpty()) {
            val errors = mutableListOf<String>()
            for (action in curActions) {
              val curError = viewModel.performAction(action = action, context = context)
              if (curError.isEmpty()) {
                viewModel.addFunctionCallDetails(
                  details = genFormattedFunctionCall(action = action, resources = resources)
                )
              } else {
                errors.add(curError)
              }
            }
            if (errors.isNotEmpty()) {
              scope.launch {
                snackbarHostState.showSnackbar(
                  errors.joinToString(separator = "; "),
                  withDismissAction = true,
                  duration = SnackbarDuration.Long,
                )
              }
            }
          }
          // No function recognized.
          else {
            viewModel.setNoFunctionRecognized(value = true)

            // Show a snack bar for unrecognized command.
            scope.launch {
              snackbarHostState.showSnackbar(
                noFunctionCallSnackbarMessage,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
              )
            }
          }
        },
        onError = { error ->
          doneGeneratingResponse = true

          // Show error dialog for users to reset the engine.
          errorDialogContent = error
          showErrorDialog = true
        },
      )

      firebaseAnalytics?.logEvent(
        GalleryEvent.GENERATE_ACTION.id,
        Bundle().apply {
          putString("capability_name", task.id)
          putString("model_id", model.name)
        },
      )
    }

    MobileActionsContent(
      uiState = uiState,
      holdToDictateViewModel = holdToDictateViewModel,
      selectedTabIndex = selectedTabIndex,
      onTabSelected = { selectedTabIndex = it },
      doneGeneratingResponse = doneGeneratingResponse,
      taskColor = taskColor,
      task = task,
      clearInputTextTrigger = clearInputTextTrigger,
      send = send,
      bottomPadding = bottomPadding,
      snackbarHostState = snackbarHostState,
    )
  }

  if (showErrorDialog) {
    AlertDialog(
      title = { Text(stringResource(R.string.error)) },
      text = { Text(errorDialogContent, style = MaterialTheme.typography.bodyMedium) },
      onDismissRequest = {
        showErrorDialog = false
        errorDialogContent = ""
      },
      dismissButton = {
        TextButton(
          onClick = {
            showErrorDialog = false
            errorDialogContent = ""
          }
        ) {
          Text(stringResource(R.string.cancel))
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showErrorDialog = false
            errorDialogContent = ""

            viewModel.resetEngine(
              context = context,
              model = model,
              tools = tools,
              modelManagerViewModel = modelManagerViewModel,
              onError = {
                errorDialogContent = it
                showErrorDialog = true
              },
            )
          },
          colors = ButtonDefaults.buttonColors(containerColor = taskColor),
        ) {
          Text(stringResource(R.string.reset), color = Color.White)
        }
      },
    )
  }
}

private fun genFormattedFunctionCall(action: Action, resources: Resources): String {
  val strFunctionName = action.functionCallDetails.functionName
  val functionNameLabel = resources.getString(R.string.function_name)
  var content = "**$functionNameLabel**:\n- $strFunctionName"
  if (action.functionCallDetails.parameters.isNotEmpty()) {
    val parametersLabel =
      resources.getQuantityString(R.plurals.parameter, action.functionCallDetails.parameters.size)
    val strParameters =
      action.functionCallDetails.parameters.joinToString("\n") { "- ${it.first}: \"${it.second}\"" }
    content += "\n\n**$parametersLabel**:\n$strParameters"
  }
  return content
}
