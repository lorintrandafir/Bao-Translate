package com.google.ai.edge.gallery.customtasks.baotranslate

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.ConnectionState
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.DiscoveredPeer
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors

@Composable
internal fun DiscoveredPeerCard(
  peer: DiscoveredPeer,
  isConnected: Boolean,
  isConnecting: Boolean,
  onConnect: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(Dimensions.Spacing.medium),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
    ) {
      Box(
        modifier = Modifier
          .size(Dimensions.Icon.xl)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Default.Person,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = peer.name,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = peer.deviceAddress,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (isConnected) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = stringResource(R.string.cd_bao_translate_connected_icon),
          tint = MaterialTheme.customColors.successColor,
          modifier = Modifier.size(Dimensions.Icon.medium),
        )
      } else {
        OutlinedButton(onClick = onConnect, enabled = !isConnecting) {
          if (isConnecting) {
            CircularProgressIndicator(modifier = Modifier.size(Dimensions.Icon.small), strokeWidth = Dimensions.Component.strokeWidth)
            Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          }
          Text(
            if (isConnecting) {
              stringResource(R.string.bao_translate_connecting)
            } else {
              stringResource(R.string.bao_translate_connect_action)
            }
          )
        }
      }
    }
  }
}

@Composable
internal fun ParticipantCard(
  participant: Participant,
  isLocal: Boolean,
  modifier: Modifier = Modifier,
  audioDevice: AudioDevice? = null,
  onDisconnect: (() -> Unit)? = null,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (isLocal) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
      },
    ),
  ) {
    Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier
            .size(Dimensions.Icon.xl)
            .clip(CircleShape)
            .background(
              if (isLocal) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.surfaceVariant
              }
            ),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = if (isLocal) {
              MaterialTheme.colorScheme.onPrimary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        }

        Spacer(modifier = Modifier.width(Dimensions.Spacing.small))

        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = if (isLocal) {
                stringResource(R.string.bao_translate_you_suffix, participant.name)
              } else {
                participant.name
              },
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
            )
            if (isLocal) {
              Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
              Box(
                modifier = Modifier
                  .size(Dimensions.Spacing.small)
                  .clip(CircleShape)
                  .background(MaterialTheme.customColors.successColor)
              )
            } else if (participant.isConnected) {
              Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.cd_bao_translate_connected_icon),
                tint = MaterialTheme.customColors.successColor,
                modifier = Modifier.size(Dimensions.Icon.small),
              )
            }
          }

          Text(
            text = stringResource(
              R.string.bao_translate_lang_pair_format,
              participantLanguageDisplayName(participant.sourceLanguage),
              participantLanguageDisplayName(participant.targetLanguage),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        // Leave-the-conversation control for a connected peer (the only way to drop a peer from the
        // UI; the BLE manager supported it but nothing called it before).
        if (!isLocal && onDisconnect != null) {
          OutlinedButton(onClick = onDisconnect) {
            Text(stringResource(R.string.bao_translate_disconnect))
          }
        }
      }

      Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        if (isLocal && audioDevice != null) {
          val deviceName = when (audioDevice) {
            is AudioDevice.BluetoothHeadset -> audioDevice.name
            is AudioDevice.WiredHeadset -> audioDevice.name
            AudioDevice.Speaker -> stringResource(R.string.bao_translate_phone_speaker)
          }
          val routeIcon = when (audioDevice) {
            is AudioDevice.BluetoothHeadset -> Icons.Default.BluetoothConnected
            is AudioDevice.WiredHeadset -> Icons.AutoMirrored.Filled.VolumeUp
            AudioDevice.Speaker -> Icons.AutoMirrored.Filled.VolumeUp
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = routeIcon,
              contentDescription = null,
              modifier = Modifier.size(Dimensions.Icon.small),
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(Dimensions.Spacing.xs))
            Text(
              text = deviceName,
              style = MaterialTheme.typography.labelSmall,
            )
          }
        }

        if (participant.hasVoiceProfile) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              modifier = Modifier.size(Dimensions.Icon.small),
              tint = MaterialTheme.customColors.successColor,
            )
            Spacer(modifier = Modifier.width(Dimensions.Spacing.xs))
            Text(
              text = stringResource(R.string.bao_translate_voice_enrolled),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.customColors.successColor,
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun ScanSection(
  isScanning: Boolean,
  connectionState: ConnectionState,
  discoveredCount: Int,
  onScanDevices: () -> Unit,
  onStopScan: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
  ) {
    Column(
      modifier = Modifier.padding(Dimensions.Spacing.medium),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (isScanning) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(modifier = Modifier.size(Dimensions.Icon.medium))
          Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          Text(
            text = stringResource(R.string.bao_translate_scanning),
            style = MaterialTheme.typography.bodyMedium,
          )
          if (discoveredCount > 0) {
            Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
            Text(
              text = pluralStringResource(
                R.plurals.bao_translate_devices_found_format,
                discoveredCount,
                discoveredCount,
              ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

        OutlinedButton(onClick = onStopScan) {
          Text(stringResource(R.string.bao_translate_stop_scanning))
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = stringResource(R.string.bao_translate_find_devices),
            tint = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          Text(
            text = if (discoveredCount > 0) {
              pluralStringResource(
                R.plurals.bao_translate_device_count_format,
                discoveredCount,
                discoveredCount,
              )
            } else {
              stringResource(R.string.bao_translate_find_devices)
            },
            style = MaterialTheme.typography.bodyMedium,
          )
        }

        Spacer(modifier = Modifier.height(Dimensions.Spacing.small))

        OutlinedButton(onClick = onScanDevices) {
          Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = stringResource(R.string.bao_translate_scan_devices),
          )
          Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
          Text(stringResource(R.string.bao_translate_scan_devices))
        }
      }
    }
  }
}
