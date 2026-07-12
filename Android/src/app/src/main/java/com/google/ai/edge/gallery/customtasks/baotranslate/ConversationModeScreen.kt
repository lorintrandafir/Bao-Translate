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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.ConnectionState
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.DiscoveredPeer
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationModeScreen(
  localParticipant: Participant?,
  remoteParticipants: List<Participant>,
  discoveredPeers: List<DiscoveredPeer>,
  isScanning: Boolean,
  connectionState: ConnectionState,
  connectingPeers: Set<String>,
  currentAudioDevice: AudioDevice,
  onScanDevices: () -> Unit,
  onStopScan: () -> Unit,
  onConnectDevice: (String) -> Unit,
  onDisconnectDevice: (String) -> Unit,
  onStartConversation: () -> Unit,
  modifier: Modifier = Modifier,
  isTablet: Boolean = false,
) {
  val maxWidth = if (isTablet) Dimensions.Component.maxContentWidthTablet else Dimensions.Component.maxContentWidth

  Column(
    modifier = modifier
      .fillMaxSize()
      .widthIn(max = maxWidth)
      .verticalScroll(rememberScrollState())
      .padding(Dimensions.Spacing.medium),
    verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
  ) {
    Text(
      text = stringResource(R.string.bao_translate_conversation_mode),
      style = if (isTablet) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
    )

    Text(
      text = stringResource(R.string.bao_translate_connect_subtitle),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ConnectionStateRow(connectionState = connectionState, isScanning = isScanning)

    localParticipant?.let { participant ->
      ParticipantCard(
        participant = participant,
        isLocal = true,
        audioDevice = currentAudioDevice,
      )
    }

    if (remoteParticipants.isNotEmpty()) {
      Text(
        text = stringResource(R.string.bao_translate_connected),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      remoteParticipants.forEach { participant ->
        ParticipantCard(
          participant = participant,
          isLocal = false,
          onDisconnect = { onDisconnectDevice(participant.id) },
        )
      }
    }

    // Peers that connected are shown in the Connected section; exclude them here so they don't
    // appear twice. (Filtering at render avoids a reconnection trap from mutating manager state,
    // since the library may not re-emit onPeerFound after a disconnect.)
    val unconnectedPeers = discoveredPeers.filter { peer -> remoteParticipants.none { it.id == peer.id } }
    if (unconnectedPeers.isNotEmpty()) {
      Text(
        text = stringResource(R.string.bao_translate_discovered_devices_section),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Column(verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small)) {
        unconnectedPeers.forEach { peer ->
          val isConnecting = connectingPeers.contains(peer.id)
          DiscoveredPeerCard(
            peer = peer,
            isConnected = false,
            isConnecting = isConnecting,
            onConnect = { onConnectDevice(peer.deviceAddress) },
          )
        }
      }
    }

    ScanSection(
      isScanning = isScanning,
      connectionState = connectionState,
      discoveredCount = discoveredPeers.size,
      onScanDevices = onScanDevices,
      onStopScan = onStopScan,
    )

    if (!isScanning && discoveredPeers.isEmpty() && remoteParticipants.isEmpty()) {
      NoDevicesState()
    }

    val connectedCount = remoteParticipants.count { it.isConnected }
    Button(
      onClick = onStartConversation,
      modifier = Modifier.fillMaxWidth(),
      enabled = connectedCount > 0,
    ) {
      Icon(
        imageVector = Icons.Default.Bluetooth,
        contentDescription = null,
      )
      Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
      Text(
        text = if (connectedCount > 0) {
          stringResource(R.string.bao_translate_group_conversation_format, connectedCount)
        } else {
          stringResource(R.string.bao_translate_connect_devices_first)
        }
      )
    }
  }
}

