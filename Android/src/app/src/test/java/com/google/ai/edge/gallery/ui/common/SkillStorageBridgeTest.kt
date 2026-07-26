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
package com.google.ai.edge.gallery.ui.common

import com.google.ai.edge.gallery.testkit.Strict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Quota tests for [SkillStorageBridge].
 *
 * Every method here is annotated `@JavascriptInterface` and is therefore callable by any skill's
 * JavaScript. `SharedPreferences` is a poor fit for untrusted input — the whole file is parsed into
 * memory on first access and held for the process lifetime, then rewritten as XML on each commit —
 * so an unbounded `setItem` lets one skill permanently inflate the app's heap and slow every later
 * read for every other skill. These tests pin the caps that make that impossible.
 *
 * Robolectric is used deliberately: the behaviour under test is the interaction with a real
 * `SharedPreferences` (including `contains` and `all.size`), which a fake would not exercise.
 */
@RunWith(RobolectricTestRunner::class)
// See ReceiveModePreferencesTest for why the emulated SDK is pinned below targetSdk.
@Config(sdk = [36])
@Category(Strict::class)
class SkillStorageBridgeTest {

  private lateinit var bridge: SkillStorageBridge
  private val changes = mutableListOf<Pair<String, String?>>()

  @Before
  fun setUp() {
    val context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("skill_storage", android.content.Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    changes.clear()
    bridge = SkillStorageBridge(context) { k, v -> changes += k to v }
  }

  @Test
  fun storesAndReadsBackAValue() {
    assertTrue(bridge.setItem("theme", "dark"))
    assertEquals("dark", bridge.getItem("theme"))
  }

  @Test
  fun removeDeletesTheValueAndNotifies() {
    bridge.setItem("theme", "dark")
    bridge.removeItem("theme")
    assertNull(bridge.getItem("theme"))
    assertEquals("theme" to null, changes.last())
  }

  @Test
  fun missingKeyReadsAsNull() {
    assertNull(bridge.getItem("never-written"))
  }

  @Test
  fun changeCallbackFiresOnWrite() {
    bridge.setItem("k", "v")
    assertEquals("k" to "v", changes.single())
  }

  // -- quotas --

  @Test
  fun oversizedValueIsRejectedAndNotStored() {
    val huge = "x".repeat(SkillStorageBridge.MAX_VALUE_LENGTH + 1)
    assertFalse(bridge.setItem("big", huge))
    assertNull("a rejected write must not land", bridge.getItem("big"))
    assertTrue("a rejected write must not notify", changes.isEmpty())
  }

  @Test
  fun valueExactlyAtTheCapIsAccepted() {
    val atCap = "x".repeat(SkillStorageBridge.MAX_VALUE_LENGTH)
    assertTrue(bridge.setItem("big", atCap))
    assertEquals(atCap.length, bridge.getItem("big")?.length)
  }

  @Test
  fun oversizedOrEmptyKeysAreRejectedEverywhere() {
    val longKey = "k".repeat(SkillStorageBridge.MAX_KEY_LENGTH + 1)
    assertFalse(bridge.setItem(longKey, "v"))
    assertNull(bridge.getItem(longKey))
    assertFalse(bridge.setItem("", "v"))
    assertNull(bridge.getItem(""))
    // removeItem must also refuse rather than touch the store.
    bridge.removeItem(longKey)
    assertTrue(changes.isEmpty())
  }

  @Test
  fun keyExactlyAtTheCapIsAccepted() {
    val atCap = "k".repeat(SkillStorageBridge.MAX_KEY_LENGTH)
    assertTrue(bridge.setItem(atCap, "v"))
    assertEquals("v", bridge.getItem(atCap))
  }

  @Test
  fun entryCountIsCapped() {
    for (i in 0 until SkillStorageBridge.MAX_ENTRIES) {
      assertTrue("insert $i should fit", bridge.setItem("k$i", "v"))
    }
    assertFalse("insert beyond the cap must be refused", bridge.setItem("one-too-many", "v"))
    assertNull(bridge.getItem("one-too-many"))
  }

  /**
   * A store at the entry cap must still accept updates to keys it already holds — otherwise a skill
   * that filled the store could never shrink or correct its own data, and the cap would turn a
   * quota into a permanent lockout.
   */
  @Test
  fun updatingAnExistingKeyStillWorksAtTheEntryCap() {
    for (i in 0 until SkillStorageBridge.MAX_ENTRIES) {
      bridge.setItem("k$i", "v")
    }
    assertTrue("update at cap must be allowed", bridge.setItem("k0", "updated"))
    assertEquals("updated", bridge.getItem("k0"))
    // And removing one frees a slot for a new key.
    bridge.removeItem("k0")
    assertTrue(bridge.setItem("fresh", "v"))
  }

  // -- namespace isolation --

  @Test
  fun namespacedBridgesCannotSeeEachOthersKeys() {
    val context = RuntimeEnvironment.getApplication()
    val skillA = SkillStorageBridge(context, namespace = "skill-a")
    val skillB = SkillStorageBridge(context, namespace = "skill-b")

    assertTrue(skillA.setItem("secret", "from-a"))
    assertTrue(skillB.setItem("secret", "from-b"))

    assertEquals("from-a", skillA.getItem("secret"))
    assertEquals("from-b", skillB.getItem("secret"))
  }

  @Test
  fun oneNamespaceCannotDeleteAnothersKey() {
    val context = RuntimeEnvironment.getApplication()
    val skillA = SkillStorageBridge(context, namespace = "skill-a")
    val skillB = SkillStorageBridge(context, namespace = "skill-b")
    skillA.setItem("k", "a")
    skillB.setItem("k", "b")

    skillB.removeItem("k")

    assertEquals("skill-a's value must survive skill-b's delete", "a", skillA.getItem("k"))
    assertNull(skillB.getItem("k"))
  }

  /**
   * The default (null namespace) must keep reading the exact keys written before namespacing
   * existed, so adopting this parameter never strands a skill's stored data.
   */
  @Test
  fun theDefaultBridgeIsBackwardCompatibleWithUnprefixedKeys() {
    val context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("skill_storage", android.content.Context.MODE_PRIVATE)
      .edit()
      .putString("legacy-key", "legacy-value")
      .commit()

    assertEquals("legacy-value", SkillStorageBridge(context).getItem("legacy-key"))
    assertNull("a namespaced bridge must not see legacy keys",
      SkillStorageBridge(context, namespace = "skill-a").getItem("legacy-key"))
  }

  @Test
  fun localeIsExposedAsALanguageTag() {
    assertTrue(bridge.getLocale().isNotEmpty())
    assertFalse("expected a language tag, not a full locale", bridge.getLocale().contains("_"))
  }
}
