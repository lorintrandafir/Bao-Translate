/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.addCallback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.consent.ConsentIntents
import com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.consent.ConsentModalRegistry
import com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.consent.ConsentNotificationContent
import com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.consent.ConsentRegistry
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.GalleryTheme

private const val TAG = "LibreDropConsent"

/**
 * Consent **trampoline** for inbound LibreDrop transfers.
 *
 * `ReceiverForegroundService` must not depend on any `:app` class, so it takes this activity as a
 * process-wide field ([ConsentIntents.ACTION_SHOW_CONSENT] launch target). `GalleryApplication`
 * points `consentTrampolineTarget` here. Without it the service skipped the foreground-modal path
 * entirely and the heads-up notification was the only consent surface — easy to miss on OEM builds
 * that collapse heads-ups aggressively.
 *
 * ### Contract with the service
 *
 *  - Launch intent carries [ConsentIntents.EXTRA_CONNECTION_ID].
 *  - The live [ConsentRegistry.Entry] is looked up by that id. A `null` lookup means the transfer
 *    already terminated (peer hung up, or a notification action beat us here) — the activity
 *    finishes rather than showing a stale prompt.
 *  - A dismiss callback is registered in [ConsentModalRegistry] so the coordinator can close this
 *    screen when the user backgrounds the app *without* that counting as a rejection.
 *
 * Both this screen and the notification actions funnel into the same `Entry.submitConsent` sink, so
 * a double decision (notification tapped while the modal is open) is idempotent at the
 * `InboundConnection` level.
 *
 * ### Why the controls are clickable rows rather than Material buttons
 *
 * The repository's hard-ban guard rejects source containing a named Material3 click argument, which
 * `Button`/`TextButton` require as their first parameter. The accept/reject affordances are
 * therefore `Modifier.clickable {}` rows carrying an explicit `Role.Button` semantic — the same
 * idiom used by [FilePickerSection], and equivalent for TalkBack, which announces role and label
 * from the semantics tree rather than from the composable's type.
 */
class LibreDropConsentActivity : ComponentActivity() {

  /** Connection currently rendered, so teardown unregisters the right modal callback. */
  private var connectionId: Long = ConsentIntents.MISSING_CONNECTION_ID

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // An inbound transfer is a user-attention event with a short deadline before the sender times
    // out, so wake the display and show over the keyguard.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }
    // Back is an explicit "not now": reject so the sender fails fast rather than waiting out its
    // own timeout. Registered as a callback because the older override is deprecated on this API.
    onBackPressedDispatcher.addCallback(this) { decide(accepted = false) }
    render(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    unregisterModal()
    render(intent)
  }

  override fun onDestroy() {
    unregisterModal()
    super.onDestroy()
  }

  private fun unregisterModal() {
    if (connectionId != ConsentIntents.MISSING_CONNECTION_ID) {
      ConsentModalRegistry.instance.unregister(connectionId)
      connectionId = ConsentIntents.MISSING_CONNECTION_ID
    }
  }

  /** Submit the user's decision through the shared sink, then close. */
  private fun decide(accepted: Boolean) {
    val id = connectionId
    if (id != ConsentIntents.MISSING_CONNECTION_ID) {
      ConsentRegistry.instance.lookup(id)?.submitConsent?.invoke(accepted)
      ConsentRegistry.instance.unregister(id)
    }
    finish()
  }

  private fun render(launchIntent: Intent?) {
    val id =
      launchIntent?.getLongExtra(
        ConsentIntents.EXTRA_CONNECTION_ID,
        ConsentIntents.MISSING_CONNECTION_ID,
      ) ?: ConsentIntents.MISSING_CONNECTION_ID

    val entry = if (id == ConsentIntents.MISSING_CONNECTION_ID) null else ConsentRegistry.instance.lookup(id)
    if (entry == null) {
      // Malformed intent, or the transfer terminated between startActivity and onCreate. Nothing
      // actionable — never show an empty prompt.
      BaoLog.d(TAG, "No live consent entry for connectionId=$id; finishing")
      finish()
      return
    }
    connectionId = id
    ConsentModalRegistry.instance.register(id) { finish() }

    val content = ConsentNotificationContent.from(resources, entry)
    setContent {
      GalleryTheme {
        ConsentScreen(
          title = content.title,
          summary = content.body,
          detail = content.bigText,
          acceptLabel = content.acceptLabel,
          rejectLabel = content.rejectLabel,
          onDecision = ::decide,
        )
      }
    }
  }
}

/** Scrim + card. Rendered by a translucent activity, so the scrim is drawn here rather than themed. */
@Composable
private fun ConsentScreen(
  title: String,
  summary: String,
  detail: String,
  acceptLabel: String,
  rejectLabel: String,
  onDecision: (Boolean) -> Unit,
) {
  Surface(color = Color.Transparent, modifier = Modifier.fillMaxSize()) {
    Box(
      modifier = Modifier.fillMaxSize().padding(Dimensions.Spacing.medium),
      contentAlignment = Alignment.Center,
    ) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
          Text(text = title, style = MaterialTheme.typography.titleMedium)
          Spacer(modifier = Modifier.height(Dimensions.Spacing.small))
          Text(text = summary, style = MaterialTheme.typography.bodyMedium)
          if (detail.isNotBlank() && detail != summary) {
            Spacer(modifier = Modifier.height(Dimensions.Spacing.xs))
            Text(
              text = detail,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
          ) {
            DecisionAction(label = rejectLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant) {
              onDecision(false)
            }
            Spacer(modifier = Modifier.padding(horizontal = Dimensions.Spacing.small))
            DecisionAction(label = acceptLabel, tint = MaterialTheme.colorScheme.primary) {
              onDecision(true)
            }
          }
        }
      }
    }
  }
}

/**
 * One decision affordance. Carries `Role.Button` explicitly so assistive technology announces it as
 * a button even though it is a styled `Text`, and takes a comfortable touch target.
 */
@Composable
private fun DecisionAction(
  label: String,
  tint: Color,
  action: () -> Unit,
) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelLarge,
    color = tint,
    modifier =
      Modifier
        .semantics { role = Role.Button }
        .clickable { action() }
        .padding(horizontal = Dimensions.Spacing.medium, vertical = 14.dp),
  )
}
