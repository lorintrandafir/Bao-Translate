/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.Dimensions

/**
 * "Receive" card: the switch that brings the LibreDrop inbound listener up and down.
 *
 * Before this existed, `ReceiverForegroundService` was unreachable — it was neither declared in
 * the manifest nor started from anywhere, so the entire inbound half of the feature was dead code.
 *
 * The switch is the only entry point to that service, and it is deliberately opt-in: being
 * discoverable to nearby senders is a privacy decision, so nothing turns it on implicitly.
 */
@Composable
internal fun ReceiveSection(
  isReceiving: Boolean,
  onReceivingChanged: (Boolean) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
      ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(Dimensions.Spacing.medium),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.libre_drop_receive_title),
          style = MaterialTheme.typography.titleSmall,
        )
        Text(
          text =
            stringResource(
              if (isReceiving) {
                R.string.libre_drop_receive_on_subtitle
              } else {
                R.string.libre_drop_receive_off_subtitle
              }
            ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          // The subtitle is the only visible confirmation that the listener came up, so screen
          // readers must hear it change rather than having to re-traverse the card.
          modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
      }
      Switch(
        checked = isReceiving,
        onCheckedChange = onReceivingChanged,
        modifier = Modifier.semantics { },
      )
    }
  }
}

/**
 * Runtime permissions the inbound listener needs, filtered to those that actually exist on the
 * running platform version.
 *
 * - `POST_NOTIFICATIONS` (API 33+): without it the foreground-service notification is suppressed
 *   and, more importantly, so is the inbound-consent heads-up — the user would never see the
 *   prompt and every transfer would time out.
 * - `NEARBY_WIFI_DEVICES` (API 33+): required for the Wi-Fi mediums the receiver advertises on.
 * - `BLUETOOTH_ADVERTISE` / `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (API 31+): the BLE pulse
 *   advertiser and scanner degrade gracefully without these, but discovery is materially slower.
 *
 * `minSdk` for this app is 31, so the Bluetooth trio is always present in the returned list.
 */
internal fun receiverRuntimePermissions(): Array<String> {
  val permissions = mutableListOf<String>()
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    permissions += Manifest.permission.POST_NOTIFICATIONS
    permissions += Manifest.permission.NEARBY_WIFI_DEVICES
  }
  permissions += Manifest.permission.BLUETOOTH_ADVERTISE
  permissions += Manifest.permission.BLUETOOTH_SCAN
  permissions += Manifest.permission.BLUETOOTH_CONNECT
  return permissions.toTypedArray()
}
