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

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.SentimentVerySatisfied
import com.google.ai.edge.gallery.common.SkillTryOutChip
import com.google.ai.edge.gallery.data.AllowedSkill
import com.google.ai.edge.gallery.proto.Skill

val TRYOUT_CHIPS: List<SkillTryOutChip> =
  listOf(
    SkillTryOutChip(
      icon = Icons.Outlined.Map,
      label = "Interactive Map",
      prompt = "Show me Googleplex on interactive map.",
      skillName = "interactive-map",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Notifications,
      label = "Schedule Reminder",
      prompt = "Set a daily reminder at 9am to check my schedule for today.",
      skillName = "schedule-notification",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.SentimentVerySatisfied,
      label = "Track my mood",
      prompt =
        "Log yesterday's mood as 2 because it was raining quite heavily, and log today's mood as 9 because I had a great time playing pickleball again. Then show me my mood dashboard.",
      skillName = "mood-tracker",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.Lightbulb,
      label = "Learn something new",
      prompt = "I want to learn something new!",
      skillName = "learn-something-new",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.LocalLibrary,
      label = "Query Wikipedia",
      prompt = "Check Wikipedia about Oscars 2026. Tell me who won the best picture.",
      skillName = "query-wikipedia",
    ),
    SkillTryOutChip(
      icon = Icons.Outlined.QrCode,
      label = "Generate QR code",
      prompt = "Generate QR code for https://deepmind.google/models/gemma/",
      skillName = "qr-code",
    ),
  )

enum class SkillSource(val sourceName: String) {
  BUILTIN("builtin"),
  FEATURED("featured"),
  REMOTE_URL("remote_url"),
  LOCAL_IMPORT("local_import"),
  UNKNOWN("unknown"),
}

enum class SkillAction(val value: String) {
  ADD("add"),
  DELETE("delete"),
  ENABLE("enable"),
  DISABLE("disable"),
  ENABLE_ALL("enable_all"),
  DISABLE_ALL("disable_all"),
}

data class SkillState(val skill: Skill)

data class SkillManagerUiState(
  val loading: Boolean = false,
  val skills: List<SkillState> = listOf(),
  val validating: Boolean = false,
  val validationError: String? = null,
  val importDirectoryUri: Uri? = null,
  val loadingSkillAllowlist: Boolean = false,
  val featuredSkills: List<AllowedSkill> = listOf(),
  val skillAllowlistError: String? = null,
)
