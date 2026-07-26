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

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.core.content.edit
import com.google.ai.edge.gallery.common.BaoLog
import java.util.Locale

/**
 * Synchronous key/value storage bridge exposed to skill WebViews as `window.AndroidBridge`.
 *
 * Backed by app-private [android.content.SharedPreferences] rather than the WebView's DOM
 * `localStorage`, so skill data:
 *  - survives a WebView data/cache clear,
 *  - is visible to (and exportable by) the host app, and
 *  - can be observed for realtime change broadcasts.
 *
 * The `@JavascriptInterface` methods are invoked on a WebView binder thread, not the main thread.
 * `SharedPreferences` reads/writes are synchronous, which matches the synchronous JS contract in
 * `_shared/storage.js` (`getItem` returns a value directly).
 *
 * [onChange] is invoked after every mutation so the host can broadcast a realtime change event
 * back into open WebViews. It runs on the calling binder thread; the host is responsible for
 * marshaling to the main thread before touching the WebView.
 *
 * @param namespace Optional isolation prefix. When supplied, every key this bridge reads or writes
 *   is scoped to `"<namespace>/<key>"`, so one skill cannot read or overwrite another's data. When
 *   `null` (the default) keys are used verbatim — the historical behaviour, kept so existing stored
 *   data stays reachable and no migration is forced. Callers that know which skill a WebView is
 *   hosting should pass its stable directory name.
 */
class SkillStorageBridge(
  context: Context,
  // `namespace` precedes `onChange` so the trailing-lambda call form used by GalleryWebView —
  // `SkillStorageBridge(ctx) { key, value -> ... }` — still binds the lambda to `onChange`.
  private val namespace: String? = null,
  private val onChange: ((key: String, value: String?) -> Unit)? = null,
) {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /**
   * Map a caller-supplied key into the stored key. The separator is `/`, which cannot appear in a
   * skill directory name, so a namespaced key can never collide with a differently-namespaced one.
   */
  private fun storedKey(key: String): String = if (namespace == null) key else "$namespace/$key"

  @JavascriptInterface
  fun getItem(key: String): String? =
    if (isAcceptableKey(key)) prefs.getString(storedKey(key), null) else null

  /**
   * Store [value] under [key]. Returns `false` when the write was rejected for exceeding a quota.
   *
   * The return value is new; JavaScript callers that ignore it are unaffected.
   *
   * ### Why the quotas exist
   *
   * This method is reachable from any skill's JavaScript, and `SharedPreferences` is the wrong
   * shape to absorb unbounded input: the whole file is parsed into memory on first access and held
   * there for the process lifetime, then rewritten as XML on every commit. Without a cap, one skill
   * writing a few megabytes — by bug or by design — permanently inflates the app's heap and slows
   * every later read of this store, for every other skill.
   */
  @JavascriptInterface
  fun setItem(
    key: String,
    value: String,
  ): Boolean {
    if (!isAcceptableKey(key)) {
      BaoLog.w(TAG, "Rejected skill storage key: length=${key.length}")
      return false
    }
    if (value.length > MAX_VALUE_LENGTH) {
      BaoLog.w(TAG, "Rejected skill storage value for key length=${key.length}: ${value.length} chars")
      return false
    }
    // Only an insert of a *new* key can grow the store, so an update of an existing key is always
    // allowed — otherwise a skill at the cap could never shrink its own value.
    if (!prefs.contains(storedKey(key)) && prefs.all.size >= MAX_ENTRIES) {
      BaoLog.w(TAG, "Rejected skill storage insert: store is at the $MAX_ENTRIES entry cap")
      return false
    }
    prefs.edit { putString(storedKey(key), value) }
    onChange?.invoke(key, value)
    return true
  }

  @JavascriptInterface
  fun removeItem(key: String) {
    if (!isAcceptableKey(key)) return
    prefs.edit { remove(storedKey(key)) }
    onChange?.invoke(key, null)
  }

  private fun isAcceptableKey(key: String): Boolean =
    key.isNotEmpty() && key.length <= MAX_KEY_LENGTH

  /** The host app locale tag (e.g. `en`, `es`), used by `_shared/i18n.js` to pick a string table. */
  @JavascriptInterface fun getLocale(): String = Locale.getDefault().language

  companion object {
    private const val TAG = "SkillStorageBridge"
    private const val PREFS_NAME = "skill_storage"
    const val INTERFACE_NAME = "AndroidBridge"

    /** Generous for a namespaced state key; far below anything that would bloat the XML store. */
    const val MAX_KEY_LENGTH: Int = 256

    /** 64 KiB of characters — ample for skill state, bounded enough to keep the store in memory. */
    const val MAX_VALUE_LENGTH: Int = 64 * 1024

    /** Cap on distinct keys. Prevents unbounded growth by key-count rather than by value size. */
    const val MAX_ENTRIES: Int = 512
  }
}
