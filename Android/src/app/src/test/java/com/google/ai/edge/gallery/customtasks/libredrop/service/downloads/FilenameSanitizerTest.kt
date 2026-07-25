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
package com.google.ai.edge.gallery.customtasks.libredrop.service.downloads

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Security tests for [FilenameSanitizer].
 *
 * `PayloadHeader.file_name` and `PayloadHeader.parent_folder` are attacker-controlled: they arrive
 * from whatever peer connected to us, before any user consent has been recorded for the *contents*
 * of the transfer. The sanitizer is the only thing standing between those bytes and a MediaStore
 * insert or a `File` constructor, so the properties asserted here are load-bearing:
 *
 *  - no output may contain a path separator,
 *  - no output may contain NUL or an ASCII control character,
 *  - no output may begin with `.` (hidden files, and the `.`/`..` traversal names),
 *  - no output may be empty,
 *  - no relative path may contain a `.` or `..` segment.
 *
 * The traversal cases are asserted as invariants over a corpus rather than as one-off equalities,
 * so a future rewrite of the sanitizer cannot pass by matching a handful of literal strings.
 */
@Category(Strict::class)
class FilenameSanitizerTest {

  /** Payloads a hostile peer would realistically send. */
  private val hostileNames =
    listOf(
      "../../etc/passwd",
      "..\\..\\Windows\\System32\\config\\SAM",
      "/etc/shadow",
      "\\\\server\\share\\file.txt",
      "....//....//secret",
      "photo.jpg\u0000.sh",
      "report\u0007\u001b[2Jpdf",
      ".",
      "..",
      "...",
      ".hidden",
      "   ",
      "",
      "/",
      "//",
      "\\",
      "a/b/c/d",
      "\u0000",
      "\n\r\t",
      ".\u0000.",
      ". .",
      ".. ..",
      ".\t.",
    )

  @Test
  fun noSanitizedNameContainsAPathSeparator() {
    for (raw in hostileNames) {
      val out = FilenameSanitizer.sanitize(raw)
      assertFalse("'$raw' -> '$out' leaked a forward slash", out.contains('/'))
      assertFalse("'$raw' -> '$out' leaked a backslash", out.contains('\\'))
    }
  }

  @Test
  fun noSanitizedNameContainsControlCharacters() {
    for (raw in hostileNames) {
      val out = FilenameSanitizer.sanitize(raw)
      val offender = out.firstOrNull { it.code < 0x20 }
      assertTrue("'$raw' -> '$out' leaked control char ${offender?.code}", offender == null)
    }
  }

  @Test
  fun noSanitizedNameStartsWithADot() {
    for (raw in hostileNames) {
      val out = FilenameSanitizer.sanitize(raw)
      assertFalse("'$raw' -> '$out' would be a hidden file", out.startsWith("."))
    }
  }

  @Test
  fun noSanitizedNameIsEmptyOrBlank() {
    for (raw in hostileNames) {
      val out = FilenameSanitizer.sanitize(raw)
      assertTrue("'$raw' sanitized to blank", out.isNotBlank())
    }
  }

  @Test
  fun traversalSequencesAreFlattenedNotResolved() {
    // The documented rule is "replace separators", not "keep the last segment": the user should
    // still be able to see that the peer sent something odd. The leading `..` is then removed by
    // the leading-dot rule, so `../../etc/passwd` lands as `_.._etc_passwd` — flattened, visibly
    // odd, and unambiguously a direct child of the save root.
    assertEquals("_.._etc_passwd", FilenameSanitizer.sanitize("../../etc/passwd"))
    assertEquals("_etc_shadow", FilenameSanitizer.sanitize("/etc/shadow"))
  }

  @Test
  fun benignNamesAreLeftAlone() {
    for (name in listOf("report.pdf", "My Photo.jpg", "2026-07-25 notes.md", "тест.txt", "日本語.png")) {
      assertEquals(name, FilenameSanitizer.sanitize(name))
    }
  }

  @Test
  fun internalWhitespaceIsPreservedButBoundaryWhitespaceIsTrimmed() {
    assertEquals("My Photo.jpg", FilenameSanitizer.sanitize("  My Photo.jpg  "))
  }

  @Test
  fun extensionIsPreservedSoMimeInferenceStillWorks() {
    assertEquals("passwd.pdf", FilenameSanitizer.sanitize("/passwd.pdf").removePrefix("_"))
    assertTrue(FilenameSanitizer.sanitize("../x/report.pdf").endsWith(".pdf"))
  }

  @Test
  fun emptyResultFallsBackToDefault() {
    assertEquals(FilenameSanitizer.DEFAULT_FALLBACK, FilenameSanitizer.sanitize("."))
    assertEquals(FilenameSanitizer.DEFAULT_FALLBACK, FilenameSanitizer.sanitize(".."))
    assertEquals(FilenameSanitizer.DEFAULT_FALLBACK, FilenameSanitizer.sanitize(""))
    assertEquals(FilenameSanitizer.DEFAULT_FALLBACK, FilenameSanitizer.sanitize("   "))
  }

  /**
   * Regression pin for the dot-strip/trim ordering bug.
   *
   * The original implementation stripped leading dots *before* trimming, so a whitespace-separated
   * dot run survived: ". ." lost its first dot to " .", which then trimmed back down to ".". The
   * sanitizer therefore returned a bare traversal marker while claiming in its own contract that
   * it never returns a leading dot. `sanitizeRelativePath` inherited the defect and could emit a
   * "." path segment.
   */
  @Test
  fun whitespaceSeparatedDotRunsCollapseInsteadOfLeavingATraversalMarker() {
    for (raw in listOf(". .", ".. ..", ". . .", " . . ", "..  ..")) {
      val out = FilenameSanitizer.sanitize(raw)
      assertEquals("'$raw' must collapse to the fallback", FilenameSanitizer.DEFAULT_FALLBACK, out)
    }
    assertEquals(emptyList<String>(), FilenameSanitizer.sanitizeRelativePath(". ./.. .."))
  }

  @Test
  fun callerSuppliedFallbackIsUsedVerbatim() {
    assertEquals("inbox-1", FilenameSanitizer.sanitize("..", fallback = "inbox-1"))
  }

  // -- relative path (parent_folder) --

  @Test
  fun relativePathDropsTraversalSegments() {
    assertEquals(listOf("etc", "passwd"), FilenameSanitizer.sanitizeRelativePath("../../etc/passwd"))
    assertEquals(emptyList<String>(), FilenameSanitizer.sanitizeRelativePath("../.."))
    assertEquals(emptyList<String>(), FilenameSanitizer.sanitizeRelativePath("./././."))
  }

  @Test
  fun relativePathNormalizesMixedAndRepeatedSeparators() {
    assertEquals(listOf("foo", "bar"), FilenameSanitizer.sanitizeRelativePath("/foo//bar/"))
    assertEquals(listOf("Trip Photos", "2025"), FilenameSanitizer.sanitizeRelativePath("Trip Photos\\2025"))
    assertEquals(listOf("a", "b", "c"), FilenameSanitizer.sanitizeRelativePath("a/b\\c"))
  }

  @Test
  fun relativePathEmptyInputProducesNoSegments() {
    assertEquals(emptyList<String>(), FilenameSanitizer.sanitizeRelativePath(""))
  }

  @Test
  fun relativePathNeverEmitsGhostFallbackSegments() {
    // A segment that fully strips (e.g. "...") must be dropped, not replaced by "received_file" —
    // otherwise a hostile peer could litter the user's save root with junk directories.
    val segments = FilenameSanitizer.sanitizeRelativePath("real/.../ /also-real")
    assertFalse(segments.contains(FilenameSanitizer.DEFAULT_FALLBACK))
    assertEquals(listOf("real", "also-real"), segments)
  }

  @Test
  fun everyRelativePathSegmentSatisfiesTheFilenameInvariants() {
    for (raw in hostileNames) {
      for (segment in FilenameSanitizer.sanitizeRelativePath(raw)) {
        assertFalse("'$raw' segment '$segment' has a separator", segment.contains('/') || segment.contains('\\'))
        assertFalse("'$raw' segment '$segment' starts with a dot", segment.startsWith("."))
        assertTrue("'$raw' produced an empty segment", segment.isNotEmpty())
        assertTrue("'$raw' segment '$segment' has a control char", segment.none { it.code < 0x20 })
        assertTrue("'$raw' segment '$segment' is a traversal marker", segment != "." && segment != "..")
      }
    }
  }
}
