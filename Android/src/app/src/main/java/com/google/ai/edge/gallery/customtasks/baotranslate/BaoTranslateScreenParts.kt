package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.ConnectionState
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.launch

/**
 * Scaffold top bar for [BaoTranslateScreen], extracted so the screen composable stays under the
 * 500-LOC monolith threshold. Contains the title plus the action icons: clear transcript,
 * conversation mode, face-to-face mode, and settings.
 */
@Composable
internal fun BaoTranslateTopBar(
  uiState: BaoTranslateUiState,
  showConversationMode: Boolean,
  showFaceToFace: Boolean,
  connectionState: ConnectionState,
  isTablet: Boolean,
  maxWidth: Dp,
  onClearTranscripts: () -> Unit,
  onToggleConversationMode: () -> Unit,
  onEnterFaceToFace: () -> Unit,
  onExitFaceToFace: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  TopAppBar(
    title = {
      Text(
        stringResource(R.string.bao_translate_title),
        style = if (isTablet) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
      )
    },
    actions = {
      val conversationModeDesc = stringResource(R.string.bao_translate_conversation_mode)
      val settingsDesc = stringResource(R.string.settings_title)
      val clearDesc = stringResource(R.string.cd_bao_translate_clear)
      // Clear the current conversation transcript (local-only). Shown only when there is
      // something to clear and the user is on the translate view, not in conversation pairing.
      if (uiState.modelsReady && !showConversationMode && uiState.transcripts.isNotEmpty()) {
        IconButton(
          onClick = onClearTranscripts,
          modifier = Modifier.semantics { contentDescription = clearDesc },
        ) {
          Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
        }
      }
      if (uiState.modelsReady) {
        val conversationTooltipState = rememberTooltipState(isPersistent = true)
        val conversationTooltipScope = rememberCoroutineScope()
        TooltipBox(
          positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
          tooltip = { PlainTooltip { Text(stringResource(R.string.bao_translate_tooltip_conversation)) } },
          state = conversationTooltipState,
        ) {
          IconButton(
            onClick = onToggleConversationMode,
            modifier = Modifier
              .semantics { contentDescription = conversationModeDesc }
              .onFocusChanged { focusState ->
                if (focusState.isFocused) {
                  conversationTooltipScope.launch { conversationTooltipState.show() }
                }
              },
          ) {
            Icon(
              imageVector = Icons.Default.People,
              contentDescription = null,
              // Primary tint while the pairing sheet is open OR a peer is actively connected, so
              // the user can see they are in a live conversation after returning to the main view.
              tint = if (showConversationMode || connectionState == ConnectionState.CONNECTED) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurface
              },
            )
          }
        }
      }
      if (uiState.modelsReady) {
        val faceToFaceDesc = stringResource(R.string.bao_face_to_face_mode)
        IconButton(
          onClick = {
            if (showFaceToFace) onExitFaceToFace() else onEnterFaceToFace()
          },
          modifier = Modifier.semantics { contentDescription = faceToFaceDesc },
        ) {
          Icon(
            imageVector = Icons.Default.SwapVert,
            contentDescription = null,
            tint = if (showFaceToFace) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
          )
        }
      }
      IconButton(
        onClick = onOpenSettings,
        modifier = Modifier.semantics { contentDescription = settingsDesc },
      ) {
        Icon(imageVector = Icons.Default.Settings, contentDescription = null)
      }
    },
    modifier = Modifier.widthIn(max = maxWidth),
  )
}

/**
 * Recording control FAB for [BaoTranslateScreen]. Hidden during face-to-face mode (each panel owns
 * its own turn control). The button is a combined click + long-press tooltip that toggles recording.
 */
@Composable
internal fun RecordingControlFab(
  uiState: BaoTranslateUiState,
  showFaceToFace: Boolean,
  showConversationMode: Boolean,
  connectionState: ConnectionState,
  onStartRecording: () -> Unit,
  onStopRecording: () -> Unit,
) {
  // No FAB in face-to-face: each rotated panel owns its ConversationTurnControl, which is the
  // single start/stop affordance readable from both sides of the table.
  val showFab = uiState.modelsReady && !showFaceToFace && (
    !showConversationMode || connectionState == ConnectionState.CONNECTED
  )
  if (showFab) {
    val startDesc = stringResource(R.string.cd_bao_translate_start)
    val stopDesc = stringResource(R.string.cd_bao_translate_stop)
    val canUseMic = uiState.modelsReady &&
      !uiState.isInitializing &&
      !uiState.isStartingRecording &&
      !uiState.isProcessing &&
      !uiState.isSpeaking &&
      uiState.pipelineStatus !is PipelineStatus.ModelsNotReady
    val recordingControlActive = uiState.isRecording || uiState.isStartingRecording
    val tooltipState = rememberTooltipState(isPersistent = true)
    val tooltipScope = rememberCoroutineScope()
    TooltipBox(
      positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
      tooltip = { PlainTooltip { Text(stringResource(R.string.bao_translate_tooltip_record)) } },
      state = tooltipState,
    ) {
      Box(
        modifier = Modifier
          .size(Dimensions.Component.fabSize)
          .clip(CircleShape)
          .background(
            when {
              recordingControlActive -> MaterialTheme.customColors.recordButtonBgColor
              canUseMic -> MaterialTheme.colorScheme.primary
              else -> MaterialTheme.colorScheme.surfaceVariant
            }
          )
          .combinedClickable(
            onClick = {
              when {
                recordingControlActive -> onStopRecording()
                canUseMic -> onStartRecording()
              }
            },
            onLongClick = {
              tooltipScope.launch { tooltipState.show() }
            }
          )
          .onFocusChanged { focusState ->
            if (focusState.isFocused) {
              tooltipScope.launch { tooltipState.show() }
            }
          }
          .semantics {
            contentDescription = if (recordingControlActive) stopDesc else startDesc
          },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (recordingControlActive) Icons.Default.Stop else Icons.Default.Mic,
          contentDescription = null,
          modifier = Modifier.size(Dimensions.Icon.large),
          tint = when {
            recordingControlActive -> MaterialTheme.colorScheme.onPrimary
            canUseMic -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
    }
  }
}