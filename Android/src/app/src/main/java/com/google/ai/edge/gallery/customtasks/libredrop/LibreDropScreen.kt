package com.google.ai.edge.gallery.customtasks.libredrop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.libredrop.discovery.DiscoveredService
import com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.ReceiverForegroundService
import com.google.ai.edge.gallery.ui.theme.Dimensions

@Composable
fun LibreDropScreen(data: CustomTaskData) {
  val senderViewModel: LibreDropSenderViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
  val peers by senderViewModel.peers.collectAsState()
  val isDiscovering by senderViewModel.isDiscovering.collectAsState()
  val transfers by senderViewModel.transfers.collectAsState()
  val selectedFiles = remember { mutableStateListOf<SelectedFile>() }
  var selectedPeer by remember { mutableStateOf<DiscoveredService?>(null) }
  val context = androidx.compose.ui.platform.LocalContext.current

  val receivePreferences = remember(context) { ReceiveModePreferences.from(context) }
  var isReceiving by remember { mutableStateOf(receivePreferences.isEnabled()) }

  // OpenMultipleDocuments, not OpenDocument: the protocol announces a multi-file introduction in
  // a single connection, so staging files one modal at a time would be needless friction.
  val filePickerLauncher =
    androidx.activity.compose.rememberLauncherForActivityResult(
      androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
      for (uri in uris) {
        // Persist read access across process death — the picker's implicit grant dies with the
        // activity, and a large transfer can outlive it.
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
        if (selectedFiles.none { it.uri == uri }) {
          val meta = resolveFileMetadata(context, uri)
          selectedFiles.add(SelectedFile(uri = uri, name = meta.first, sizeBytes = meta.second))
        }
      }
    }

  val permissionLauncher =
    androidx.activity.compose.rememberLauncherForActivityResult(
      androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
      // The receiver degrades rather than fails on a denied optional permission (BLE discovery
      // gets slower, the consent heads-up may be suppressed), so start regardless and let the
      // service's own graceful-failure paths handle whatever was refused.
      ReceiverForegroundService.start(context)
      receivePreferences.setEnabled(true)
      isReceiving = true
    }

  // Re-assert the user's persisted choice on entry. startForegroundService is idempotent for an
  // already-running service, so this only has an effect when the platform killed the listener
  // without restarting it — the toggle then reflects reality instead of a stale "on".
  androidx.compose.runtime.LaunchedEffect(Unit) {
    if (receivePreferences.isEnabled()) {
      ReceiverForegroundService.start(context)
    }
  }

  // Discovery holds an NsdManager browse; leaving the screen must release it.
  androidx.compose.runtime.DisposableEffect(Unit) {
    onDispose { senderViewModel.stopDiscovery() }
  }

  val isSending = remember(transfers) {
    transfers.any { it.state == TransferState.CONNECTING || it.state == TransferState.TRANSFERRING }
  }

  Scaffold { padding ->
    Column(
      // The screen now stacks five cards; on a short display the transfer log would otherwise be
      // clipped with no way to reach it.
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(androidx.compose.foundation.rememberScrollState())
        .padding(Dimensions.Spacing.medium),
      verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
    ) {
      Text(
        text = stringResource(R.string.libre_drop),
        style = MaterialTheme.typography.headlineMedium,
      )

      Text(
        text = stringResource(R.string.libre_drop_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      ReceiveSection(
        isReceiving = isReceiving,
        onReceivingChanged = { wantsReceiving ->
          if (wantsReceiving) {
            permissionLauncher.launch(receiverRuntimePermissions())
          } else {
            ReceiverForegroundService.stop(context)
            receivePreferences.setEnabled(false)
            isReceiving = false
          }
        },
      )

      FilePickerSection(
        selectedFiles = selectedFiles,
        onPickFiles = { filePickerLauncher.launch(arrayOf("*/*")) },
        onFileRemoved = { file -> selectedFiles.remove(file) },
      )

      PeerDiscoverySection(
        peers = peers.map { it.toDisplayPeer() },
        isDiscovering = isDiscovering,
        onStartDiscovery = { senderViewModel.startDiscovery() },
        onStopDiscovery = { senderViewModel.stopDiscovery() },
        onPeerSelected = { displayName ->
          selectedPeer = peers.firstOrNull { it.toDisplayPeer().name == displayName }
        },
        selectedPeerName = selectedPeer?.toDisplayPeer()?.name,
      )

      val canSend = selectedFiles.isNotEmpty() && selectedPeer != null
      val sendDisabledReason = when {
        selectedFiles.isEmpty() && selectedPeer == null ->
          stringResource(R.string.libre_drop_send_disabled_both)
        selectedFiles.isEmpty() ->
          stringResource(R.string.libre_drop_send_disabled_no_files)
        selectedPeer == null ->
          stringResource(R.string.libre_drop_send_disabled_no_peer)
        else -> null
      }

      Button(
        onClick = {
          val target = selectedPeer ?: return@Button
          val fileSources = selectedFiles.mapIndexed { index, sf ->
            com.google.ai.edge.gallery.customtasks.libredrop.service.uploads.UriFileSource(
              context = context,
              uri = sf.uri,
              payloadId = (index + 1).toLong(),
            ).build()
          }
          senderViewModel.send(peer = target, files = fileSources)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = canSend && !isSending,
      ) {
        if (isSending) {
          CircularProgressIndicator(
            modifier = Modifier.size(Dimensions.Icon.medium),
            strokeWidth = Dimensions.Stroke.thin,
          )
          Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
        }
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.libre_drop_send))
        Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
        Text(
          if (canSend) stringResource(R.string.libre_drop_send_to, selectedPeer?.toDisplayPeer()?.name ?: "")
          else sendDisabledReason ?: stringResource(R.string.libre_drop_send_disabled_both)
        )
      }

      TransferStatusSection(transfers = transfers)
    }
  }
}

@Composable
private fun PeerDiscoverySection(
  peers: List<DiscoveredPeer>,
  isDiscovering: Boolean,
  onStartDiscovery: () -> Unit,
  onStopDiscovery: () -> Unit,
  onPeerSelected: (String) -> Unit = {},
  selectedPeerName: String? = null,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
  ) {
    Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.libre_drop_nearby_devices),
          style = MaterialTheme.typography.titleSmall,
        )
        Button(
          onClick = {
            if (isDiscovering) onStopDiscovery() else onStartDiscovery()
          },
        ) {
          if (isDiscovering) {
            CircularProgressIndicator(
              modifier = Modifier.size(Dimensions.Icon.small),
              strokeWidth = Dimensions.Stroke.thin,
            )
            Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
            Text(stringResource(R.string.libre_drop_scanning))
          } else {
            Icon(Icons.Filled.Devices, contentDescription = null)
            Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
            Text(stringResource(R.string.libre_drop_scan))
          }
        }
      }
      Spacer(modifier = Modifier.height(Dimensions.Spacing.small))
      if (peers.isEmpty() && isDiscovering) {
        Text(
          text = stringResource(R.string.libre_drop_searching),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Dimensions.Spacing.small))
        repeat(3) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimensions.Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier
                .size(Dimensions.Icon.medium)
                .clip(RoundedCornerShape(Dimensions.Spacing.xs))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(modifier = Modifier.width(Dimensions.Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
              Box(
                modifier = Modifier
                  .fillMaxWidth(0.6f)
                  .height(14.dp)
                  .clip(RoundedCornerShape(Dimensions.Spacing.xs))
                  .background(MaterialTheme.colorScheme.surfaceContainerHighest),
              )
              Spacer(modifier = Modifier.height(Dimensions.Spacing.xs))
              Box(
                modifier = Modifier
                  .fillMaxWidth(0.4f)
                  .height(10.dp)
                  .clip(RoundedCornerShape(Dimensions.Spacing.xs))
                  .background(MaterialTheme.colorScheme.surfaceContainerHighest),
              )
            }
          }
        }
      } else if (peers.isEmpty()) {
        Text(
          text = stringResource(R.string.libre_drop_no_devices_found),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        // A plain Column, not a LazyColumn: this card now sits inside a verticalScroll parent,
        // and a lazy list of the same orientation there is given unbounded height constraints and
        // throws. Nearby-peer lists are a handful of entries, so laziness buys nothing anyway.
        Column {
          peers.forEach { peer ->
            PeerListItem(
              peer = peer,
              isSelected = peer.name == selectedPeerName,
              onClick = { onPeerSelected(peer.name) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PeerListItem(
  peer: DiscoveredPeer,
  isSelected: Boolean = false,
  onClick: () -> Unit = {},
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = Dimensions.Spacing.small)
      .semantics {
        selected = isSelected
        role = Role.Button
      }
      .clickable(onClick = onClick),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Filled.Devices,
      contentDescription = null,
      modifier = Modifier.size(Dimensions.Icon.medium),
      tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(Dimensions.Spacing.md))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = peer.name,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = peer.endpointId,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (isSelected) {
      Icon(
        Icons.Filled.CheckCircle,
        contentDescription = stringResource(R.string.libre_drop_selected_peer),
        modifier = Modifier.size(Dimensions.Icon.small),
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}


