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
package com.google.ai.edge.gallery.customtasks.libredrop

import android.content.Context
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [ReceiveModePreferences] against a real Android `SharedPreferences` under Robolectric.
 *
 * This class decides whether the device advertises itself to nearby senders, so the default is a
 * privacy property rather than a convenience one: an installation that has never touched the
 * toggle must not be discoverable. The persistence round-trip matters too — the LibreDrop screen
 * re-issues `ReceiverForegroundService.start` on entry based on this flag, so a value that failed
 * to persist would silently stop the listener from coming back after a process death.
 *
 * Robolectric (rather than a hand-rolled fake) is deliberate: the thing worth testing is the
 * interaction with the platform's `SharedPreferences`, including that `PREFS_NAME` is a dedicated
 * file, and a fake would assert nothing about that.
 */
@RunWith(RobolectricTestRunner::class)
// The project targets SDK 37; Robolectric 4.16.1 ships emulated frameworks up to 36 and refuses to
// configure a test whose targetSdkVersion exceeds that. Pinning the emulated level here is the
// documented remedy. SharedPreferences semantics are unchanged between 36 and 37, so nothing this
// test asserts depends on the difference. 4.17-beta-2 DOES accept SDK 37, but fails on both JDK 21
// and JDK 26 with "Failed to interact with raw FileDescriptor internals" — revisit at 4.17 stable.
@Config(sdk = [36])
@Category(Strict::class)
class ReceiveModePreferencesTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    // Robolectric's own accessor, so the suite does not need androidx.test:core on the classpath.
    context = RuntimeEnvironment.getApplication()
    clearStore()
  }

  @After
  fun tearDown() {
    clearStore()
  }

  private fun clearStore() {
    context
      .getSharedPreferences(ReceiveModePreferences.PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
  }

  @Test
  fun defaultsToOffSoAFreshInstallIsNotDiscoverable() {
    assertFalse(
      "receiving must be opt-in — a fresh install must never advertise itself",
      ReceiveModePreferences.from(context).isEnabled(),
    )
  }

  @Test
  fun enabledStateSurvivesANewInstance() {
    ReceiveModePreferences.from(context).setEnabled(true)
    // A separate instance reads the same backing file — this is the process-death path the
    // LibreDrop screen relies on when it re-asserts the user's choice on entry.
    assertTrue(ReceiveModePreferences.from(context).isEnabled())
  }

  @Test
  fun disablingPersistsAsWell() {
    val prefs = ReceiveModePreferences.from(context)
    prefs.setEnabled(true)
    prefs.setEnabled(false)
    assertFalse(ReceiveModePreferences.from(context).isEnabled())
  }

  @Test
  fun repeatedWritesOfTheSameValueAreStable() {
    val prefs = ReceiveModePreferences.from(context)
    repeat(5) { prefs.setEnabled(true) }
    assertTrue(prefs.isEnabled())
    repeat(5) { prefs.setEnabled(false) }
    assertFalse(prefs.isEnabled())
  }

  /**
   * The preference lives in its own file. If it shared the app's default preferences, a future
   * "clear settings" action elsewhere could silently switch the receiver on or off.
   */
  @Test
  fun usesADedicatedPreferencesFile() {
    ReceiveModePreferences.from(context).setEnabled(true)
    val dedicated =
      context.getSharedPreferences(ReceiveModePreferences.PREFS_NAME, Context.MODE_PRIVATE)
    assertTrue(dedicated.contains(ReceiveModePreferences.KEY_ENABLED))

    val defaultPrefs = context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
    assertFalse(defaultPrefs.contains(ReceiveModePreferences.KEY_ENABLED))
  }
}
