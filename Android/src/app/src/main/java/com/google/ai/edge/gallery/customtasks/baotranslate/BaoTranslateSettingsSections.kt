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
package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.BluetoothTransport
import com.google.ai.edge.gallery.ui.theme.Dimensions

@Composable
internal fun ModelDownloadSection(
  modelStatuses: Map<String, ModelStatus>,
  storageBreakdown: Map<String, Long>,
  onDownloadModel: (String) -> Unit,
  onDeleteModel: (String) -> Unit,
) {
  val allModels = BaoTranslateModelManager.ALL_MODELS
  val requiredModels = BaoTranslateModelManager.REQUIRED_MODEL_IDS.mapNotNull { id ->
    allModels.firstOrNull { it.id == id }
  }
  val optionalModels = allModels.filter { it.id !in BaoTranslateModelManager.REQUIRED_MODEL_IDS }
  val anyBusy = modelStatuses.values.any { it is ModelStatus.Downloading || it is ModelStatus.Extracting }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
  ) {
    Column(
      modifier = Modifier.padding(Dimensions.Spacing.medium),
      verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      Text(
        text = stringResource(R.string.bao_translate_required_models),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      requiredModels.forEach { model ->
        val status = modelStatuses[model.id] ?: ModelStatus.NotDownloaded
        val usedBytes = storageBreakdown[model.id] ?: 0L
        val usedMb = usedBytes.toFloat() / (1024f * 1024f)

        ModelDownloadCard(
          modelInfo = model,
          status = status,
          usedMb = usedMb,
          onDownload = { onDownloadModel(model.id) },
          onDelete = { onDeleteModel(model.id) },
          downloadDisabled = anyBusy,
        )
      }

      if (optionalModels.isNotEmpty()) {
        HorizontalDivider()
        Text(
          text = stringResource(R.string.bao_translate_optional_models),
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      optionalModels.forEach { model ->
        val status = modelStatuses[model.id] ?: ModelStatus.NotDownloaded
        val usedBytes = storageBreakdown[model.id] ?: 0L
        val usedMb = usedBytes.toFloat() / (1024f * 1024f)

        ModelDownloadCard(
          modelInfo = model,
          status = status,
          usedMb = usedMb,
          onDownload = { onDownloadModel(model.id) },
          onDelete = { onDeleteModel(model.id) },
          downloadDisabled = anyBusy,
        )
      }
    }
  }
}

@Composable
internal fun ModelDownloadCard(
  modelInfo: ModelInfo,
  status: ModelStatus,
  usedMb: Float,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
  downloadDisabled: Boolean,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = stringResource(modelInfo.displayNameRes),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
      )

      when (status) {
        is ModelStatus.Downloading -> {
          Text(
            text = "${(status.progress * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is ModelStatus.Extracting -> {
          Text(
            text = stringResource(R.string.bao_translate_extracting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is ModelStatus.Ready -> {
          Text(
            text = "%.1f MB".format(usedMb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is ModelStatus.NotDownloaded -> {
          Text(
            text = stringResource(R.string.model_not_downloaded_msg),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is ModelStatus.Error -> {
          Text(
            text = stringResource(R.string.notification_content_fail, ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    }

    when (status) {
      is ModelStatus.NotDownloaded, is ModelStatus.Error -> {
        TextButton(onClick = onDownload, enabled = !downloadDisabled) {
          Text(stringResource(R.string.bao_translate_download))
        }
      }
      is ModelStatus.Ready -> {
        TextButton(onClick = onDelete, enabled = !downloadDisabled) {
          Text(stringResource(R.string.bao_translate_delete))
        }
      }
      is ModelStatus.Downloading, is ModelStatus.Extracting -> {
        CircularProgressIndicator(
          modifier = Modifier.size(Dimensions.Icon.medium),
          strokeWidth = Dimensions.Stroke.thin,
        )
      }
    }
  }
}

@Composable
internal fun AudioDeviceSection(
  currentDevice: AudioDevice,
  preferredInputDevice: AudioDevice.BluetoothHeadset?,
  onOpenAudioPicker: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
  ) {
    Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
      Text(
        text = stringResource(R.string.bao_translate_settings_audio),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

      Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs)) {
        AudioRouteSummaryRow(
          label = stringResource(R.string.bao_translate_audio_output_label),
          value = audioDeviceName(currentDevice),
        )
        AudioRouteSummaryRow(
          label = stringResource(R.string.bao_translate_audio_input_label),
          value = preferredInputDevice?.let { audioDeviceName(it) }
            ?: stringResource(R.string.bao_translate_audio_input_default_short),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(onClick = onOpenAudioPicker) {
            Text(stringResource(R.string.bao_translate_change_audio_devices))
          }
        }
      }
    }
  }
}

@Composable
internal fun AudioRouteSummaryRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
internal fun audioDeviceName(device: AudioDevice): String = when (device) {
  is AudioDevice.BluetoothHeadset -> "${device.name} ${transportShortLabel(device.transport)}"
  is AudioDevice.WiredHeadset -> device.name
  AudioDevice.Speaker -> stringResource(R.string.bao_translate_phone_speaker)
}

@Composable
internal fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = Dimensions.Spacing.small),
  )
}

@Composable
internal fun SettingsRadioGroup(
  label: String,
  options: List<Pair<String, String>>,
  selectedOption: String,
  onOptionSelected: (String) -> Unit,
  enabledOptions: Set<String> = options.map { it.second }.toSet(),
  optionDescriptions: Map<String, String> = emptyMap(),
) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Medium,
    )

    Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

    Column(modifier = Modifier.selectableGroup()) {
      options.forEach { (displayName, value) ->
        val enabled = value in enabledOptions
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .selectable(
              selected = selectedOption == value,
              onClick = { onOptionSelected(value) },
              enabled = enabled,
              role = Role.RadioButton,
            )
            .padding(vertical = Dimensions.Spacing.xs),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = selectedOption == value,
            onClick = null,
            enabled = enabled,
          )
          Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          Column {
            Text(
              text = displayName,
              style = MaterialTheme.typography.bodyMedium,
              color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            optionDescriptions[value]?.let { description ->
              Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}