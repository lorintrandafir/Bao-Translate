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
package com.google.ai.edge.gallery.customtasks.libredrop.protocol.crypto

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Conformance tests for [Hkdf] against the published RFC 5869 Appendix A SHA-256 test vectors.
 *
 * These are *external* vectors, not values captured from this implementation, so they detect a
 * wrong implementation rather than merely pinning current behaviour. Every expected value below
 * appears verbatim in RFC 5869 Appendix A (test cases 1, 2 and 3) and was independently
 * recomputed with a separate HMAC-SHA256 implementation before being written down here.
 *
 * Why this matters: [Hkdf] feeds `D2DKeyDerivation`, which produces the encrypt/HMAC keys for the
 * SecureMessage layer. A one-byte error in the expand loop would still produce plausible-looking
 * key material and would only surface as an undiagnosable decrypt failure against a stock Quick
 * Share peer.
 */
@Category(Strict::class)
class HkdfTest {

  /** RFC 5869 A.1 — basic case with salt and info, SHA-256, L = 42. */
  @Test
  fun rfc5869_testCase1_matchesPublishedVector() {
    val ikm = ByteArray(22) { 0x0b }
    val salt = hex("000102030405060708090a0b0c")
    val info = hex("f0f1f2f3f4f5f6f7f8f9")

    assertEquals(
      "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
      Hkdf.extract(salt, ikm).toHex(),
    )
    assertEquals(
      "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
      Hkdf.derive(ikm = ikm, salt = salt, info = info, length = 42).toHex(),
    )
  }

  /**
   * RFC 5869 A.2 — long inputs (80-byte IKM/salt/info) and L = 82, which forces the expand loop
   * through three HMAC blocks. This is the case that catches an off-by-one in the block counter.
   */
  @Test
  fun rfc5869_testCase2_longInputsSpanMultipleExpandBlocks() {
    val ikm = ByteArray(0x50) { it.toByte() }
    val salt = ByteArray(0x50) { (0x60 + it).toByte() }
    val info = ByteArray(0x50) { (0xb0 + it).toByte() }

    assertEquals(
      "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
      Hkdf.extract(salt, ikm).toHex(),
    )
    assertEquals(
      "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
        "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
        "cc30c58179ec3e87c14c01d5c1f3434f1d87",
      Hkdf.derive(ikm = ikm, salt = salt, info = info, length = 82).toHex(),
    )
  }

  /**
   * RFC 5869 A.3 — zero-length salt and info. Exercises the §2.2 rule that an empty salt is
   * replaced by HashLen zero bytes. Quick Share's D2D derivation relies on this path.
   */
  @Test
  fun rfc5869_testCase3_emptySaltAndInfo() {
    val ikm = ByteArray(22) { 0x0b }

    assertEquals(
      "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
      Hkdf.extract(ByteArray(0), ikm).toHex(),
    )
    assertEquals(
      "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
      Hkdf.derive(ikm = ikm, salt = ByteArray(0), info = ByteArray(0), length = 42).toHex(),
    )
  }

  /**
   * The §2.2 empty-salt substitution must be exactly "HashLen zero bytes" — not "no salt at all"
   * and not a different length. Passing 32 explicit zero bytes must therefore be indistinguishable
   * from passing an empty salt.
   */
  @Test
  fun emptySaltIsEquivalentToThirtyTwoZeroBytes() {
    val ikm = ByteArray(22) { 0x0b }
    assertArrayEquals(
      Hkdf.extract(ByteArray(0), ikm),
      Hkdf.extract(ByteArray(32), ikm),
    )
  }

  /**
   * An all-zero salt of ANY length below the HMAC block size is equivalent to the empty-salt
   * substitution, because HMAC right-pads a short key with zeros to the 64-byte block. This is a
   * property of HMAC, not a defect: it is asserted here so nobody "fixes" the empty-salt branch
   * into something that breaks RFC conformance while chasing a phantom collision.
   */
  @Test
  fun allZeroSaltsShorterThanTheHmacBlockCollapseToTheSameKey() {
    val ikm = ByteArray(22) { 0x0b }
    val empty = Hkdf.extract(ByteArray(0), ikm).toHex()
    for (saltLength in intArrayOf(1, 32, 33, 63, 64)) {
      assertEquals(
        "all-zero salt of length $saltLength must match the empty-salt substitution",
        empty,
        Hkdf.extract(ByteArray(saltLength), ikm).toHex(),
      )
    }
  }

  /** A salt with any non-zero byte must change the PRK. */
  @Test
  fun nonZeroSaltChangesThePrk() {
    val ikm = ByteArray(22) { 0x0b }
    val salted = ByteArray(32).also { it[31] = 1 }
    assertNotEquals(
      Hkdf.extract(ByteArray(0), ikm).toHex(),
      Hkdf.extract(salted, ikm).toHex(),
    )
  }

  /**
   * Output truncation must be a prefix relationship: deriving L bytes and deriving L+k bytes must
   * agree on the first L bytes. A regression that reset the block counter or re-seeded the loop
   * would break this while still passing a single fixed-length vector.
   */
  @Test
  fun shorterDerivationIsAPrefixOfLongerDerivation() {
    val ikm = "quick-share-ikm".toByteArray()
    val salt = "salt".toByteArray()
    val info = "info".toByteArray()

    val long = Hkdf.derive(ikm, salt, info, 200)
    for (length in intArrayOf(1, 31, 32, 33, 64, 199)) {
      val short = Hkdf.derive(ikm, salt, info, length)
      assertArrayEquals(
        "length=$length must be a prefix of the 200-byte derivation",
        long.copyOfRange(0, length),
        short,
      )
    }
  }

  /** Different info strings must yield different key material — that is the whole point of info. */
  @Test
  fun infoIsDomainSeparating() {
    val ikm = ByteArray(32) { 7 }
    val salt = ByteArray(16) { 9 }
    assertNotEquals(
      Hkdf.derive(ikm, salt, "client".toByteArray(), 32).toHex(),
      Hkdf.derive(ikm, salt, "server".toByteArray(), 32).toHex(),
    )
  }

  /** RFC 5869 §2.3 caps output at 255 * HashLen = 8160 bytes for SHA-256. */
  @Test
  fun maximumOutputLengthIsAccepted() {
    assertEquals(8160, Hkdf.derive(ByteArray(32), ByteArray(0), ByteArray(0), 8160).size)
  }

  @Test
  fun outputLengthAboveRfcMaximumIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      Hkdf.derive(ByteArray(32), ByteArray(0), ByteArray(0), 8161)
    }
  }

  @Test
  fun zeroOutputLengthIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      Hkdf.derive(ByteArray(32), ByteArray(0), ByteArray(0), 0)
    }
  }

  @Test
  fun negativeOutputLengthIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      Hkdf.derive(ByteArray(32), ByteArray(0), ByteArray(0), -1)
    }
  }

  /** The extract step always produces exactly one SHA-256 block regardless of input sizes. */
  @Test
  fun extractAlwaysProducesThirtyTwoBytes() {
    assertEquals(32, Hkdf.extract(ByteArray(0), ByteArray(0)).size)
    assertEquals(32, Hkdf.extract(ByteArray(1), ByteArray(4096)).size)
  }

  /** Derivation must not mutate the caller's buffers. */
  @Test
  fun derivationDoesNotMutateInputs() {
    val ikm = ByteArray(22) { 0x0b }
    val salt = hex("000102030405060708090a0b0c")
    val info = hex("f0f1f2f3f4f5f6f7f8f9")
    val ikmCopy = ikm.copyOf()
    val saltCopy = salt.copyOf()
    val infoCopy = info.copyOf()

    Hkdf.derive(ikm, salt, info, 42)

    assertArrayEquals(ikmCopy, ikm)
    assertArrayEquals(saltCopy, salt)
    assertArrayEquals(infoCopy, info)
  }

  private fun hex(s: String): ByteArray =
    ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
