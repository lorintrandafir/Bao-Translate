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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.RevealingText
import com.google.ai.edge.gallery.ui.common.SwipingText
import androidx.compose.material3.MaterialTheme
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.ai.edge.gallery.ui.theme.homePageTitleStyle

@Composable
fun AppTitle(enableAnimation: Boolean) {
  val firstLineText = stringResource(R.string.app_name_first_part)
  val secondLineText = stringResource(R.string.app_name_second_part)
  val titleColor = MaterialTheme.customColors.appTitleGradientColors[1]
  val screenWidthInDp = LocalConfiguration.current.screenWidthDp.dp
  val fontSize = with(LocalDensity.current) { (screenWidthInDp.toPx() * 0.12f).toSp() }
  val titleStyle = homePageTitleStyle.copy(fontSize = fontSize, lineHeight = fontSize)

  // First line text "Google AI" and its animation.
  //
  // The animation starts with the first line of text swiping in from left to right, progressively
  // revealing itself in the title color (blue). Then, after a brief delay, the exact same text, but
  // in the onSurface color (which is black in light mode), begins its own left-to-right swiping
  // animation. This second animation is positioned directly on top of the first, appearing just as
  // the initial reveal is finishing or has just completed, creating a layered and dynamic visual
  // effect.
  Box(modifier = Modifier.clearAndSetSemantics {}) {
    var delay = ANIMATION_INIT_DELAY
    if (enableAnimation) {
      SwipingText(
        text = firstLineText,
        style = titleStyle,
        color = titleColor,
        animationDelay = delay,
        animationDurationMs = TITLE_FIRST_LINE_ANIMATION_DURATION,
      )
      delay += (TITLE_FIRST_LINE_ANIMATION_DURATION * 0.3).toLong()
    }
    SwipingText(
      text = firstLineText,
      style = titleStyle,
      color = MaterialTheme.colorScheme.onSurface,
      animationDelay = if (enableAnimation) delay else 0,
      animationDurationMs = if (enableAnimation) TITLE_FIRST_LINE_ANIMATION_DURATION else 0,
    )
  }
  // Second line text "Edge Gallery" and its animation.
  //
  // The initial animation is the same as the first line text. Right before it is done, the final
  // text with a gradient is revealed.
  Box(modifier = Modifier.clearAndSetSemantics {}) {
    var delay = TITLE_SECOND_LINE_ANIMATION_START
    if (enableAnimation) {
      SwipingText(
        text = secondLineText,
        style = titleStyle,
        color = titleColor,
        modifier = Modifier.offset(y = (-16).dp),
        animationDelay = delay,
        animationDurationMs = TITLE_SECOND_LINE_ANIMATION_DURATION,
      )
      delay += (TITLE_SECOND_LINE_ANIMATION_DURATION * 0.3).toInt()
      SwipingText(
        text = secondLineText,
        style = titleStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.offset(y = (-16).dp),
        animationDelay = delay,
        animationDurationMs = TITLE_SECOND_LINE_ANIMATION_DURATION,
      )
      delay += (TITLE_SECOND_LINE_ANIMATION_DURATION * 0.6).toInt()
    }
    RevealingText(
      text = secondLineText,
      style =
        titleStyle.copy(
          brush = linearGradient(colors = MaterialTheme.customColors.appTitleGradientColors)
        ),
      modifier = Modifier.offset(x = (-16).dp, y = (-16).dp),
      animationDelay = if (enableAnimation) delay else 0,
      animationDurationMs = if (enableAnimation) TITLE_SECOND_LINE_ANIMATION_DURATION2 else 0,
    )
  }
}

@Composable
fun AppTitleGm4(enableAnimation: Boolean) {
  val text1 = stringResource(R.string.app_title_google)
  val text2 = stringResource(R.string.app_title_ai_edge_gallery)
  val annotatedText = buildAnnotatedString {
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface)) { append(text1) }
    append(" ")
    withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(text2) }
  }

  RevealingText(
    text = "",
    annotatedText = annotatedText,
    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
    animationDelay = 0,
    animationDurationMs =
      if (enableAnimation) {
        (TITLE_FIRST_LINE_ANIMATION_DURATION + TITLE_SECOND_LINE_ANIMATION_DURATION)
      } else {
        0
      },
    extraTextPadding = 0.dp,
  )
}
