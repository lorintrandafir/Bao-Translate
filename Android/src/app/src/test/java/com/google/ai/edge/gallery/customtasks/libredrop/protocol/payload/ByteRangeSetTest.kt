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
package com.google.ai.edge.gallery.customtasks.libredrop.protocol.payload

import com.google.ai.edge.gallery.customtasks.libredrop.protocol.payload.ByteRangeSet.AddResult
import com.google.ai.edge.gallery.customtasks.libredrop.protocol.payload.ByteRangeSet.Range
import com.google.ai.edge.gallery.testkit.Strict
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Tests for [ByteRangeSet], the coverage map behind resumable transfers.
 *
 * A wrong answer here is not a crash — it is silent data corruption. `isComplete` gating a
 * "transfer finished" notification on a set with a hidden gap hands the user a truncated file that
 * looks complete, and a missed [AddResult.PartialOverlap] lets a misbehaving peer splice
 * overlapping chunk bodies into the assembled output.
 *
 * The merge behaviour is checked both by explicit layout assertions and by a randomised model
 * test against a naive boolean-array oracle, so a rewrite of the splice logic cannot pass by
 * happening to satisfy the hand-written cases.
 */
@Category(Strict::class)
class ByteRangeSetTest {

  @Test
  fun emptySetCoversNothing() {
    val set = ByteRangeSet()
    assertEquals(0, set.size)
    assertEquals(0L, set.coveredBytes)
    assertFalse(set.contains(0, 1))
    assertTrue("an empty span is trivially contained", set.contains(5, 5))
  }

  @Test
  fun sequentialChunksMergeIntoASingleRange() {
    val set = ByteRangeSet()
    for (start in 0 until 5) {
      assertEquals(AddResult.Added(100), set.add(start * 100L, start * 100L + 100))
    }
    assertEquals(listOf(Range(0, 500)), set.snapshot())
    assertEquals(500L, set.coveredBytes)
    assertTrue(set.isComplete(500))
  }

  @Test
  fun outOfOrderChunksStillMergeIntoASingleRange() {
    val set = ByteRangeSet()
    // Arrival order 3, 0, 4, 1, 2 — the shape a multi-threaded or retrying sender produces.
    for (start in listOf(3, 0, 4, 1, 2)) {
      set.add(start * 100L, start * 100L + 100)
    }
    assertEquals(listOf(Range(0, 500)), set.snapshot())
    assertTrue(set.isComplete(500))
  }

  @Test
  fun disjointChunksStayAsSeparateRangesUntilTheGapIsFilled() {
    val set = ByteRangeSet()
    set.add(0, 100)
    set.add(200, 300)
    assertEquals(listOf(Range(0, 100), Range(200, 300)), set.snapshot())
    assertEquals(200L, set.coveredBytes)
    assertFalse("a gap must not read as complete", set.isComplete(300))

    set.add(100, 200)
    assertEquals(listOf(Range(0, 300)), set.snapshot())
    assertTrue(set.isComplete(300))
  }

  @Test
  fun adjacentRangesAreMergedNotLeftTouching() {
    val set = ByteRangeSet()
    set.add(0, 10)
    set.add(10, 20)
    assertEquals("[0,10) and [10,20) are adjacent and must coalesce", 1, set.size)
    assertEquals(listOf(Range(0, 20)), set.snapshot())
  }

  @Test
  fun exactDuplicateIsReportedAsAlreadyCovered() {
    val set = ByteRangeSet()
    set.add(0, 100)
    assertEquals(AddResult.AlreadyCovered, set.add(0, 100))
    assertEquals(100L, set.coveredBytes)
    assertEquals(1, set.size)
  }

  @Test
  fun subRangeOfAnExistingRangeIsAlreadyCovered() {
    val set = ByteRangeSet()
    set.add(0, 100)
    assertEquals(AddResult.AlreadyCovered, set.add(25, 75))
  }

  @Test
  fun rangeThatOverlapsAndExtendsIsFlaggedAsPartialOverlap() {
    val set = ByteRangeSet()
    set.add(0, 100)
    // The wire protocol forbids this shape: a resent chunk must repeat its exact byte range.
    val result = set.add(50, 150)
    assertEquals(AddResult.PartialOverlap(50), result)
    assertEquals(listOf(Range(0, 150)), set.snapshot())
  }

  @Test
  fun emptyRangeIsANoOp() {
    val set = ByteRangeSet()
    assertEquals(AddResult.AlreadyCovered, set.add(42, 42))
    assertEquals(0, set.size)
  }

  @Test
  fun containsRequiresFullContainmentNotMereOverlap() {
    val set = ByteRangeSet()
    set.add(100, 200)
    assertTrue(set.contains(100, 200))
    assertTrue(set.contains(120, 180))
    assertFalse("straddling the left edge is not containment", set.contains(50, 150))
    assertFalse("straddling the right edge is not containment", set.contains(150, 250))
    assertFalse(set.contains(0, 50))
  }

  @Test
  fun zeroLengthPayloadIsCompleteWithNoRanges() {
    assertTrue(ByteRangeSet().isComplete(0))
  }

  @Test
  fun coverageThatStartsAfterZeroIsNotComplete() {
    val set = ByteRangeSet()
    set.add(1, 100)
    assertFalse("a missing first byte must not read as complete", set.isComplete(100))
  }

  @Test
  fun clearResetsTheSetForReuse() {
    val set = ByteRangeSet()
    set.add(0, 100)
    set.clear()
    assertEquals(0, set.size)
    assertEquals(0L, set.coveredBytes)
    assertFalse(set.contains(0, 1))
  }

  @Test
  fun snapshotIsADefensiveCopy() {
    val set = ByteRangeSet()
    set.add(0, 10)
    val snapshot = set.snapshot()
    set.add(20, 30)
    assertEquals("snapshot must not observe later mutations", 1, snapshot.size)
  }

  @Test
  fun negativeStartIsRejected() {
    assertThrows(IllegalArgumentException::class.java) { ByteRangeSet().add(-1, 10) }
  }

  @Test
  fun invertedRangeIsRejected() {
    assertThrows(IllegalArgumentException::class.java) { ByteRangeSet().add(10, 5) }
  }

  @Test
  fun negativeTotalSizeIsRejected() {
    assertThrows(IllegalArgumentException::class.java) { ByteRangeSet().isComplete(-1) }
  }

  /**
   * Model test: drive the set with random inserts and compare against a naive boolean-array
   * oracle. Checks the three externally-visible invariants at every step — covered-byte count,
   * canonical (sorted, non-overlapping, non-adjacent) layout, and `contains` agreement.
   */
  @Test
  fun randomInsertsAgreeWithANaiveCoverageOracle() {
    val universe = 512
    val random = Random(20260725L)
    repeat(200) { trial ->
      val set = ByteRangeSet()
      val oracle = BooleanArray(universe)
      repeat(40) {
        val start = random.nextInt(universe)
        val end = start + random.nextInt(universe - start + 1)
        set.add(start.toLong(), end.toLong())
        for (i in start until end) oracle[i] = true

        assertEquals("trial $trial covered bytes", oracle.count { it }.toLong(), set.coveredBytes)

        val snapshot = set.snapshot()
        for (i in 1 until snapshot.size) {
          assertTrue(
            "trial $trial layout not canonical: ${snapshot[i - 1]} then ${snapshot[i]}",
            snapshot[i - 1].end < snapshot[i].start,
          )
        }
        for (r in snapshot) {
          assertTrue("trial $trial empty range in layout", r.end > r.start)
        }
      }
      // `contains` must agree with the oracle for every probe span.
      repeat(50) {
        val start = random.nextInt(universe)
        val end = start + random.nextInt(universe - start + 1)
        val expected = (start until end).all { oracle[it] }
        assertEquals(
          "trial $trial contains($start,$end)",
          expected,
          set.contains(start.toLong(), end.toLong()),
        )
      }
    }
  }

  /** Large offsets must not overflow or lose precision — payloads can exceed 2 GiB. */
  @Test
  fun handlesOffsetsBeyondIntRange() {
    val set = ByteRangeSet()
    val base = 8L * 1024 * 1024 * 1024 // 8 GiB
    set.add(base, base + 1024)
    set.add(base + 1024, base + 2048)
    assertEquals(listOf(Range(base, base + 2048)), set.snapshot())
    assertEquals(2048L, set.coveredBytes)
    assertTrue(set.contains(base + 512, base + 1536))
  }
}
