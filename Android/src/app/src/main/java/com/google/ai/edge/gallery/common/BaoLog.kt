/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.common

/**
 * Project-wide log facade. Zero-dep wrapper that enforces:
 *
 *  - **Tag discipline**: tags longer than [TAG_MAX] characters are truncated to avoid the
 *    Android 23-char tag limit (API 24) which silently drops the entry otherwise.
 *  - **Throwable handling**: every level overload accepts an optional [Throwable].
 *  - **Uniform API**: `BaoLog.d(tag, msg)` matches the SLF4J/Android idiom.
 *  - **JVM test safety**: uses reflection to dispatch to `android.util.Log`, so this class
 *    loads cleanly in plain JVM unit tests where `android.util.Log` is absent.
 */
object BaoLog {

  const val TAG_MAX: Int = 23

  private val logClass: Class<*>? by lazy {
    val runtimeName = System.getProperty("java.runtime.name") ?: ""
    if (!runtimeName.contains("Android", ignoreCase = true)) return@lazy null
    runCatching { Class.forName("android.util.Log") }.getOrNull()
  }

  private fun log(level: String, tag: String, message: String) {
    val cls = logClass ?: return
    val method = cls.getMethod(level, String::class.java, String::class.java)
    method.invoke(null, normalize(tag), message)
  }

  private fun log(level: String, tag: String, message: String, throwable: Throwable) {
    val cls = logClass ?: return
    val method = cls.getMethod(level, String::class.java, String::class.java, Throwable::class.java)
    method.invoke(null, normalize(tag), message, throwable)
  }

  private fun logW(tag: String, throwable: Throwable) {
    val cls = logClass ?: return
    val method = cls.getMethod("w", String::class.java, Throwable::class.java)
    method.invoke(null, normalize(tag), throwable)
  }

  /**
   * Whether a log at [level] may be emitted in a build where [debugBuild] describes
   * `BuildConfig.DEBUG`.
   *
   * The verbose levels (`v`, `d`) are dropped in release builds. They are where diagnostic
   * messages interpolate application values, and an audit of this tree found call sites writing a
   * user's typed input history and a model's response text straight into them. Nothing strips
   * those on the way out: this facade dispatches to `android.util.Log` unconditionally, and the
   * release build sets `isMinifyEnabled = false`, so there is no R8 pass removing log calls
   * either. The result shipped user-derived text to logcat, readable by any app holding
   * `READ_LOGS` or by an adb-connected host.
   *
   * `i`, `w` and `e` are kept in release: they are the operational levels a field bug report
   * needs, and they are expected to carry shapes and error codes rather than content.
   *
   * Pure and internal so the gate is unit-testable without needing to rebuild under a different
   * variant — see `BaoLogTest`.
   */
  internal fun shouldEmit(level: String, debugBuild: Boolean): Boolean =
    debugBuild || (level != "v" && level != "d")

  private val debugBuild: Boolean get() = com.google.ai.edge.gallery.BuildConfig.DEBUG

  fun v(tag: String, message: String) { if (shouldEmit("v", debugBuild)) log("v", tag, message) }
  fun v(tag: String, message: String, throwable: Throwable) {
    if (shouldEmit("v", debugBuild)) log("v", tag, message, throwable)
  }
  fun d(tag: String, message: String) { if (shouldEmit("d", debugBuild)) log("d", tag, message) }
  fun d(tag: String, message: String, throwable: Throwable) {
    if (shouldEmit("d", debugBuild)) log("d", tag, message, throwable)
  }
  fun i(tag: String, message: String) { log("i", tag, message) }
  fun i(tag: String, message: String, throwable: Throwable) { log("i", tag, message, throwable) }
  fun w(tag: String, message: String) { log("w", tag, message) }
  fun w(tag: String, message: String, throwable: Throwable) { log("w", tag, message, throwable) }
  fun w(tag: String, throwable: Throwable) { logW(tag, throwable) }
  fun e(tag: String, message: String) { log("e", tag, message) }
  fun e(tag: String, message: String, throwable: Throwable) { log("e", tag, message, throwable) }

  internal fun normalize(tag: String): String {
    // Android 7+ silently drops log entries whose tags contain control characters.
    val sanitized = buildString(tag.length) {
      for (ch in tag) {
        append(if (ch.code in 0..0x1F || ch.code == 0x7F) '_' else ch)
      }
    }
    if (sanitized.length <= TAG_MAX) return sanitized
    // Codepoint-aware truncation: never split a surrogate pair.
    val sb = StringBuilder()
    var codepoints = 0
    var i = 0
    while (i < sanitized.length && codepoints < TAG_MAX) {
      val c = sanitized[i]
      if (c.isHighSurrogate() && i + 1 < sanitized.length && sanitized[i + 1].isLowSurrogate()) {
        sb.append(c)
        sb.append(sanitized[i + 1])
        i += 2
      } else {
        sb.append(c)
        i += 1
      }
      codepoints++
    }
    return sb.toString()
  }

}
