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

package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.ai.edge.gallery.BuildConfig

/**
 * Skill allowlist URL injected from `bao.skillAllowlistUrl` gradle property. Empty string
 * disables the remote fetch (only bundled + locally imported skills are shown). When set,
 * [loadSkillAllowlist] fetches the JSON, caches it to disk with a 24h TTL, and falls back to
 * the cached copy on network failure.
 */
internal val SKILL_ALLOWLIST_URL: String = BuildConfig.SKILL_ALLOWLIST_URL

/** Max age of the cached allowlist before a fresh fetch is required (24 hours, in millis). */
internal const val ALLOWLIST_CACHE_TTL_MS = 24L * 60 * 60 * 1000

/** Cache file for the allowlist JSON, rooted at filesDir for persistence across launches. */
internal const val ALLOWLIST_CACHE_FILENAME = "skill_allowlist.json"

internal val DEFAULT_DISABLED_SKILLS =
  setOf("calculate-hash", "kitchen-adventure", "text-spinner", "send-email")
