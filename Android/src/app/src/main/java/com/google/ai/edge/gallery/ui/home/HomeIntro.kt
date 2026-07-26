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

package com.google.ai.edge.gallery.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString
import com.google.ai.edge.gallery.ui.common.rememberDelayedAnimationProgress


@Composable
fun IntroText(enableAnimation: Boolean, gm4: Boolean) {
  val litertUrl = "https://huggingface.co/litert-community"

  // Intro text animation:
  //
  // fade in + slide up.
  val progress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = TITLE_SECOND_LINE_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "intro text animation",
      )
    }

  val introText = buildAnnotatedString {
    val gemma4Url = "https://ai.google.dev/gemma"
    if (gm4) {
      append(stringResource(R.string.intro_gm4_prefix))
      append(buildTrackableUrlAnnotatedString(url = litertUrl, linkText = stringResource(R.string.intro_gm4_link_litert)))
      append(stringResource(R.string.intro_gm4_middle))
      append(buildTrackableUrlAnnotatedString(url = gemma4Url, linkText = stringResource(R.string.intro_gm4_link_gemma4)))
      append(stringResource(R.string.intro_gm4_suffix))
    } else {
      append("${stringResource(R.string.app_intro)} ")
      append(
        buildTrackableUrlAnnotatedString(
          url = litertUrl,
          linkText = stringResource(R.string.litert_community_label),
        )
      )
    }
  }
  Text(
    introText,
    style = MaterialTheme.typography.bodyMedium,
    modifier =
      Modifier.graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
  )
}

@Composable
fun TryGm4IntroText(enableAnimation: Boolean) {
  // fade in + slide up.
  val progress =
    if (!enableAnimation) {
      1f
    } else {
      rememberDelayedAnimationProgress(
        initialDelay = TITLE_SECOND_LINE_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "intro text animation",
      )
    }
  Row(
    modifier =
      Modifier.padding(top = 24.dp).graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      ImageVector.vectorResource(R.drawable.gemma_logo),
      contentDescription = stringResource(R.string.home_try_gemma4),
      modifier = Modifier.size(24.dp),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = stringResource(R.string.home_try_gemma4),
      style =
        MaterialTheme.typography.headlineSmall.copy(
          fontWeight = FontWeight.Medium,
          fontSize = 20.sp,
          lineHeight = 24.sp,
        ),
      color = MaterialTheme.colorScheme.onSurface,
    )
  }

  Text(
    stringResource(R.string.home_gemma4_intro),
    style = MaterialTheme.typography.bodyMedium,
    modifier =
      Modifier.graphicsLayer {
        alpha = progress
        translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
      },
  )
}
