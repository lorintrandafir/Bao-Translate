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

import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.FileSource
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.InboundConnection
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.InboundConnectionState
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.connection.OutboundConnection
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.payload.FileDestinationFactory
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.server.InboundConnectionCompletion
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.server.TcpReceiverServer
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.PayloadTransferFrame.PayloadHeader
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After

/**
 * Shared fixtures for the LibreDrop loopback tests.
 *
 * Everything here is deliberately real except the destination sink: a genuine [TcpReceiverServer]
 * on 127.0.0.1 and a genuine [OutboundConnection] over a genuine TCP socket. The only substitution
 * is [InMemoryDestinations], which stands in for the Android MediaStore writer so the assertions
 * can compare bytes without touching a filesystem.
 *
 * Subclasses get [startReceiver] (bind + auto-answer consent), [outboundTo] (a sender pointed at
 * the bound port) and [fileSource] (an in-memory [FileSource]).
 */
abstract class LibreDropLoopbackHarness {

  protected val serverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var runningServer: TcpReceiverServer? = null

  /** The receiver under test. Valid only after [startReceiver]. */
  protected val server: TcpReceiverServer
    get() = checkNotNull(runningServer) { "startReceiver() has not been called" }

  @After
  fun stopLoopbackServer() {
    runningServer?.stopBlocking()
    serverScope.cancel()
  }

  /** Collects everything the receiver writes, keyed by payload id. */
  protected class InMemoryDestinations : FileDestinationFactory {
    private val buffers: MutableMap<Long, GrowableChannel> = ConcurrentHashMap()

    /** Payload ids the assembler explicitly committed after a successful FileComplete. */
    val committed: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** Payload ids the assembler aborted (cancel, reject, protocol error). */
    val aborted: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    override fun open(header: PayloadHeader): SeekableByteChannel =
      buffers.getOrPut(header.id) { GrowableChannel() }

    override fun commit(payloadId: Long): Boolean = committed.add(payloadId)

    override fun abort(payloadId: Long): Boolean = aborted.add(payloadId)

    /** Bytes written for [payloadId]; empty when the payload was never opened. */
    fun bytesFor(payloadId: Long): ByteArray = buffers[payloadId]?.toByteArray() ?: ByteArray(0)

    /** Whether a destination was ever opened for [payloadId]. */
    fun opened(payloadId: Long): Boolean = buffers.containsKey(payloadId)
  }

  /**
   * Minimal in-memory [SeekableByteChannel]. The assembler writes chunks at explicit offsets and
   * may deliver them out of order, so position/truncate semantics have to be real rather than a
   * plain append-only stream.
   */
  private class GrowableChannel : SeekableByteChannel {
    private var bytes = ByteArray(0)
    private var cursor = 0L
    private var open = true

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun read(dst: ByteBuffer): Int {
      if (cursor >= bytes.size) return -1
      val count = minOf(dst.remaining(), bytes.size - cursor.toInt())
      dst.put(bytes, cursor.toInt(), count)
      cursor += count
      return count
    }

    override fun write(src: ByteBuffer): Int {
      val count = src.remaining()
      val end = cursor.toInt() + count
      if (end > bytes.size) bytes = bytes.copyOf(end)
      src.get(bytes, cursor.toInt(), count)
      cursor += count
      return count
    }

    override fun position(): Long = cursor

    override fun position(newPosition: Long): SeekableByteChannel {
      cursor = newPosition
      return this
    }

    override fun size(): Long = bytes.size.toLong()

    override fun truncate(size: Long): SeekableByteChannel {
      if (size < bytes.size) bytes = bytes.copyOf(size.toInt())
      if (cursor > size) cursor = size
      return this
    }

    override fun isOpen(): Boolean = open

    override fun close() {
      open = false
    }
  }

  /** An in-memory [FileSource] whose channel replays [content] from byte zero on every open. */
  protected fun fileSource(
    name: String,
    payloadId: Long,
    content: ByteArray,
    mimeType: String = "application/octet-stream",
    parentFolder: String = "",
  ): FileSource =
    FileSource(
      name = name,
      size = content.size.toLong(),
      mimeType = mimeType,
      lastModifiedTimestampMillis = 0L,
      payloadId = payloadId,
      parentFolder = parentFolder,
      open = { Channels.newChannel(ByteArrayInputStream(content)) as ReadableByteChannel },
    )

  /**
   * Deterministic randomness so a failure reproduces on re-run. SHA1PRNG with an explicit seed is
   * fully reproducible; the default `SecureRandom()` constructor is not.
   */
  protected fun seededRandom(seed: Long): SecureRandom =
    SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }

  /**
   * Bind the receiver on an ephemeral loopback port and auto-answer the consent prompt with
   * [accept], through the very same `submitUserConsent` entry point the notification action uses.
   *
   * @param onConnection invoked for each accepted connection before consent is answered, so a
   *   test can observe the connection's own state flow.
   * @return the bound TCP port.
   */
  protected suspend fun startReceiver(
    destinations: InMemoryDestinations,
    accept: Boolean = true,
    onConnection: (InboundConnection) -> Unit = {},
  ): Int {
    val srv =
      TcpReceiverServer(
        parentScope = serverScope,
        factoryProvider = { destinations },
        secureRandomProvider = { seededRandom(RECEIVER_SEED) },
        bindAddress = InetAddress.getLoopbackAddress(),
      )
    runningServer = srv
    serverScope.launch {
      srv.activeConnections.collect { connection ->
        onConnection(connection)
        serverScope.launch {
          connection.state.collect { state ->
            if (state is InboundConnectionState.WaitingForUserConsent) {
              connection.submitUserConsent(accept)
            }
          }
        }
      }
    }
    return srv.start()
  }

  /** Await the receiver's next completion. Started before the sender runs so no result is missed. */
  protected fun awaitCompletion(): Deferred<InboundConnectionCompletion> =
    serverScope.async { server.results.first() }

  /** A sender pointed at [port] on loopback, seeded for reproducibility. */
  protected fun outboundTo(
    port: Int,
    seed: Long,
    connectTimeoutMillis: Int = OutboundConnection.DEFAULT_CONNECT_TIMEOUT_MILLIS,
  ): OutboundConnection =
    OutboundConnection(
      targetAddress = InetAddress.getLoopbackAddress(),
      port = port,
      connectTimeoutMillis = connectTimeoutMillis,
      secureRandom = seededRandom(seed),
    )

  protected companion object {
    private const val RECEIVER_SEED = 2L

    /** Ceiling on any single wire operation, so a protocol hang fails instead of stalling. */
    const val WIRE_TIMEOUT = 30_000L
  }
}
