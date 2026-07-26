
package com.google.ai.edge.gallery.customtasks.agentchat

import android.os.Bundle
import com.google.ai.edge.gallery.common.BaoLog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.clearFocusOnKeyboardDismiss
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.common.SmallFilledTonalButton
import com.google.ai.edge.gallery.ui.common.SmallOutlinedButton
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGMcpManagerBottomSheet"

/**
 * A bottom sheet that allows users to manage configured MCP servers, search through them, add new
 * ones, toggle their enabled states, and launch secondary views for detailed tool inspection.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

@Composable
internal fun EmptyMcpServerView(onAddClick: () -> Unit, onDismiss: () -> Unit) {
  val focusManager = LocalFocusManager.current

  Column(
    modifier =
      Modifier.padding(horizontal = Dimensions.Spacing.medium).padding(bottom = Dimensions.Spacing.medium).fillMaxSize().pointerInput(
        Unit
      ) {
        detectTapGestures(onTap = { focusManager.clearFocus() })
      }
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = Dimensions.Spacing.small),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(modifier = Modifier.padding(end = 3.dp), onClick = onDismiss) {
        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
      }
    }
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
          onClick = onAddClick,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
          Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(Dimensions.Component.iconSmall))
          Spacer(modifier = Modifier.width(Dimensions.Spacing.xs))
          Text(stringResource(R.string.add_mcp_server))
        }
        ClickableLink(
          url = "https://github.com/google-ai-edge/gallery/tree/main/mcp",
          linkText = stringResource(R.string.learn_more_about_mcp),
          modifier = Modifier.padding(top = Dimensions.Spacing.medium),
        )
      }
    }
  }
}

@Composable
internal fun McpServerItemRow(
  serverState: McpServerState,
  onEnabledChange: (Boolean) -> Unit,
  onToolsClick: () -> Unit,
  onDeleteClick: () -> Unit,
) {
  val server = serverState.mcpServer
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape = RoundedCornerShape(Dimensions.Component.chipCornerRadius))
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .padding(horizontal = Dimensions.Spacing.medium, vertical = Dimensions.Spacing.md),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small)) {
        // Column to display server details like name, URL, and any error messages.
        Column(
          modifier = Modifier.weight(1f).padding(top = Dimensions.Spacing.xxs),
          verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.sm),
        ) {
          val hasName = server.name.isNotEmpty()
          val primaryText = if (hasName) server.name else server.url
          // Displays the server name, or just the URL if the name is empty.
          Text(
            primaryText,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
          )
          // Displays the server version if available when a name is present.
          if (hasName && server.version.isNotEmpty()) {
            Text(
              "v${server.version}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          // Displays the server URL below the version if the name is used as title.
          if (hasName) {
            Text(
              server.url,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.MiddleEllipsis,
            )
          }
          // Displays an error message if the server has one.
          serverState.error?.let { errorMsg ->
            Text(
              text = errorMsg,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        // Switch to toggle the enabled state of the MCP server.
        val toggleServerDesc = stringResource(R.string.cd_toggle_server, server.url)
        Switch(
          checked = server.enabled,
          onCheckedChange = onEnabledChange,
          enabled = serverState.error == null,
          modifier =
            Modifier.offset(y = -Dimensions.Spacing.xs).semantics { contentDescription = toggleServerDesc },
        )
      }

      // Buttons row
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.padding(top = Dimensions.Spacing.medium),
      ) {
        val enabledToolsCount = server.toolsList.count { it.enabled }
        val totalToolsCount = server.toolsList.size
        SmallFilledTonalButton(
          onClick = onToolsClick,
          label = "Tools ($enabledToolsCount/$totalToolsCount)",
          imageVector = Icons.Outlined.Tune,
          enabled = serverState.error == null,
        )
        Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
        SmallOutlinedButton(
          onClick = onDeleteClick,
          labelResId = R.string.delete,
          imageVector = Icons.Outlined.Delete,
        )
      }
    }
  }
}
