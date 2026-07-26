/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persisted "receiving is on" preference for the LibreDrop inbound listener.
 *
 * ### Why a preference rather than asking the service
 *
 * `ReceiverForegroundService` returns `START_STICKY`, so once the user opts in the platform keeps
 * the listener alive — and resurrects it after a low-memory kill — until something explicitly
 * stops it. There is therefore no useful "is the service object alive right now" question for the
 * UI to ask: the durable fact is whether the *user* wants to be discoverable. That is what the
 * toggle shows and what this class stores.
 *
 * The screen re-issues `ReceiverForegroundService.start` on entry whenever this flag is set.
 * `startForegroundService` is idempotent for an already-running service (`onStartCommand` no-ops
 * when a session exists), so a stale flag after an unusual kill self-heals the moment the user
 * opens the screen rather than silently showing "on" while nothing listens.
 *
 * Mirrors the shape of
 * [com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.AdvertisedDeviceNamePreferences]
 * so the two LibreDrop preference surfaces stay symmetric.
 */
class ReceiveModePreferences(private val prefs: SharedPreferences) {

  /** Whether the user has opted in to receiving. Defaults to `false` — opt-in, never opt-out. */
  fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

  /** Persist the user's choice. */
  fun setEnabled(enabled: Boolean) {
    prefs.edit { putBoolean(KEY_ENABLED, enabled) }
  }

  companion object {
    /** Dedicated preferences file so clearing receive mode cannot disturb other settings. */
    const val PREFS_NAME: String = "bada.receive_mode"

    internal const val KEY_ENABLED: String = "enabled"

    @JvmStatic
    fun from(context: Context): ReceiveModePreferences =
      ReceiveModePreferences(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      )
  }
}
