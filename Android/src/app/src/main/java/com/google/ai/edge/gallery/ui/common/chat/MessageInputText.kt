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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import com.google.ai.edge.gallery.common.BaoLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.rounded.AudioFile

import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.AudioClip
import com.google.ai.edge.gallery.data.MAX_AUDIO_CLIP_COUNT
import com.google.ai.edge.gallery.data.MAX_IMAGE_COUNT
import com.google.ai.edge.gallery.data.MAX_IMAGE_COUNT_AI_CORE
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.getTaskIconColor
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.bodyLargeNarrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGMessageInputText"

/**
 * Composable function to display a text input field for composing chat messages.
 *
 * This function renders a row containing a text field for message input and a send button. It
 * handles message composition, input validation, and sending messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInputText(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  curMessage: String,
  isResettingSession: Boolean,
  inProgress: Boolean,
  imageCount: Int,
  audioClipMessageCount: Int,
  skillCount: Int = 0,
  mcpCount: Int = 0,
  modelInitializing: Boolean,
  @StringRes textFieldPlaceHolderRes: Int,
  onValueChanged: (String) -> Unit,
  onSendMessage: (List<ChatMessage>) -> Unit,
  modelPreparing: Boolean = false,
  onOpenPromptTemplatesClicked: () -> Unit = {},
  onStopButtonClicked: () -> Unit = {},
  onSetAudioRecorderVisible: (visible: Boolean) -> Unit = {},
  onAmplitudeChanged: (Int) -> Unit,
  onSkillsClicked: () -> Unit = {},
  onMcpClicked: () -> Unit = {},
  onPickedImagesChanged: (List<Bitmap>) -> Unit = {},
  onPickedAudioClipsChanged: (List<AudioClip>) -> Unit = {},
  showPromptTemplatesInMenu: Boolean = false,
  showSkillsPicker: Boolean = false,
  showMcpPicker: Boolean = false,
  showImagePicker: Boolean = false,
  showAudioPicker: Boolean = false,
  showStopButtonWhenInProgress: Boolean = false,
  onImageLimitExceeded: () -> Unit = {},
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  var showAddContentMenu by remember { mutableStateOf(false) }
  var showTextInputHistorySheet by remember { mutableStateOf(false) }
  var showCameraCaptureBottomSheet by remember { mutableStateOf(false) }
  val cameraCaptureSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showAudioRecorder by remember { mutableStateOf(false) }
  val audioRecorderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var pickedImages by remember { mutableStateOf<List<Bitmap>>(listOf()) }
  var pickedAudioClips by remember { mutableStateOf<List<AudioClip>>(listOf()) }
  var hasFrontCamera by remember { mutableStateOf(false) }
  val sensorObserver = remember { SensorObserver(context) }

  val updatePickedImages: (List<Bitmap>) -> Unit = { bitmaps ->
    val isAiCore = modelManagerUiState.selectedModel.runtimeType == RuntimeType.AICORE
    var limit = MAX_IMAGE_COUNT
    if (isAiCore) {
      limit = MAX_IMAGE_COUNT_AI_CORE
    }
    val maxAllowedForThisMessage = (limit - imageCount).coerceAtLeast(0)

    val combinedSize = pickedImages.size + bitmaps.size
    val withinLimit = combinedSize <= maxAllowedForThisMessage

    pickedImages =
      if (withinLimit) {
        pickedImages + bitmaps
      } else {
        if (isAiCore) {
          scope.launch(Dispatchers.Main) { onImageLimitExceeded() }
        }
        (pickedImages + bitmaps).take(maxAllowedForThisMessage)
      }
  }

  val updatePickedAudioClips: (List<AudioClip>) -> Unit = { audioDataList ->
    val maxAllowedForThisMessage = (MAX_AUDIO_CLIP_COUNT - audioClipMessageCount).coerceAtLeast(0)

    val combinedSize = pickedAudioClips.size + audioDataList.size
    val withinLimit = combinedSize <= maxAllowedForThisMessage

    pickedAudioClips =
      if (withinLimit) {
        pickedAudioClips + audioDataList
      } else {
        (pickedAudioClips + audioDataList).take(maxAllowedForThisMessage)
      }
  }

  LaunchedEffect(Unit) { checkFrontCamera(context = context, callback = { hasFrontCamera = it }) }

  LaunchedEffect(pickedImages) { onPickedImagesChanged(pickedImages) }

  LaunchedEffect(pickedAudioClips) { onPickedAudioClipsChanged(pickedAudioClips) }

  // Permission request when taking picture.
  val takePicturePermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        showAddContentMenu = false
        showCameraCaptureBottomSheet = true
      }
    }

  val handleClickRecordAudioClip = {
    showAddContentMenu = false
    showAudioRecorder = true
    onSetAudioRecorderVisible(true)
  }

  // Permission request when recording audio clips.
  val recordAudioClipsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        handleClickRecordAudioClip()
      }
    }

  // Registers a photo picker activity launcher in single-select mode.
  val pickMedia =
    rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
      // Callback is invoked after the user selects media items or closes the
      // photo picker.
      if (uris.isNotEmpty()) {
        scope.launch(Dispatchers.IO) {
          handleImagesSelected(
            context = context,
            uris = uris,
            onImagesSelected = { bitmaps -> updatePickedImages(bitmaps) },
          )
        }
      }
    }

  val pickWav =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == android.app.Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          BaoLog.d(TAG, "Picked wav file: $uri")
          scope.launch(Dispatchers.IO) {
            handleAudioWavSelected(
              context = context,
              uri = uri,
              onAudioSelected = { audioClip ->
                updatePickedAudioClips(
                  listOf(
                    AudioClip(audioData = audioClip.audioData, sampleRate = audioClip.sampleRate)
                  )
                )
              },
            )
          }
        }
      } else {
        BaoLog.d(TAG, "Wav picking cancelled.")
      }
    }

  DisposableEffect(lifecycleOwner) {
    lifecycleOwner.lifecycle.addObserver(sensorObserver)
    onDispose { lifecycleOwner.lifecycle.removeObserver(sensorObserver) }
  }

  Column {
    MediaPreviewPanel(
      pickedImages = pickedImages,
      pickedAudioClips = pickedAudioClips,
      onRemoveImage = { image -> pickedImages = pickedImages.filter { it != image } },
      onRemoveAudioClip = { index -> pickedAudioClips = pickedAudioClips.filterIndexed { i, _ -> i != index } },
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = Dimensions.Component.inputMinHeight)) {
      AnimatedContent(targetState = showAudioRecorder) { curShowAudioRecorder ->
        when (curShowAudioRecorder) {
          // Input
          false ->
            Column(
              modifier =
                Modifier.padding(horizontal = Dimensions.Spacing.md)
                  .padding(vertical = Dimensions.Spacing.small)
                  .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Dimensions.Spacing.medium))
            ) {
              // First row: text field for input.
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                // Text field.
                val cdPromptInput = stringResource(R.string.cd_prompt_input_text_field)
                TextField(
                  value = curMessage,
                  minLines = 1,
                  maxLines = 3,
                  onValueChange = onValueChanged,
                  colors =
                    TextFieldDefaults.colors(
                      unfocusedContainerColor = Color.Transparent,
                      focusedContainerColor = Color.Transparent,
                      focusedIndicatorColor = Color.Transparent,
                      unfocusedIndicatorColor = Color.Transparent,
                      disabledIndicatorColor = Color.Transparent,
                      disabledContainerColor = Color.Transparent,
                    ),
                  textStyle = bodyLargeNarrow,
                  modifier = Modifier.weight(1f).semantics { contentDescription = cdPromptInput },
                  placeholder = { Text(stringResource(textFieldPlaceHolderRes)) },
                )
                Spacer(modifier = Modifier.width(Dimensions.Spacing.xs))
              }

              // Second row: buttons to add extra content, and the action button.
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimensions.Spacing.md).offset(y = -Dimensions.Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs),
                ) {
                  // A plus button to show a popup menu to add stuff to the chat.
                  val isImageLimitExceededForAiCore =
                    modelManagerUiState.selectedModel.runtimeType == RuntimeType.AICORE &&
                      (imageCount + pickedImages.size) >= MAX_IMAGE_COUNT_AI_CORE
                  val enableAddImageMenuItems =
                    (imageCount + pickedImages.size) < MAX_IMAGE_COUNT
                  val enableRecordAudioClipMenuItems =
                    (audioClipMessageCount + pickedAudioClips.size) < MAX_AUDIO_CLIP_COUNT
                  AddContentMenuButton(
                    expanded = showAddContentMenu,
                    onDismiss = { showAddContentMenu = false },
                    showImagePicker = showImagePicker,
                    showAudioPicker = showAudioPicker,
                    enableAddImageMenuItems = enableAddImageMenuItems,
                    enableRecordAudioClipMenuItems = enableRecordAudioClipMenuItems,
                    isImageLimitExceededForAiCore = isImageLimitExceededForAiCore,
                    onImageLimitExceeded = onImageLimitExceeded,
                    inProgress = inProgress,
                    isResettingSession = isResettingSession,
                    modelInitializing = modelInitializing,
                    context = context,
                    takePicturePermissionLauncher = takePicturePermissionLauncher,
                    recordAudioClipsPermissionLauncher = recordAudioClipsPermissionLauncher,
                    pickMedia = pickMedia,
                    pickWav = pickWav,
                    onCameraGranted = { showCameraCaptureBottomSheet = true },
                    onRecordAudioGranted = { handleClickRecordAudioClip() },
                    onPickFromAlbum = {
                      pickMedia.launch(
                        PickVisualMediaRequest(
                          ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                      )
                    },
                    onPickWav = {
                      val intent =
                        Intent(Intent.ACTION_GET_CONTENT).apply {
                          addCategory(Intent.CATEGORY_OPENABLE)
                          type = "audio/*"
                          val mimeTypes = arrayOf("audio/wav", "audio/x-wav")
                          putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                          putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                      pickWav.launch(intent)
                    },
                    onShowInputHistory = { showTextInputHistorySheet = true },
                  )

                  // Skills.
                  if (showSkillsPicker) {
                    SkillsPickerButton(
                      count = skillCount,
                      enabled = !inProgress && !isResettingSession && !modelInitializing,
                    ) {
                      onSkillsClicked()
                    }
                  }

                  // MCP.
                  if (showMcpPicker) {
                    SkillsPickerButton(
                      count = mcpCount,
                      enabled = !inProgress && !isResettingSession && !modelInitializing,
                      labelRes = R.string.mcp,
                    ) {
                      onMcpClicked()
                    }
                  }
                }

                if (inProgress && showStopButtonWhenInProgress) {
                  if (!modelInitializing && !modelPreparing) {
                    StopButton(onClick = onStopButtonClicked)
                  }
                } else {
                  SendButton(
                    enabled =
                      !inProgress &&
                        !isResettingSession &&
                        (curMessage.isNotEmpty() || pickedAudioClips.isNotEmpty()),
                    task = task,
                    onClick = {
                      var message = curMessage.trim()
                      onSendMessage(
                        createMessagesToSend(
                          pickedImages = pickedImages,
                          audioClips = pickedAudioClips,
                          text = message,
                        )
                      )
                      pickedImages = listOf()
                      pickedAudioClips = listOf()
                    },
                  )
                }
              }
            }

          // Audio recorder.
          true ->
            AudioRecorderPanel(
              task = task,
              onSendAudioClip = { audioData ->
                scope.launch {
                  updatePickedAudioClips(
                    listOf(AudioClip(audioData = audioData, sampleRate = SAMPLE_RATE))
                  )
                  audioRecorderSheetState.hide()
                  showAudioRecorder = false
                  onSetAudioRecorderVisible(false)
                }
              },
              onAmplitudeChanged = onAmplitudeChanged,
              onClose = {
                showAudioRecorder = false
                onSetAudioRecorderVisible(false)
              },
            )
        }
      }
    }
  }

  // A bottom sheet to show the text input history to pick from.
  if (showTextInputHistorySheet) {
    TextInputHistorySheet(
      history = modelManagerUiState.textInputHistory,
      onDismissed = { showTextInputHistorySheet = false },
      onHistoryItemClicked = { item ->
        onSendMessage(
          createMessagesToSend(
            pickedImages = pickedImages,
            audioClips = pickedAudioClips,
            text = item,
          )
        )
        pickedImages = listOf()
        pickedAudioClips = listOf()
        modelManagerViewModel.promoteTextInputHistoryItem(item)
      },
      onHistoryItemDeleted = { item -> modelManagerViewModel.deleteTextInputHistory(item) },
      onHistoryItemsDeleteAll = { modelManagerViewModel.clearTextInputHistory() },
    )
  }

  CameraCaptureBottomSheet(
    showCameraCaptureBottomSheet = showCameraCaptureBottomSheet,
    hasFrontCamera = hasFrontCamera,
    sensorObserver = sensorObserver,
    onPickedImagesChanged = { updatePickedImages(it) },
    onDismiss = { showCameraCaptureBottomSheet = false },
    onSheetHidden = { showCameraCaptureBottomSheet = false },
  )
}


