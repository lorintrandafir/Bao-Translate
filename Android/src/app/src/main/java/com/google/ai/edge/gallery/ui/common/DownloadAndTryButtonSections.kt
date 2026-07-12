package com.google.ai.edge.gallery.ui.common

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * Download progress row shown in place of the main button while a model is downloading,
 * being checked for auth, or partially downloaded. Includes animated progress bar and cancel.
 */
@Composable
internal fun DownloadProgressSection(
  compact: Boolean,
  task: Task?,
  downloadProgress: Float,
  checkingToken: Boolean,
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier,
  onCancel: () -> Unit,
) {
  val animatedProgress = remember { Animatable(0f) }

  var downloadProgressModifier: Modifier = modifier
  if (!compact) {
    downloadProgressModifier = downloadProgressModifier.fillMaxWidth()
  }
  downloadProgressModifier =
    downloadProgressModifier
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .padding(horizontal = 8.dp)
      .height(42.dp)
  Row(modifier = downloadProgressModifier, verticalAlignment = Alignment.CenterVertically) {
    if (checkingToken) {
      Text(
        stringResource(R.string.checking_access),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = if (!compact) Modifier.fillMaxWidth() else Modifier.padding(horizontal = 4.dp),
      )
    } else {
      Text(
        "${(downloadProgress * 100).toInt()}%",
        style =
          MaterialTheme.typography.bodyMedium.copy(
            // This stops numbers from "jumping around" when being updated.
            fontFeatureSettings = "tnum"
          ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 12.dp).width(if (compact) 32.dp else 44.dp),
      )
      if (!compact) {
        val color =
          if (task != null) getTaskBgGradientColors(task = task)[1]
          else MaterialTheme.colorScheme.primary
        LinearProgressIndicator(
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
          progress = { animatedProgress.value },
          color = color,
          trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
      }
      val cbStop = stringResource(R.string.cd_stop_icon)
      IconButton(
        onClick = {
          onCancel()
          modelManagerViewModel.cancelDownloadModel(model = model)
        },
        colors =
          IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
          ),
        modifier = Modifier.semantics { contentDescription = cbStop },
      ) {
        Icon(
          Icons.Outlined.Close,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
  LaunchedEffect(downloadProgress) {
    animatedProgress.animateTo(downloadProgress, animationSpec = tween(150))
  }
}

/**
 * Modal bottom sheet that prompts the user to acknowledge the gated-model user agreement
 * by opening it in a custom tab. The host composable controls visibility and dismiss state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgreementAckSheet(
  show: Boolean,
  onDismiss: () -> Unit,
  onAgreementClicked: () -> Unit,
  agreementAckLauncher: ActivityResultLauncher<Intent>,
  sheetState: SheetState,
  model: Model,
) {
  if (!show) return
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    modifier = Modifier.wrapContentHeight(),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(horizontal = 16.dp),
    ) {
      Text(
        stringResource(R.string.acknowledge_user_agreement),
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        stringResource(R.string.gated_model_agreement_message),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 16.dp),
      )
      Button(
        onClick = {
          // Get agreement url from model url.
          val index = model.url.indexOf("/resolve/")
          // Show it in a tab.
          if (index >= 0) {
            val agreementUrl = model.url.substring(0, index)

            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.setData(agreementUrl.toUri())
            agreementAckLauncher.launch(customTabsIntent.intent)
          }
          // Dismiss the sheet.
          onAgreementClicked()
        }
      ) {
        Text(stringResource(R.string.open_user_agreement))
      }
    }
  }
}
