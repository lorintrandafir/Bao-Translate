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
package com.google.ai.edge.gallery.customtasks.libredrop.protocol.crypto.pin

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Vectors for [PinDerivation.deriveFourDigitPin].
 *
 * The KDoc on `deriveFourDigitPin` claimed the function was "cross-validated against three classes
 * of reference vectors" — but no test existed anywhere in the repository. These are those three
 * classes, computed from the algorithm *specification* (sign-extended bytes, accumulate mod 9973,
 * multiplier stepped by 31 mod 9973, absolute value, zero-padded to four digits) by an independent
 * implementation, so they check the Kotlin against the spec rather than against itself.
 *
 * The PIN is the human-verifiable channel binding: both devices show it and the user compares them
 * to detect a man-in-the-middle. A sign-extension regression would still produce a plausible
 * 4-digit string while silently disagreeing with every stock Quick Share peer, which downgrades
 * the check to theatre.
 */
@Category(Strict::class)
class PinDerivationTest {

  /** Class 1: trivial inputs — loop boundary and zero-padding format. */
  @Test
  fun trivialInputs() {
    assertEquals("0000", PinDerivation.deriveFourDigitPin(ByteArray(0)))
    assertEquals("0000", PinDerivation.deriveFourDigitPin(byteArrayOf(0)))
    assertEquals("0001", PinDerivation.deriveFourDigitPin(byteArrayOf(1)))
    assertEquals("0127", PinDerivation.deriveFourDigitPin(byteArrayOf(0x7f)))
  }

  /**
   * Class 2: single-byte inputs at or above 0x80 — the sign-extension contract.
   *
   * `0xFF` sign-extends to -1, so `hash = -1`, `abs(-1) = 1` and the PIN is "0001". A
   * zero-extending implementation would compute 255 and return "0255". `0x80` extends to -128,
   * giving "0128" instead of the zero-extended "0128"... which happens to collide, so `0xFF` is
   * the discriminating case and is asserted explicitly.
   */
  @Test
  fun signExtensionOfHighBytes() {
    assertEquals("0001", PinDerivation.deriveFourDigitPin(byteArrayOf(0xff.toByte())))
    assertEquals("0128", PinDerivation.deriveFourDigitPin(byteArrayOf(0x80.toByte())))
    assertNotEquals(
      "0xFF must not zero-extend to 255",
      "0255",
      PinDerivation.deriveFourDigitPin(byteArrayOf(0xff.toByte())),
    )
  }

  /** Class 3: multi-byte inputs that wrap the accumulator and multiplier modulo 9973. */
  @Test
  fun multiByteInputsThatWrapTheModulus() {
    assertEquals("8517", PinDerivation.deriveFourDigitPin("abc".toByteArray()))
    assertEquals("8855", PinDerivation.deriveFourDigitPin("QuickShare-authstring".toByteArray()))
    assertEquals("8079", PinDerivation.deriveFourDigitPin(ByteArray(256) { it.toByte() }))
    assertEquals("6509", PinDerivation.deriveFourDigitPin(ByteArray(32) { 0xff.toByte() }))
  }

  /** The output format is load-bearing: the UI renders it verbatim next to the peer's copy. */
  @Test
  fun outputIsAlwaysExactlyFourAsciiDigits() {
    val random = java.util.Random(20260725L)
    repeat(2_000) {
      val bytes = ByteArray(random.nextInt(64)).also(random::nextBytes)
      val pin = PinDerivation.deriveFourDigitPin(bytes)
      assertEquals("PIN length for ${bytes.size} bytes", 4, pin.length)
      assertTrue("PIN '$pin' must be all digits", pin.all { it in '0'..'9' })
    }
  }

  /** Derivation is a pure function of the input bytes. */
  @Test
  fun derivationIsDeterministic() {
    val authString = ByteArray(48) { (it * 7).toByte() }
    val first = PinDerivation.deriveFourDigitPin(authString)
    repeat(10) { assertEquals(first, PinDerivation.deriveFourDigitPin(authString.copyOf())) }
  }

  /** Byte order matters — a transposition must not produce the same PIN. */
  @Test
  fun derivationIsOrderSensitive() {
    assertNotEquals(
      PinDerivation.deriveFourDigitPin(byteArrayOf(1, 2, 3, 4)),
      PinDerivation.deriveFourDigitPin(byteArrayOf(4, 3, 2, 1)),
    )
  }

  /** A single flipped bit anywhere in a realistic authString must change the PIN often. */
  @Test
  fun singleBitFlipsUsuallyChangeThePin() {
    val base = ByteArray(32) { (it * 31 + 7).toByte() }
    val basePin = PinDerivation.deriveFourDigitPin(base)
    var changed = 0
    for (index in base.indices) {
      for (bit in 0 until 8) {
        val mutated = base.copyOf()
        mutated[index] = (mutated[index].toInt() xor (1 shl bit)).toByte()
        if (PinDerivation.deriveFourDigitPin(mutated) != basePin) changed++
      }
    }
    // 256 mutations over a 4-digit space: collisions are expected, wholesale insensitivity is not.
    assertTrue("only $changed/256 bit flips changed the PIN", changed > 200)
  }

  @Test
  fun derivationDoesNotMutateInput() {
    val authString = ByteArray(16) { it.toByte() }
    val copy = authString.copyOf()
    PinDerivation.deriveFourDigitPin(authString)
    assertTrue(authString.contentEquals(copy))
  }
}
