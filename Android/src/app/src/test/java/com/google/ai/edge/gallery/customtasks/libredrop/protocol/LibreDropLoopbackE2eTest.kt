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
package com.google.ai.edge.gallery.customtasks.libredrop.protocol

import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.InboundConnectionState
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.InboundResult
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.OutboundConnectionState
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.OutboundResult
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.ReceivedItem
import com.google.ai.edge.gallery.testkit.Strict
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * End-to-end loopback test of the whole LibreDrop protocol stack.
 *
 * A real `TcpReceiverServer` binds an ephemeral port on 127.0.0.1 and a real `OutboundConnection`
 * connects to it. Nothing below the socket is mocked: the two halves run the actual
 * `ConnectionRequest` exchange, the actual UKEY2 handshake, the actual D2D key derivation, the
 * actual SecureMessage channel, the actual sharing FSMs on both sides, and the actual chunked
 * payload encoder/assembler. The assertion is byte equality between what the sender read and what
 * the receiver wrote.
 *
 * ### Why this test is the important one
 *
 * The LibreDrop tree is ~44k lines and had no unit tests below the sender ViewModel. Layer-local
 * tests would still let a role mix-up slip through — deriving the sender's D2D keys as `SERVER`
 * instead of `CLIENT`, say, is self-consistent within either half but leaves the halves mutually
 * undecryptable. Only running both halves against each other catches that class of defect, and it
 * is exactly the class that presents on-device as "it just doesn't connect".
 *
 * ### Why runBlocking and not runTest
 *
 * This suite drives real blocking socket I/O on `Dispatchers.IO`. `runTest`'s virtual clock makes
 * every `withTimeout` fire immediately while the wire is still mid-handshake, so the coroutine-test
 * builder is actively wrong here. Each body is `runBlocking { withTimeout(...) { ... } }` per the
 * repository's hardening rule, with a JUnit timeout as a second backstop: a protocol hang fails
 * loudly instead of wedging the build. No sleeps anywhere.
 */
@Category(Strict::class)
class LibreDropLoopbackE2eTest : LibreDropLoopbackHarness() {

  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun singleFileRoundTripsThroughTheRealProtocolStack() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    val content = ByteArray(64 * 1024) { (it * 31).toByte() }
    val destinations = InMemoryDestinations()
    val port = startReceiver(destinations)
    val completion = awaitCompletion()

    val sendResult = outboundTo(port, seed = 1L).run(listOf(fileSource("report.pdf", 1L, content)))
    assertTrue("sender result was $sendResult", sendResult is OutboundResult.Completed)

    val received = completion.await().result
    assertTrue("receiver result was $received", received is InboundResult.Completed)

    val items = (received as InboundResult.Completed).items
    assertEquals(1, items.size)
    val file = items.single()
    assertTrue("expected a File item, got $file", file is ReceivedItem.File)
    assertEquals("report.pdf", (file as ReceivedItem.File).header.fileName)
    assertEquals(content.size.toLong(), file.bytesWritten)
    assertArrayEquals(
      "received bytes must be identical to the sent bytes",
      content,
      destinations.bytesFor(1L),
    )
    assertTrue("destination must be committed on success", destinations.committed.contains(1L))
  } }

  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun multipleFilesInOneConnectionAllArriveIntact() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    val first = ByteArray(3_000) { (it % 251).toByte() }
    val second = ByteArray(150_000) { (it % 97).toByte() }
    val third = byteArrayOf(42)
    val destinations = InMemoryDestinations()
    val port = startReceiver(destinations)
    val completion = awaitCompletion()

    val sendResult =
      outboundTo(port, seed = 3L).run(
        listOf(
          fileSource("a.bin", 1L, first),
          fileSource("b.bin", 2L, second, mimeType = "image/png"),
          fileSource("c.bin", 3L, third, parentFolder = "nested"),
        )
      )

    assertTrue("sender result was $sendResult", sendResult is OutboundResult.Completed)
    val received = completion.await().result
    assertTrue("receiver result was $received", received is InboundResult.Completed)
    assertEquals(3, (received as InboundResult.Completed).items.size)

    assertArrayEquals(first, destinations.bytesFor(1L))
    assertArrayEquals(second, destinations.bytesFor(2L))
    assertArrayEquals(third, destinations.bytesFor(3L))
  } }

  /** A payload larger than the 512 KiB chunk size must span multiple chunks and still reassemble. */
  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun payloadLargerThanOneChunkReassemblesInOrder() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    val content = ByteArray(1_500_000) { (it * 7 + it / 512).toByte() }
    val destinations = InMemoryDestinations()
    val port = startReceiver(destinations)
    val completion = awaitCompletion()

    val sendResult = outboundTo(port, seed = 4L).run(listOf(fileSource("big.bin", 9L, content)))

    assertTrue("sender result was $sendResult", sendResult is OutboundResult.Completed)
    assertTrue(completion.await().result is InboundResult.Completed)
    assertArrayEquals(content, destinations.bytesFor(9L))
  } }

  /** A zero-byte file is a legal Quick Share payload and must not hang the chunk loop. */
  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun emptyFileTransfersSuccessfully() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    val destinations = InMemoryDestinations()
    val port = startReceiver(destinations)
    val completion = awaitCompletion()

    val sendResult = outboundTo(port, seed = 5L).run(listOf(fileSource("empty.txt", 4L, ByteArray(0))))

    assertTrue("sender result was $sendResult", sendResult is OutboundResult.Completed)
    assertTrue(completion.await().result is InboundResult.Completed)
    assertEquals(0, destinations.bytesFor(4L).size)
  } }

  /**
   * When the receiving user declines, the sender must observe a clean [OutboundResult.Rejected] —
   * not a timeout and not a generic failure — and no bytes may be committed on the receiver.
   */
  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun rejectedConsentSurfacesAsRejectedOnBothSides() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    val destinations = InMemoryDestinations()
    val port = startReceiver(destinations, accept = false)
    val completion = awaitCompletion()

    val sendResult = outboundTo(port, seed = 6L).run(listOf(fileSource("secret.bin", 7L, ByteArray(1024))))

    assertTrue("sender result was $sendResult", sendResult is OutboundResult.Rejected)
    val received = completion.await().result
    assertTrue("receiver result was $received", received is InboundResult.Rejected)
    assertTrue("nothing may be committed after a reject", destinations.committed.isEmpty())
    assertFalse("no destination may be opened after a reject", destinations.opened(7L))
  } }

  /**
   * Both peers derive the same 4-digit confirmation PIN from the UKEY2 auth string. If they did
   * not, a user comparing the two screens would see a mismatch on every legitimate transfer and
   * the channel-binding check would be worthless.
   */
  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun bothSidesDeriveTheSameConfirmationPin() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    val destinations = InMemoryDestinations()
    var receiverPin: String? = null
    val port =
      startReceiver(destinations) { connection ->
        serverScope.launch {
          connection.state.collect { state ->
            if (state is InboundConnectionState.WaitingForUserConsent) {
              receiverPin = state.metadata.pin
            }
          }
        }
      }

    val outbound = outboundTo(port, seed = 8L)
    var senderPin: String? = null
    val pinWatcher =
      serverScope.launch {
        outbound.state.collect { state -> pinOf(state)?.let { senderPin = it } }
      }

    val sendResult = outbound.run(listOf(fileSource("x.bin", 1L, ByteArray(16))))
    pinWatcher.cancel()

    assertTrue("sender result was $sendResult", sendResult is OutboundResult.Completed)
    val sender = senderPin
    val receiver = receiverPin
    assertTrue("sender never surfaced a PIN", sender != null)
    assertTrue("receiver never surfaced a PIN", receiver != null)
    assertEquals("the two peers must agree on the confirmation PIN", sender, receiver)
    assertEquals(4, sender?.length)
  } }

  /** `run()` owns a single-use socket; a second invocation must fail loudly rather than corrupt it. */
  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun runIsSingleUse() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    val destinations = InMemoryDestinations()
    val port = startReceiver(destinations)
    val outbound = outboundTo(port, seed = 9L)
    outbound.run(listOf(fileSource("once.bin", 1L, ByteArray(8))))

    val failure =
      runCatching { outbound.run(listOf(fileSource("twice.bin", 2L, ByteArray(8)))) }.exceptionOrNull()
    assertTrue(
      "second run() must throw IllegalStateException, got $failure",
      failure is IllegalStateException,
    )
  } }

  /** Connecting to a closed port must surface as Failed, not hang until the caller's timeout. */
  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun unreachablePeerFailsFast() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    // Bind and immediately release a port so we have one nothing is listening on.
    val deadPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    val result =
      outboundTo(deadPort, seed = 10L, connectTimeoutMillis = 1_000)
        .run(listOf(fileSource("nope.bin", 1L, ByteArray(4))))
    assertTrue("expected Failed, got $result", result is OutboundResult.Failed)
  } }

  /** Duplicate payload ids would make the receiver's reassembler merge two byte streams. */
  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun duplicatePayloadIdsAreRejectedBeforeAnyIo() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    val failure =
      runCatching {
        outboundTo(port = 1, seed = 11L).run(
          listOf(fileSource("a", 5L, ByteArray(1)), fileSource("b", 5L, ByteArray(1)))
        )
      }.exceptionOrNull()
    assertTrue(
      "duplicate payload ids must be rejected before any I/O, got $failure",
      failure is IllegalArgumentException,
    )
  } }

  /** Non-positive payload ids are equally illegal per the Quick Share payload contract. */
  @Test(timeout = JUNIT_HANG_GUARD_MILLIS)
  fun nonPositivePayloadIdIsRejectedBeforeAnyIo() = runBlocking { withTimeout(WIRE_TIMEOUT) {
    val failure =
      runCatching {
        outboundTo(port = 1, seed = 12L).run(listOf(fileSource("a", 0L, ByteArray(1))))
      }.exceptionOrNull()
    assertTrue(
      "non-positive payload id must be rejected before any I/O, got $failure",
      failure is IllegalArgumentException,
    )
  } }

  private fun pinOf(state: OutboundConnectionState): String? =
    when (state) {
      is OutboundConnectionState.AwaitingRemoteAcceptance -> state.pin
      is OutboundConnectionState.Sending -> state.pin
      else -> null
    }

  private companion object {
    /**
     * JUnit-level hang guard, in milliseconds. Second line of defence behind the per-body
     * `withTimeout`: it also covers a wedge that happens before the coroutine timer can arm.
     */
    const val JUNIT_HANG_GUARD_MILLIS = 120_000L
  }
}
