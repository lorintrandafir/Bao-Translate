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

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.AudioClip
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.common.convertWavToMonoWithMaxSeconds
import com.google.ai.edge.gallery.common.decodeSampledBitmapFromUri
import com.google.ai.edge.gallery.common.rotateBitmap
import com.google.ai.edge.gallery.data.MAX_AUDIO_CLIP_COUNT
import com.google.ai.edge.gallery.data.MAX_IMAGE_COUNT
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.getTaskIconColor
import androidx.exifinterface.media.ExifInterface
import java.io.FileInputStream

private const val TAG = "AGMessageInputText"

internal fun handleImagesSelected(
  context: Context,
  uris: List<Uri>,
  onImagesSelected: (List<Bitmap>) -> Unit,
) {
  val images: MutableList<Bitmap> = mutableListOf()
  for (uri in uris) {
    val bitmap: Bitmap? =
      runCatching {
        val inputStream =
          if (uri.scheme == null || uri.scheme == "file") {
            FileInputStream(uri.path ?: "")
          } else {
            context.contentResolver.openInputStream(uri)
          }
        if (inputStream != null) {
          val exif = ExifInterface(inputStream)
          val orientation =
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
          inputStream.close()
          decodeSampledBitmapFromUri(context, uri, 1024, 1024)?.let { originalBitmap ->
            rotateBitmap(bitmap = originalBitmap, orientation = orientation)
          }
        } else {
          null
        }
      }.getOrElse { e ->
        BaoLog.e(TAG, "Failed to decode selected image", e)
        null
      }
    if (bitmap != null) {
      images.add(bitmap)
    }
  }
  if (images.isNotEmpty()) {
    onImagesSelected(images)
  }
}

internal fun handleAudioWavSelected(
  context: Context,
  uri: Uri,
  onAudioSelected: (AudioClip) -> Unit,
) {
  convertWavToMonoWithMaxSeconds(context = context, stereoUri = uri)?.let { audioClip ->
    onAudioSelected(audioClip)
  }
}

internal fun createMessagesToSend(
  pickedImages: List<Bitmap>,
  audioClips: List<AudioClip>,
  text: String,
): List<ChatMessage> {
  val messages: MutableList<ChatMessage> = mutableListOf()
  if (pickedImages.isNotEmpty()) {
    var curPickedImages = pickedImages.toList()
    if (curPickedImages.size > MAX_IMAGE_COUNT) {
      curPickedImages = curPickedImages.subList(fromIndex = 0, toIndex = MAX_IMAGE_COUNT)
    }
    messages.add(
      ChatMessageImage(
        bitmaps = curPickedImages,
        imageBitMaps = curPickedImages.map { it.asImageBitmap() },
        side = ChatSide.USER,
      )
    )
  }
  var audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
  if (audioClips.isNotEmpty()) {
    for (audioClip in audioClips) {
      audioMessages.add(
        ChatMessageAudioClip(
          audioData = audioClip.audioData,
          sampleRate = audioClip.sampleRate,
          side = ChatSide.USER,
        )
      )
    }
  }
  if (audioMessages.size > MAX_AUDIO_CLIP_COUNT) {
    audioMessages = audioMessages.subList(fromIndex = 0, toIndex = MAX_AUDIO_CLIP_COUNT)
  }
  messages.addAll(audioMessages)
  if (text.isNotEmpty()) {
    messages.add(ChatMessageText(content = text, side = ChatSide.USER))
  }
  return messages
}

@Composable
internal fun MediaPreviewPanel(
  pickedImages: List<Bitmap>,
  pickedAudioClips: List<AudioClip>,
  onRemoveImage: (Bitmap) -> Unit,
  onRemoveAudioClip: (Int) -> Unit,
) {
  if (pickedImages.isNotEmpty() || pickedAudioClips.isNotEmpty()) {
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Spacer(modifier = Modifier.width(16.dp))
      for (image in pickedImages) {
        Box(contentAlignment = Alignment.TopEnd) {
          Image(
            bitmap = image.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_image_thumbnail),
            modifier =
              Modifier.height(80.dp)
                .shadow(2.dp, shape = RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
          )
          MediaPanelCloseButton { onRemoveImage(image) }
        }
      }
      for ((index, audioClip) in pickedAudioClips.withIndex()) {
        Box(contentAlignment = Alignment.TopEnd) {
          Box(
            modifier =
              Modifier.shadow(2.dp, shape = RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
          ) {
            AudioPlaybackPanel(
              audioData = audioClip.audioData,
              sampleRate = audioClip.sampleRate,
              isRecording = false,
              modifier = Modifier.padding(end = 16.dp),
            )
          }
          MediaPanelCloseButton {
            onRemoveAudioClip(index)
          }
        }
      }
      Spacer(modifier = Modifier.width(16.dp))
    }
  }
}

@Composable
internal fun SendButton(
  enabled: Boolean,
  task: Task,
  onClick: () -> Unit,
) {
  IconButton(
    enabled = enabled,
    onClick = onClick,
    colors =
      IconButtonDefaults.iconButtonColors(
        containerColor = getTaskIconColor(task = task),
        disabledContainerColor = getTaskIconColor(task = task).copy(alpha = 0.3f),
      ),
  ) {
    Icon(
      Icons.AutoMirrored.Rounded.Send,
      contentDescription = stringResource(R.string.cd_send_prompt_icon),
      modifier = Modifier.size(24.dp),
      tint = Color.White,
    )
  }
}

@Composable
internal fun StopButton(
  onClick: () -> Unit,
) {
  IconButton(
    onClick = onClick,
    colors =
      IconButtonDefaults.iconButtonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer
      ),
  ) {
    Icon(
      Icons.Rounded.Stop,
      contentDescription = stringResource(R.string.cd_stop_icon),
      tint = MaterialTheme.colorScheme.primary,
    )
  }
}

/**
 * A button + dropdown menu that lets the user add image, audio, or input-history content to a chat
 * message. Encapsulates permission checks (camera, microphone) and the menu item visibility flags.
 *
 * @param expanded Whether the dropdown menu is currently shown.
 * @param onDismiss Callback invoked when the menu requests dismissal.
 * @param showImagePicker Whether to show the image-related menu items.
 * @param showAudioPicker Whether to show the audio-related menu items.
 * @param enableAddImageMenuItems Whether the image menu items are enabled.
 * @param enableRecordAudioClipMenuItems Whether the audio menu items are enabled.
 * @param isImageLimitExceededForAiCore Whether the AI Core image limit has been reached.
 * @param onImageLimitExceeded Callback invoked when the AI Core image limit is exceeded.
 * @param inProgress Whether a chat response is currently being generated.
 * @param isResettingSession Whether the session is currently being reset.
 * @param modelInitializing Whether the model is currently initializing.
 * @param context Android context, used for permission checks.
 * @param takePicturePermissionLauncher Launcher for the camera permission request.
 * @param recordAudioClipsPermissionLauncher Launcher for the microphone permission request.
 * @param pickMedia Launcher for the photo picker.
 * @param pickWav Launcher for the WAV file picker.
 * @param onCameraGranted Callback invoked when camera permission is granted (or already granted).
 * @param onRecordAudioGranted Callback invoked when microphone permission is granted.
 * @param onPickFromAlbum Callback invoked when the user picks "Pick from album".
 * @param onPickWav Callback invoked when the user picks "Pick WAV".
 * @param onShowInputHistory Callback invoked when the user picks "Input history".
 */
@Composable
internal fun AddContentMenuButton(
  expanded: Boolean,
  onDismiss: () -> Unit,
  showImagePicker: Boolean,
  showAudioPicker: Boolean,
  enableAddImageMenuItems: Boolean,
  enableRecordAudioClipMenuItems: Boolean,
  isImageLimitExceededForAiCore: Boolean,
  onImageLimitExceeded: () -> Unit,
  inProgress: Boolean,
  isResettingSession: Boolean,
  modelInitializing: Boolean,
  context: android.content.Context,
  takePicturePermissionLauncher: ActivityResultLauncher<String>,
  recordAudioClipsPermissionLauncher: ActivityResultLauncher<String>,
  pickMedia: ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>,
  pickWav: ActivityResultLauncher<android.content.Intent>,
  onCameraGranted: () -> Unit,
  onRecordAudioGranted: () -> Unit,
  onPickFromAlbum: () -> Unit,
  onPickWav: () -> Unit,
  onShowInputHistory: () -> Unit,
) {
  val enableAddButton = !inProgress && !isResettingSession && !modelInitializing
  Box {
    OutlinedIconButton(
      enabled = enableAddButton,
      onClick = { onDismiss() },
      colors =
        IconButtonDefaults.iconButtonColors(
          disabledContentColor =
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
        ),
      border =
        IconButtonDefaults.outlinedIconButtonBorder(true)
          .copy(
            brush =
              SolidColor(
                MaterialTheme.colorScheme.outlineVariant.copy(
                  alpha = if (enableAddButton) 1f else 0.1f
                )
              )
          ),
    ) {
      Icon(
        Icons.Outlined.Add,
        contentDescription = stringResource(R.string.cd_add_content_icon),
        modifier = Modifier.size(24.dp),
      )
    }

    DropdownMenu(
      expanded = expanded,
      onDismissRequest = onDismiss,
    ) {
      if (showImagePicker) {
        // Take a picture.
        DropdownMenuItem(
          text = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
              Text(stringResource(R.string.chat_take_picture))
            }
          },
          enabled = enableAddImageMenuItems,
          onClick = {
            if (isImageLimitExceededForAiCore) {
              onImageLimitExceeded()
              onDismiss()
              return@DropdownMenuItem
            }
            when (android.content.pm.PackageManager.PERMISSION_GRANTED) {
              androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
              ) -> {
                onDismiss()
                onCameraGranted()
              }
              else -> {
                takePicturePermissionLauncher.launch(android.Manifest.permission.CAMERA)
              }
            }
          },
        )

        // Pick an image from album.
        DropdownMenuItem(
          text = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(Icons.Rounded.Photo, contentDescription = null)
              Text(stringResource(R.string.chat_pick_from_album))
            }
          },
          enabled = enableAddImageMenuItems,
          onClick = {
            if (isImageLimitExceededForAiCore) {
              onImageLimitExceeded()
              onDismiss()
              return@DropdownMenuItem
            }
            onPickFromAlbum()
            onDismiss()
          },
        )
      }

      // Audio related menu items.
      if (showAudioPicker) {
        DropdownMenuItem(
          text = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(Icons.Rounded.Mic, contentDescription = null)
              Text(stringResource(R.string.chat_record_audio))
            }
          },
          enabled = enableRecordAudioClipMenuItems,
          onClick = {
            when (android.content.pm.PackageManager.PERMISSION_GRANTED) {
              androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
              ) -> {
                onRecordAudioGranted()
              }
              else -> {
                recordAudioClipsPermissionLauncher.launch(
                  android.Manifest.permission.RECORD_AUDIO
                )
              }
            }
          },
        )

        DropdownMenuItem(
          text = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(Icons.Rounded.AudioFile, contentDescription = null)
              Text(stringResource(R.string.chat_pick_wav))
            }
          },
          enabled = enableRecordAudioClipMenuItems,
          onClick = {
            onDismiss()
            onPickWav()
          },
        )
      }

      // Prompt history.
      DropdownMenuItem(
        text = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(Icons.Rounded.History, contentDescription = null)
            Text(stringResource(R.string.chat_input_history))
          }
        },
        onClick = {
          onDismiss()
          onShowInputHistory()
        },
      )
    }
  }
}


/**
 * An outlined button with a label and a circular badge showing a count. Used for the Skills and MCP
 * pickers on the chat input toolbar.
 */
@Composable
internal fun SkillsPickerButton(
  count: Int,
  enabled: Boolean,
  onClick: () -> Unit,
  labelRes: Int = R.string.skills,
) {
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(labelRes))
      Spacer(modifier = Modifier.width(4.dp))
      Box(
        contentAlignment = Alignment.Center,
        modifier =
          Modifier.background(
              MaterialTheme.colorScheme.surfaceContainer,
              shape = CircleShape,
            )
            .height(18.dp)
            .widthIn(min = 18.dp),
      ) {
        Text(
          text = count.toString(),
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
  }
}
