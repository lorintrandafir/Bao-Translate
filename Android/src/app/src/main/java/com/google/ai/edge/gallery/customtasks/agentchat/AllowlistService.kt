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

import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.common.parseJson
import com.google.ai.edge.gallery.data.AllowedSkill
import com.google.ai.edge.gallery.data.SkillAllowlist
import java.io.File

private const val TAG = "AGAllowlistService"

/**
 * Pure data result from loading the skill allowlist. ViewModel maps this into UI state.
 */
data class AllowlistLoadResult(
  val featuredSkills: List<AllowedSkill>,
  val error: String? = null,
)

object AllowlistService {

  /**
   * Attempts to load the skill allowlist: returns cached data if fresh, otherwise fetches from
   * the remote URL, caches it, and returns the result. Falls back to cache on network failure.
   */
  fun loadAllowlist(
    allowlistUrl: String,
    cacheTtlMs: Long,
    cacheFilename: String,
    filesDir: File,
  ): AllowlistLoadResult {
    BaoLog.d(TAG, "Loading skill allowlist from: $allowlistUrl")
    val cacheFile = File(filesDir, cacheFilename)
    val now = System.currentTimeMillis()
    val cacheFresh =
      cacheFile.exists() && (now - cacheFile.lastModified()) < cacheTtlMs

    if (cacheFresh) {
      val cached = parseJson<SkillAllowlist>(cacheFile.readText())
      if (cached != null) {
        BaoLog.d(TAG, "Loaded ${cached.featuredSkills.size} featured skills from cache.")
        return AllowlistLoadResult(featuredSkills = cached.featuredSkills)
      }
    }

    return runCatching {
      BaoLog.d(TAG, "Fetching skill allowlist from: $allowlistUrl")
      val result =
        getJsonResponse<SkillAllowlist>(allowlistUrl)
          ?: throw Exception("Failed to fetch or parse JSON from $allowlistUrl")

      val allowlist = result.jsonObj
      BaoLog.d(TAG, "Successfully loaded ${allowlist.featuredSkills.size} featured skills.")
      runCatching { cacheFile.writeText(result.textContent) }
        .onFailure { e -> BaoLog.w(TAG, "Failed to write allowlist cache", e) }

      AllowlistLoadResult(featuredSkills = allowlist.featuredSkills)
    }.getOrElse { e ->
      BaoLog.e(TAG, "Error loading skill allowlist", e)
      if (cacheFile.exists()) {
        val fallback = parseJson<SkillAllowlist>(cacheFile.readText())
        if (fallback != null) {
          BaoLog.d(TAG, "Offline fallback: ${fallback.featuredSkills.size} cached skills.")
          return AllowlistLoadResult(featuredSkills = fallback.featuredSkills)
        }
      }
      AllowlistLoadResult(
        featuredSkills = emptyList(),
        error = "Failed to load skill list: ${e.message}",
      )
    }
  }
}
