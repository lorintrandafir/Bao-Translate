package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.ConnectionState
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors

/**
 * Non-interactive sections of the conversation mode screen: pairing status, the empty state, and
 * the language-label helper. Kept apart from [ConversationModeScreenSections] — which owns the
 * sections that take user input — so neither file grows unbounded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionStateRow(
  connectionState: ConnectionState,
  isScanning: Boolean,
) {
  val label = when {
    isScanning -> stringResource(R.string.bao_translate_scanning)
    connectionState == ConnectionState.ADVERTISING -> stringResource(R.string.bao_translate_advertising)
    connectionState == ConnectionState.CONNECTING -> stringResource(R.string.bao_translate_connecting)
    connectionState == ConnectionState.CONNECTED -> stringResource(R.string.bao_translate_connected)
    else -> stringResource(R.string.bao_translate_ready_to_pair)
  }
  val showProgress = isScanning ||
    connectionState == ConnectionState.ADVERTISING ||
    connectionState == ConnectionState.CONNECTING

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(Dimensions.Spacing.small),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      if (showProgress) {
        CircularProgressIndicator(modifier = Modifier.size(Dimensions.Icon.medium), strokeWidth = Dimensions.Component.strokeWidth)
      } else {
        Box(
          modifier = Modifier
            .size(Dimensions.Indicator.medium)
            .clip(CircleShape)
            .background(
              if (connectionState == ConnectionState.CONNECTED) {
                MaterialTheme.customColors.successColor
              } else {
                MaterialTheme.colorScheme.outline
              }
            )
        )
      }
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
internal fun NoDevicesState() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(Dimensions.Spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      Icon(
        imageVector = Icons.Default.Search,
        contentDescription = null,
        modifier = Modifier.size(Dimensions.Icon.large),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.bao_translate_no_devices_found),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = stringResource(R.string.bao_translate_devices_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
internal fun participantLanguageDisplayName(language: String): String {
  val key = SupportedLanguages.keyForCode(language) ?: language
  val supportedLanguage = SupportedLanguages.ALL.firstOrNull { it.key == key }
  return supportedLanguage?.let { stringResource(it.displayNameRes) } ?: language.uppercase()
}
