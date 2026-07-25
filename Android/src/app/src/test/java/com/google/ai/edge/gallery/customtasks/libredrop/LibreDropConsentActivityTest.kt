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

import android.content.Intent
import com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.consent.ConsentIntents
import com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.consent.ConsentModalRegistry
import com.google.ai.edge.gallery.testkit.Strict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for the defensive paths of [LibreDropConsentActivity].
 *
 * The trampoline is launched by a foreground service in response to a peer's introduction frame, so
 * every input it receives is either machine-generated or stale by the time it arrives. The three
 * cases below are the ones that decide whether a user ever sees a *wrong* prompt:
 *
 *  - a launch with no connection id at all,
 *  - a launch whose id names a transfer that already terminated,
 *  - a launch for a connection that is no longer in the registry.
 *
 * In all three the activity must finish without rendering, because a consent card that does not
 * correspond to a live inbound connection is worse than no card: accepting it would submit a
 * decision into nothing while the user believes they authorised a transfer.
 *
 * The *happy* path (a live registry entry, Accept/Reject tapped) is deliberately not driven here.
 * It composes real Compose content, which needs an instrumentation host or a Compose test rule this
 * module does not carry; it is verified on-device instead. What is unit-testable is exactly the
 * logic above, and it is the part with the security consequence.
 */
@RunWith(RobolectricTestRunner::class)
// See ReceiveModePreferencesTest for why the emulated SDK is pinned below targetSdk.
@Config(sdk = [36])
@Category(Strict::class)
class LibreDropConsentActivityTest {

  @After
  fun clearModalRegistry() {
    ConsentModalRegistry.instance.snapshotIds().forEach {
      ConsentModalRegistry.instance.unregister(it)
    }
  }

  private fun launchIntent(connectionId: Long?): Intent =
    Intent(RuntimeEnvironment.getApplication(), LibreDropConsentActivity::class.java).apply {
      action = ConsentIntents.ACTION_SHOW_CONSENT
      if (connectionId != null) putExtra(ConsentIntents.EXTRA_CONNECTION_ID, connectionId)
    }

  @Test
  fun launchWithoutAConnectionIdFinishesWithoutRendering() {
    val controller = Robolectric.buildActivity(LibreDropConsentActivity::class.java, launchIntent(null))
    controller.create()
    assertTrue("a consent prompt with no target must not stay up", controller.get().isFinishing)
  }

  @Test
  fun launchWithTheSentinelIdFinishesWithoutRendering() {
    val controller =
      Robolectric.buildActivity(
        LibreDropConsentActivity::class.java,
        launchIntent(ConsentIntents.MISSING_CONNECTION_ID),
      )
    controller.create()
    assertTrue(controller.get().isFinishing)
  }

  @Test
  fun launchForATerminatedTransferFinishesWithoutRendering() {
    // Nothing was ever registered under this id — the shape of a transfer that ended between the
    // service's startActivity and this onCreate, or of a stale notification tap after a restart.
    val controller = Robolectric.buildActivity(LibreDropConsentActivity::class.java, launchIntent(4242L))
    controller.create()
    assertTrue(controller.get().isFinishing)
  }

  /**
   * A finished-without-rendering launch must not leave a dismiss callback behind. A leaked entry
   * would make a later `ConsentModalRegistry.dismiss` for a recycled id close an unrelated screen.
   */
  @Test
  fun aRejectedLaunchLeavesNoModalRegistration() {
    val before = ConsentModalRegistry.instance.snapshotIds().size
    Robolectric.buildActivity(LibreDropConsentActivity::class.java, launchIntent(4243L)).create()
    assertEquals(before, ConsentModalRegistry.instance.snapshotIds().size)
  }

  /** The modal registry is the indirection the coordinator uses; its contract must hold. */
  @Test
  fun modalRegistryDismissIsIdempotentAndRemovesTheEntry() {
    var dismissed = 0
    ConsentModalRegistry.instance.register(99L) { dismissed++ }
    ConsentModalRegistry.instance.dismiss(99L)
    ConsentModalRegistry.instance.dismiss(99L)
    assertEquals("a second dismiss must not re-invoke the callback", 1, dismissed)
    assertFalse(ConsentModalRegistry.instance.snapshotIds().contains(99L))
  }
}
