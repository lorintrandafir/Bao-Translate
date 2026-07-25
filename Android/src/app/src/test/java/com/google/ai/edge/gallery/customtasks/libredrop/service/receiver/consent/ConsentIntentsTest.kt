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
package com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.consent

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Routing tests for [ConsentIntents.parsePayload].
 *
 * This function decides what a tap on the inbound-transfer notification means. Getting it wrong is
 * a consent failure, not a cosmetic one: an accept routed as a reject strands the sender, and —
 * far worse — a malformed or stale broadcast that parsed as ACCEPT would auto-approve a transfer
 * the user never saw. The `null` cases below are therefore the important ones.
 *
 * The KDoc on `ConsentIntents` says tests "assert routing semantics ... by going through the
 * pure-JVM extractor" with a fake intent. No such test existed; this is it.
 */
@Category(Strict::class)
class ConsentIntentsTest {

  /** Map-backed stand-in for a real `Intent`, exactly the surface [ConsentIntents] consumes. */
  private class FakeIntent(
    override val action: String?,
    private val longExtras: Map<String, Long> = emptyMap(),
  ) : ConsentIntents.IntentLike {
    override fun getLongExtra(name: String, default: Long): Long = longExtras[name] ?: default
  }

  private fun intent(action: String?, connectionId: Long? = null) =
    FakeIntent(
      action = action,
      longExtras = connectionId?.let { mapOf(ConsentIntents.EXTRA_CONNECTION_ID to it) } ?: emptyMap(),
    )

  @Test
  fun acceptActionParsesAsAccept() {
    val payload = ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_ACCEPT, 42L))
    assertEquals(ConsentIntents.Decision.ACCEPT, payload?.decision)
    assertEquals(42L, payload?.connectionId)
    assertTrue(payload?.accepted == true)
  }

  @Test
  fun rejectActionParsesAsReject() {
    val payload = ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_REJECT, 7L))
    assertEquals(ConsentIntents.Decision.REJECT, payload?.decision)
    assertEquals(7L, payload?.connectionId)
    assertFalse(payload?.accepted == true)
  }

  @Test
  fun missingConnectionIdIsDropped() {
    assertNull(
      "a consent broadcast without a target connection must never be actioned",
      ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_ACCEPT)),
    )
  }

  @Test
  fun sentinelConnectionIdIsDropped() {
    assertNull(
      ConsentIntents.parsePayload(
        intent(ConsentIntents.ACTION_ACCEPT, ConsentIntents.MISSING_CONNECTION_ID)
      )
    )
  }

  @Test
  fun nullActionIsDropped() {
    assertNull(ConsentIntents.parsePayload(intent(null, 1L)))
  }

  @Test
  fun unrelatedActionIsDropped() {
    assertNull(ConsentIntents.parsePayload(intent("android.intent.action.VIEW", 1L)))
    assertNull(ConsentIntents.parsePayload(intent("", 1L)))
  }

  /**
   * The cancel and show-consent actions are handled by different code paths (mid-transfer abort
   * and the trampoline launch respectively). Neither may be mistaken for a consent decision.
   */
  @Test
  fun cancelAndShowActionsAreNotConsentDecisions() {
    assertNull(ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_CANCEL_TRANSFER, 1L)))
    assertNull(ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_SHOW_CONSENT, 1L)))
  }

  /** A near-miss action string must not be accepted by prefix or substring matching. */
  @Test
  fun similarButNotIdenticalActionsAreDropped() {
    assertNull(ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_ACCEPT + "X", 1L)))
    assertNull(ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_ACCEPT.uppercase(), 1L)))
    assertNull(
      ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_ACCEPT.dropLast(1), 1L))
    )
  }

  /** Connection ids are a monotonic counter starting at 1; large values must survive intact. */
  @Test
  fun largeConnectionIdsRoundTrip() {
    val payload = ConsentIntents.parsePayload(intent(ConsentIntents.ACTION_ACCEPT, Long.MAX_VALUE))
    assertEquals(Long.MAX_VALUE, payload?.connectionId)
  }

  /** The accept and reject actions must be distinct strings, or every reject would accept. */
  @Test
  fun acceptAndRejectActionsAreDistinct() {
    assertFalse(ConsentIntents.ACTION_ACCEPT == ConsentIntents.ACTION_REJECT)
    assertFalse(ConsentIntents.ACTION_ACCEPT == ConsentIntents.ACTION_CANCEL_TRANSFER)
    assertFalse(ConsentIntents.ACTION_REJECT == ConsentIntents.ACTION_CANCEL_TRANSFER)
  }
}
