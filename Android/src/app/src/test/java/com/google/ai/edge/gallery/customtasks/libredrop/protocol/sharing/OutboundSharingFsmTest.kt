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
package com.google.ai.edge.gallery.customtasks.libredrop.protocol.sharing

import com.google.ai.edge.gallery.testkit.Strict
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Transition-table tests for [OutboundSharingFsm], the pure state machine the sender's I/O loop
 * interprets.
 *
 * The FSM is where "the peer said something unexpected" is supposed to become a clean
 * [SharingFsmEffect.ProtocolError] rather than an exception, a hang, or — worst — a silent
 * advance into payload streaming. Because it is pure, every one of those paths is cheap to pin
 * here, and pinning them is what stops a malformed or hostile peer from steering the sender
 * off its intended sequence.
 *
 * Covered: the happy path through to `SendingPayloads`, out-of-order frames at each state, the
 * receiver-only `UserConsent` event being rejected, local and peer cancellation from every state,
 * reject handling, and terminal-state absorption.
 */
@Category(Strict::class)
class OutboundSharingFsmTest {

  private fun deterministicRandom(): SecureRandom =
    SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1234L) }

  private fun fsm(): OutboundSharingFsm =
    OutboundSharingFsm(
      introduction = IntroductionFrame.getDefaultInstance(),
      secureRandom = deterministicRandom(),
    )

  private fun frame(f: SharingFrame) = SharingFsmEvent.FrameReceived(f)

  private fun pairedKeyEncryption() = frame(SharingFrames.pairedKeyEncryption(secureRandom = deterministicRandom()))

  private fun pairedKeyResult() = frame(SharingFrames.pairedKeyResult(PairedKeyResultStatus.UNABLE))

  private fun connectionResponse(status: ConnectionResponseStatus) =
    frame(SharingFrames.connectionResponse(status))

  /** Drive the FSM to the state just before the peer's accept/reject decision. */
  private fun fsmAwaitingResponse(): OutboundSharingFsm =
    fsm().apply {
      start()
      onEvent(pairedKeyEncryption())
      onEvent(pairedKeyResult())
    }

  @Test
  fun startEmitsPairedKeyEncryptionExactlyOnce() {
    val machine = fsm()
    val first = machine.start()
    assertEquals(1, first.size)
    assertTrue(first.single() is SharingFsmEffect.SendFrame)
    assertEquals(OutboundSharingState.SentPairedKeyEncryption, machine.state)

    assertTrue("start() must be idempotent", machine.start().isEmpty())
  }

  @Test
  fun happyPathReachesSendingPayloads() {
    val machine = fsm()
    machine.start()

    machine.onEvent(pairedKeyEncryption())
    assertEquals(OutboundSharingState.SentPairedKeyResult, machine.state)

    machine.onEvent(pairedKeyResult())
    assertEquals(OutboundSharingState.SentIntroduction, machine.state)

    val effects = machine.onEvent(connectionResponse(ConnectionResponseStatus.ACCEPT))
    assertEquals(OutboundSharingState.SendingPayloads, machine.state)
    assertTrue(
      "accept must unlock payload streaming",
      effects.any { it is SharingFsmEffect.ReadyToSendPayloads },
    )
  }

  @Test
  fun peerRejectTerminatesWithRejectedEffect() {
    val machine = fsmAwaitingResponse()
    val effects = machine.onEvent(connectionResponse(ConnectionResponseStatus.REJECT))
    assertTrue(effects.any { it is SharingFsmEffect.Rejected })
    assertEquals(OutboundSharingState.Disconnected, machine.state)
  }

  /** The sender FSM must never accept a consent decision — that event belongs to the receiver. */
  @Test
  fun userConsentIsRejectedAsAProtocolError() {
    val machine = fsm()
    machine.start()
    val effects = machine.onEvent(SharingFsmEvent.UserConsent(accept = true))
    assertTrue(
      "UserConsent on the sender must be a protocol error, got $effects",
      effects.any { it is SharingFsmEffect.ProtocolError },
    )
  }

  @Test
  fun outOfOrderFrameInEachStateIsAProtocolError() {
    // Introduction arriving where PAIRED_KEY_ENCRYPTION is expected.
    val atEncryption = fsm().apply { start() }
    assertTrue(
      atEncryption
        .onEvent(frame(SharingFrames.introduction(IntroductionFrame.getDefaultInstance())))
        .any { it is SharingFsmEffect.ProtocolError }
    )

    // PAIRED_KEY_ENCRYPTION repeated where PAIRED_KEY_RESULT is expected.
    val atResult = fsm().apply { start(); onEvent(pairedKeyEncryption()) }
    assertTrue(
      atResult.onEvent(pairedKeyEncryption()).any { it is SharingFsmEffect.ProtocolError }
    )

    // PAIRED_KEY_RESULT repeated where CONNECTION_RESPONSE is expected.
    assertTrue(
      fsmAwaitingResponse().onEvent(pairedKeyResult()).any { it is SharingFsmEffect.ProtocolError }
    )
  }

  @Test
  fun localCancelFromAnyStateEmitsCancelledAndDisconnects() {
    val states =
      listOf(
        fsm().apply { start() },
        fsm().apply { start(); onEvent(pairedKeyEncryption()) },
        fsmAwaitingResponse(),
        fsmAwaitingResponse().apply { onEvent(connectionResponse(ConnectionResponseStatus.ACCEPT)) },
      )
    for (machine in states) {
      val before = machine.state
      val effects = machine.onEvent(SharingFsmEvent.UserCancel)
      assertTrue(
        "cancel from $before must emit Cancelled, got $effects",
        effects.any { it is SharingFsmEffect.Cancelled },
      )
      assertEquals("cancel from $before must disconnect", OutboundSharingState.Disconnected, machine.state)
    }
  }

  @Test
  fun peerCancelFromAnyStateDisconnects() {
    val states =
      listOf(
        fsm().apply { start() },
        fsm().apply { start(); onEvent(pairedKeyEncryption()) },
        fsmAwaitingResponse(),
      )
    for (machine in states) {
      val before = machine.state
      val effects = machine.onEvent(frame(SharingFrames.cancel()))
      assertTrue(
        "peer cancel from $before must emit Cancelled, got $effects",
        effects.any { it is SharingFsmEffect.Cancelled },
      )
      assertEquals(OutboundSharingState.Disconnected, machine.state)
    }
  }

  /** Once terminal, the FSM must absorb everything — no late frame may resurrect it. */
  @Test
  fun disconnectedStateAbsorbsAllFurtherEvents() {
    val machine = fsm().apply { start(); onEvent(SharingFsmEvent.UserCancel) }
    assertEquals(OutboundSharingState.Disconnected, machine.state)

    for (event in listOf(pairedKeyEncryption(), pairedKeyResult(), SharingFsmEvent.UserCancel)) {
      assertTrue("terminal FSM must stay silent for $event", machine.onEvent(event).isEmpty())
      assertEquals(OutboundSharingState.Disconnected, machine.state)
    }
  }

  /** A cancel arriving twice must not emit a second Cancelled effect (duplicate teardown). */
  @Test
  fun doubleCancelIsIdempotent() {
    val machine = fsm().apply { start() }
    assertTrue(machine.onEvent(SharingFsmEvent.UserCancel).isNotEmpty())
    assertTrue(machine.onEvent(SharingFsmEvent.UserCancel).isEmpty())
  }

  /** A frame parsed from bytes must drive the FSM the same way a constructed one does. */
  @Test
  fun framesSurviveASerializationRoundTrip() {
    val machine = fsm().apply { start() }
    val wireBytes = SharingFrames.pairedKeyEncryption(secureRandom = deterministicRandom()).toByteArray()
    val parsed = SharingFrames.parse(wireBytes)
    machine.onEvent(frame(parsed))
    assertEquals(OutboundSharingState.SentPairedKeyResult, machine.state)
  }
}
