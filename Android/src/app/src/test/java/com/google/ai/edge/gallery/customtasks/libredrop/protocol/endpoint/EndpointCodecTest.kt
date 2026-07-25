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
package com.google.ai.edge.gallery.customtasks.libredrop.protocol.endpoint

import com.google.ai.edge.gallery.testkit.Strict
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Wire-format tests for the endpoint descriptor a peer publishes in its mDNS TXT record and in
 * `ConnectionRequestFrame.endpoint_info`: [EndpointInfo], [TlvRecord] and [Base64Url].
 *
 * This is the first thing either side parses, and it is parsed *before* any authentication — the
 * bytes come from whoever is on the network. Two properties therefore matter more than exact
 * field layout:
 *
 *  1. Round-trip fidelity, including TLV records this build does not understand. Dropping unknown
 *     records would silently break interop the moment Google ships a new one.
 *  2. Total absence of crashes on malformed input. `parse` must return `null`, never throw, for
 *     truncated, over-long or nonsense buffers — a thrown exception here is a remote DoS on the
 *     discovery loop.
 */
@Category(Strict::class)
class EndpointCodecTest {

  private fun metadata(seed: Byte = 1): ByteArray = ByteArray(EndpointInfo.METADATA_LEN) { (it + seed).toByte() }

  private fun visible(
    name: String = "Pixel 8",
    version: Int = 1,
    deviceType: DeviceType = DeviceType.PHONE,
    tlvRecords: List<TlvRecord> = emptyList(),
  ) = EndpointInfo(
    version = version,
    hidden = false,
    deviceType = deviceType,
    reserved = false,
    metadata = metadata(),
    deviceName = name,
    tlvRecords = tlvRecords,
  )

  @Test
  fun visibleEndpointRoundTrips() {
    val original = visible()
    val parsed = EndpointInfo.parse(original.serialize())
    assertNotNull(parsed)
    assertEquals(original.version, parsed?.version)
    assertEquals(original.hidden, parsed?.hidden)
    assertEquals(original.deviceType, parsed?.deviceType)
    assertEquals(original.deviceName, parsed?.deviceName)
    assertArrayEquals(original.metadata, parsed?.metadata)
  }

  @Test
  fun hiddenEndpointOmitsTheNameOnTheWireAndRoundTrips() {
    val hidden =
      EndpointInfo(
        version = 1,
        hidden = true,
        deviceType = DeviceType.LAPTOP,
        reserved = false,
        metadata = metadata(),
        deviceName = null,
      )
    val bytes = hidden.serialize()
    val parsed = EndpointInfo.parse(bytes)
    assertEquals(true, parsed?.hidden)
    assertNull("a hidden peer must not leak a device name", parsed?.deviceName)
    // A hidden record is exactly header + metadata: no name-length byte, no name bytes.
    assertEquals(EndpointInfo.HEADER_LEN + EndpointInfo.METADATA_LEN, bytes.size)
  }

  @Test
  fun hiddenEndpointWithANameIsRejectedAtConstruction() {
    assertThrows(IllegalArgumentException::class.java) {
      EndpointInfo(1, true, DeviceType.PHONE, false, metadata(), "leaky")
    }
  }

  @Test
  fun visibleEndpointWithoutANameIsRejectedAtConstruction() {
    assertThrows(IllegalArgumentException::class.java) {
      EndpointInfo(1, false, DeviceType.PHONE, false, metadata(), null)
    }
  }

  @Test
  fun metadataMustBeExactlySixteenBytes() {
    for (size in intArrayOf(0, 15, 17, 64)) {
      assertThrows(IllegalArgumentException::class.java) {
        EndpointInfo(1, false, DeviceType.PHONE, false, ByteArray(size), "x")
      }
    }
  }

  @Test
  fun versionMustFitInThreeBits() {
    assertThrows(IllegalArgumentException::class.java) {
      EndpointInfo(EndpointInfo.MAX_VERSION + 1, false, DeviceType.PHONE, false, metadata(), "x")
    }
    // The maximum legal version must still round-trip.
    val maxVersion = visible(version = EndpointInfo.MAX_VERSION)
    assertEquals(EndpointInfo.MAX_VERSION, EndpointInfo.parse(maxVersion.serialize())?.version)
  }

  @Test
  fun everyDeviceTypeRoundTrips() {
    for (type in DeviceType.entries) {
      val parsed = EndpointInfo.parse(visible(deviceType = type).serialize())
      assertEquals("device type $type", type, parsed?.deviceType)
    }
  }

  /** Forward compatibility: TLV records this build has no meaning for must survive verbatim. */
  @Test
  fun unknownTlvRecordsRoundTripVerbatim() {
    val records =
      listOf(
        TlvRecord(type = 0x7f, value = byteArrayOf(1, 2, 3)),
        TlvRecord(type = 0xfe, value = ByteArray(0)),
        TlvRecord(type = 0x01, value = ByteArray(TlvRecord.MAX_BYTE_VALUE) { it.toByte() }),
      )
    val parsed = EndpointInfo.parse(visible(tlvRecords = records).serialize())
    assertEquals(records, parsed?.tlvRecords)
  }

  @Test
  fun tlvRejectsOutOfRangeTypeOrOversizedValue() {
    assertThrows(IllegalArgumentException::class.java) { TlvRecord(-1, ByteArray(0)) }
    assertThrows(IllegalArgumentException::class.java) { TlvRecord(0x100, ByteArray(0)) }
    assertThrows(IllegalArgumentException::class.java) {
      TlvRecord(1, ByteArray(TlvRecord.MAX_BYTE_VALUE + 1))
    }
  }

  /** TLV equality is structural over the value bytes, not reference identity. */
  @Test
  fun tlvEqualityIsStructural() {
    assertEquals(TlvRecord(3, byteArrayOf(9, 9)), TlvRecord(3, byteArrayOf(9, 9)))
    assertEquals(
      TlvRecord(3, byteArrayOf(9, 9)).hashCode(),
      TlvRecord(3, byteArrayOf(9, 9)).hashCode(),
    )
    assertTrue(TlvRecord(3, byteArrayOf(9, 9)) != TlvRecord(3, byteArrayOf(9, 8)))
    assertTrue(TlvRecord(3, byteArrayOf(9, 9)) != TlvRecord(4, byteArrayOf(9, 9)))
  }

  @Test
  fun deviceNamesWithMultiByteCharactersRoundTrip() {
    for (name in listOf("日本語のデバイス", "Тестовое устройство", "emoji 🎉 phone", "Ünïcödé")) {
      assertEquals(name, EndpointInfo.parse(visible(name = name).serialize())?.deviceName)
    }
  }

  @Test
  fun deviceNameLongerThanOneByteLengthIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      visible(name = "a".repeat(EndpointInfo.MAX_NAME_LEN + 1))
    }
  }

  // -- malformed input --

  @Test
  fun truncatedBuffersParseToNullRatherThanThrowing() {
    val full = visible().serialize()
    for (length in 0 until full.size) {
      val truncated = full.copyOf(length)
      // The only contract is "no exception escapes"; a short prefix may legitimately be
      // unparseable, and a hidden-shaped prefix may parse into a valid hidden record.
      val parsed = EndpointInfo.parse(truncated)
      assertTrue("length=$length must not throw", parsed == null || parsed.metadata.size == EndpointInfo.METADATA_LEN)
    }
  }

  @Test
  fun emptyBufferParsesToNull() {
    assertNull(EndpointInfo.parse(ByteArray(0)))
  }

  /** Fuzz: random bytes must never crash the parser. This runs before any peer authentication. */
  @Test
  fun randomBytesNeverThrow() {
    val random = Random(20260725L)
    repeat(5_000) {
      val bytes = ByteArray(random.nextInt(96)).also(random::nextBytes)
      // Any outcome is acceptable except an exception.
      EndpointInfo.parse(bytes)
    }
  }

  // -- Base64Url --

  @Test
  fun base64UrlRoundTripsArbitraryBytes() {
    val random = Random(7L)
    repeat(500) {
      val bytes = ByteArray(random.nextInt(64)).also(random::nextBytes)
      val encoded = Base64Url.encode(bytes)
      assertArrayEquals(bytes, Base64Url.decode(encoded))
    }
  }

  @Test
  fun base64UrlUsesTheUrlSafeAlphabetWithoutPadding() {
    // 0xFB 0xFF encodes to "+/" in the standard alphabet and "-_" in the URL-safe one.
    val encoded = Base64Url.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte()))
    assertTrue("must not use '+' or '/': $encoded", encoded.none { it == '+' || it == '/' })
    assertTrue("mDNS TXT values are unpadded: $encoded", encoded.none { it == '=' })
  }

  @Test
  fun base64UrlRejectsMalformedInputWithNullRatherThanThrowing() {
    for (bad in listOf("!!!!", "a", "====", "ab*d")) {
      assertNull("'$bad' must decode to null", Base64Url.decode(bad))
    }
  }

  @Test
  fun endpointInfoSurvivesABase64UrlHop() {
    // This is the exact path an mDNS TXT record takes: serialize -> base64url -> TXT -> back.
    val original = visible(name = "Living Room TV", deviceType = DeviceType.TABLET)
    val decoded = Base64Url.decode(Base64Url.encode(original.serialize()))
    assertNotNull(decoded)
    val parsed = decoded?.let { EndpointInfo.parse(it) }
    assertEquals("Living Room TV", parsed?.deviceName)
    assertEquals(DeviceType.TABLET, parsed?.deviceType)
  }
}
