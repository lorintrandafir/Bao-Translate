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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.MessageBodyLoading
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.HoldToDictateViewModel
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.TextAndVoiceInput
import com.google.ai.edge.gallery.ui.common.textandvoiceinput.VoiceRecognizerOverlay
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatus
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.litertlm.ToolProvider

private const val TAG = "AGMAMainUi"

/**
 * Main body of the Mobile Actions screen: model initialization states, the prompt/response area,
 * and the dictation overlay.
 *
 * Split out of [MobileActionsScreen] so neither file grows unbounded. The individual sections each
 * own their own file — [WelcomeSection], [PromptTemplateBar], [ResponseTabs], [ModelInitErrorUi]
 * and [ErrorResetDialog] — so this function stays orchestration only.
 */
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
  val holdToDictateUiState by holdToDictateViewModel.uiState.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  var curAmplitude by remember { mutableIntStateOf(0) }
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

  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]?.status
  setAppBarControlsDisabled(
    curDownloadStatus == ModelDownloadStatusType.SUCCEEDED &&
      (!modelManagerUiState.isModelInitialized(model = model) || uiState.processing)
  )

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

  val initStatus = modelManagerUiState.modelInitializationStatus[model.name]
  val initError = initStatus?.status == ModelInitializationStatusType.ERROR

  if (initError) {
    ModelInitErrorUi(
      context = context,
      task = task,
      model = model,
      initStatus = initStatus,
      modelManagerViewModel = modelManagerViewModel,
    )
  } else if (!modelManagerUiState.isModelInitialized(model = model)) {
    Column(
      modifier = Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite },
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      CircularProgressIndicator(
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = Dimensions.Stroke.medium,
        modifier = Modifier.size(Dimensions.Icon.medium),
      )
      Spacer(modifier = Modifier.size(Dimensions.Spacing.medium))
      Text(
        text = stringResource(R.string.mobile_actions_loading_model),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  } else {
    val noFunctionCallSnackbarMessage = stringResource(R.string.snackbar_no_function_call)

    val send: (String) -> Unit = { text ->
      sendPrompt(
        text = text,
        task = task,
        model = model,
        tools = tools,
        viewModel = viewModel,
        curActions = curActions,
        scope = scope,
        snackbarHostState = snackbarHostState,
        focusManager = focusManager,
        context = context,
        resources = resources,
        noFunctionCallSnackbarMessage = noFunctionCallSnackbarMessage,
        onSelectedTabReset = { selectedTabIndex = 0 },
        onClearInputTrigger = { clearInputTextTrigger = System.currentTimeMillis() },
        onProcessingStarted = onProcessingStarted,
        onDoneGenerating = { doneGeneratingResponse = true },
      ) { error ->
        doneGeneratingResponse = true
        errorDialogContent = error
        showErrorDialog = true
      }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(
              bottom =
                if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                  bottomPadding
                } else {
                  Dimensions.Spacing.small
                }
            )
            .imePadding()
      ) {
        if (uiState.showWelcomeMessage) {
          WelcomeSection(task = task)
        } else {
          Box(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.CenterStart,
          ) {
            Text(
              uiState.userPrompt,
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.fillMaxWidth().padding(Dimensions.Spacing.medium),
            )
          }

          if (uiState.processing) {
            Box(
              modifier =
                Modifier.weight(1f)
                  .fillMaxWidth()
                  .padding(Dimensions.Spacing.medium)
                  .semantics { liveRegion = LiveRegionMode.Assertive },
              contentAlignment = Alignment.TopStart,
            ) {
              MessageBodyLoading()
            }
          } else {
            ResponseTabs(
              task = task,
              uiState = uiState,
              doneGeneratingResponse = doneGeneratingResponse,
            )
          }
        }

        Column(
          modifier = Modifier.fillMaxWidth().padding(top = Dimensions.Spacing.small),
          verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
        ) {
          PromptTemplateBar(processing = uiState.processing, onSend = send)

          Row(
            modifier = Modifier.padding(horizontal = Dimensions.Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
          ) {
            TextAndVoiceInput(
              task = task,
              processing = uiState.processing,
              holdToDictateViewModel = holdToDictateViewModel,
              onDone = { text -> send(text) },
              onAmplitudeChanged = { curAmplitude = it },
              clearTextTrigger = clearInputTextTrigger,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }

      AnimatedVisibility(
        holdToDictateUiState.recognizing,
        enter = fadeIn(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
        exit =
          fadeOut(
            animationSpec =
              tween(durationMillis = 100, easing = FastOutSlowInEasing, delayMillis = 300)
          ),
      ) {
        VoiceRecognizerOverlay(
          task = task,
          viewModel = holdToDictateViewModel,
          curAmplitude = curAmplitude,
          bottomPadding = bottomPadding,
        )
      }

      SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.padding(bottom = bottomPadding + 100.dp).align(Alignment.BottomCenter),
      )
    }
  }

  if (showErrorDialog) {
    ErrorResetDialog(
      task = task,
      errorContent = errorDialogContent,
      context = context,
      viewModel = viewModel,
      model = model,
      tools = tools,
      modelManagerViewModel = modelManagerViewModel,
      onDismiss = {
        showErrorDialog = false
        errorDialogContent = ""
      },
    ) {
      errorDialogContent = it
      showErrorDialog = true
    }
  }
}
