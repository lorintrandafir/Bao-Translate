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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.BluetoothTransport
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfile
import com.google.ai.edge.gallery.ui.theme.Dimensions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaoTranslateSettingsSheet(
  onDismiss: () -> Unit,
  voiceProfile: VoiceProfile?,
  currentAudioDevice: AudioDevice,
  preferredInputDevice: AudioDevice.BluetoothHeadset?,
  sttModel: String,
  translationModel: String,
  wifiOnlyDownloads: Boolean,
  autoAcceptDetectedLanguage: Boolean,
  voiceProfiles: List<VoiceProfile>,
  activeVoiceProfileId: String,
  storageBreakdown: Map<String, Long>,
  modelStatuses: Map<String, ModelStatus>,
  onSttModelChange: (String) -> Unit,
  onTranslationModelChange: (String) -> Unit,
  onWifiOnlyChange: (Boolean) -> Unit,
  onAutoAcceptDetectedLanguageChange: (Boolean) -> Unit,
  onSwitchVoiceProfile: (String) -> Unit,
  onReRecordVoice: () -> Unit,
  onDeleteVoiceProfile: (String) -> Unit,
  onDeleteModels: () -> Unit,
  onDownloadModel: (String) -> Unit,
  onDeleteModel: (String) -> Unit,
  onOpenAudioPicker: () -> Unit,
  isTablet: Boolean = false,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val focusRequester = remember { FocusRequester() }
  val totalStorageMb = storageBreakdown.values.sum().toFloat() / (1024f * 1024f)
  val maxWidth = if (isTablet) Dimensions.Component.maxContentWidthTablet else Dimensions.Component.maxContentWidth

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    modifier = Modifier.focusRequester(focusRequester),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = maxWidth)
        .padding(Dimensions.Spacing.large)
        .verticalScroll(rememberScrollState()),
    ) {
      Text(
        text = stringResource(R.string.bao_translate_settings),
        style = if (isTablet) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.large))

      SectionHeader(stringResource(R.string.bao_translate_settings_downloads))

      ModelDownloadSection(
        modelStatuses = modelStatuses,
        storageBreakdown = storageBreakdown,
        onDownloadModel = onDownloadModel,
        onDeleteModel = onDeleteModel,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
      ) {
        Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.bao_translate_wifi_only),
              style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
              checked = wifiOnlyDownloads,
              onCheckedChange = onWifiOnlyChange,
            )
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = Dimensions.Spacing.small))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.bao_translate_storage_format, totalStorageMb),
              style = MaterialTheme.typography.bodyMedium,
            )
            // Disabled while any model is downloading/extracting: a recursive delete racing an
            // in-flight download to the same dirs can leave a partial file or a deleted model
            // re-marked Ready by the still-running download's completion write.
            val deleteBusy = modelStatuses.values.any {
              it is ModelStatus.Downloading || it is ModelStatus.Extracting
            }
            TextButton(onClick = onDeleteModels, enabled = !deleteBusy) {
              Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.cd_delete_icon),
                modifier = Modifier.height(Dimensions.Spacing.medium),
              )
              Spacer(Modifier.width(Dimensions.Spacing.xs))
              Text(stringResource(R.string.bao_translate_delete_all_models))
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      SectionHeader(stringResource(R.string.bao_translate_settings_language))

      val autoAcceptSwitchCd = stringResource(R.string.cd_bao_translate_auto_accept_switch)
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
      ) {
        Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.bao_translate_auto_accept_detected_language),
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = stringResource(R.string.bao_translate_auto_accept_detected_language_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = autoAcceptDetectedLanguage,
              onCheckedChange = onAutoAcceptDetectedLanguageChange,
              modifier = Modifier.semantics { contentDescription = autoAcceptSwitchCd },
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      SectionHeader(stringResource(R.string.bao_translate_settings_models))

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
      ) {
        Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
          SettingsRadioGroup(
            label = stringResource(R.string.bao_translate_stt_model),
            options = listOf(
              stringResource(R.string.bao_translate_stt_model_option_whisper) to "whisper_base",
            ),
            selectedOption = sttModel,
            onOptionSelected = onSttModelChange,
          )

          HorizontalDivider(modifier = Modifier.padding(vertical = Dimensions.Spacing.small))

          SettingsRadioGroup(
            label = stringResource(R.string.bao_translate_translation_model),
            options = listOf(
              stringResource(R.string.bao_translate_translation_model_option_qwen25) to "qwen25_1b",
              stringResource(R.string.bao_translate_translation_model_option_gemma4_e2b) to "gemma4_e2b",
            ),
            selectedOption = translationModel,
            onOptionSelected = onTranslationModelChange,
            enabledOptions = buildSet {
              add("qwen25_1b")
              if (modelStatuses["gemma4_e2b"] == ModelStatus.Ready) add("gemma4_e2b")
            },
            optionDescriptions = if (modelStatuses["gemma4_e2b"] == ModelStatus.Ready) {
              emptyMap()
            } else {
              mapOf("gemma4_e2b" to stringResource(R.string.bao_translate_model_download_first))
            },
          )
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      SectionHeader(stringResource(R.string.bao_translate_settings_voice))

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
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
          ) {
            val settingsEnrollVoiceContentDescription =
              stringResource(R.string.cd_bao_translate_settings_enroll_voice)
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xxs)) {
              Text(
                text = stringResource(R.string.bao_translate_your_voice_profile),
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = if (voiceProfiles.isNotEmpty()) {
                  stringResource(R.string.bao_translate_voice_profile_saved)
                } else {
                  stringResource(R.string.bao_translate_voice_profile_empty)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            if (voiceProfiles.isNotEmpty()) {
              Text(
                text = stringResource(R.string.bao_translate_voice_profiles_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
              )
              voiceProfiles.forEach { profile ->
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = stringResource(
                        R.string.bao_translate_voice_profile_chip_format,
                        profile.name,
                        profile.durationSec,
                      ),
                      style = MaterialTheme.typography.bodyMedium,
                    )
                    if (profile.id == activeVoiceProfileId) {
                      Text(
                        text = stringResource(R.string.bao_translate_voice_profile_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                      )
                    }
                  }
                  Row {
                    if (profile.id != activeVoiceProfileId) {
                      TextButton(onClick = { onSwitchVoiceProfile(profile.id) }) {
                        Text(stringResource(R.string.bao_translate_switch_voice_profile))
                      }
                    }
                    TextButton(onClick = { onDeleteVoiceProfile(profile.id) }) {
                      Text(stringResource(R.string.bao_translate_unenroll_voice))
                    }
                  }
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End,
            ) {
              TextButton(
                onClick = onReRecordVoice,
                modifier = Modifier.semantics {
                  if (voiceProfiles.isEmpty()) {
                    contentDescription = settingsEnrollVoiceContentDescription
                  }
                },
              ) {
                Text(
                  if (voiceProfiles.isNotEmpty()) {
                    stringResource(R.string.bao_translate_re_record_voice)
                  } else {
                    stringResource(R.string.bao_translate_enroll_voice)
                  },
                )
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))

      SectionHeader(stringResource(R.string.bao_translate_settings_audio))

      AudioDeviceSection(
        currentDevice = currentAudioDevice,
        preferredInputDevice = preferredInputDevice,
        onOpenAudioPicker = onOpenAudioPicker,
      )

      Spacer(modifier = Modifier.height(Dimensions.Spacing.xl))
    }
  }
}
