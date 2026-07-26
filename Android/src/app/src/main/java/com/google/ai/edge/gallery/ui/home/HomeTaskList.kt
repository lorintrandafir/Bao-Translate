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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.rememberDelayedAnimationProgress
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.delay

@Composable
fun TaskList(
  modelManagerViewModel: ModelManagerViewModel,
  pagerState: PagerState,
  sortedCategories: List<CategoryInfo>,
  tasksByCategories: Map<String, List<Task>>,
  enableAnimation: Boolean,
  navigateToTaskScreen: (Task) -> Unit,
  gm4: Boolean = false,
  grid: Boolean = false,
) {
  // Model list animation:
  //
  // 1.  Slide Up: The entire column of task cards translates upwards,
  // 2.  Fade in one by one: The task card fade in one by one. See TaskCard for details.
  val progress =
    if (!enableAnimation) 1f
    else
      rememberDelayedAnimationProgress(
        initialDelay = TASK_LIST_ANIMATION_START,
        animationDurationMs = CONTENT_COMPOSABLES_ANIMATION_DURATION,
        animationLabel = "task card animation",
      )

  // Tracks when the initial animation is done.
  //
  var initialAnimationDone by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    // Use 5 iterations to make sure all visible task cards are animated.
    delay(((TASK_CARD_ANIMATION_DURATION + TASK_CARD_ANIMATION_DELAY_OFFSET) * 5).toLong())
    initialAnimationDone = true
  }

  // The highlighted tiles at the top.
  if (gm4) {
    Column(
      verticalArrangement = Arrangement.spacedBy(10.dp),
      modifier =
        Modifier.padding(horizontal = 24.dp).graphicsLayer {
          alpha = progress
          translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
        },
    ) {
      val chatToDescription =
        mapOf(
          BuiltInTaskId.BAO_TRANSLATE to stringResource(R.string.home_gemma4_chat_desc),
          BuiltInTaskId.LLM_CHAT to stringResource(R.string.home_gemma4_agent_desc),
        )
      val featuredTasks = listOf(
        modelManagerViewModel.getTaskById(BuiltInTaskId.BAO_TRANSLATE),
        modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT),
      ).filterNotNull()
      for (task in featuredTasks) {
        val desc = chatToDescription[task.id] ?: ""
        TaskCard(
          task = task,
          index = 0,
          animate = !initialAnimationDone && enableAnimation,
          onClick = { navigateToTaskScreen(task) },
          modifier = Modifier.fillMaxWidth(),
          description = desc,
        )
      }

      Text(
        text = stringResource(R.string.home_explore_use_cases),
        style =
          MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 24.sp,
          ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 22.dp, bottom = 16.dp),
      )
    }
  }

  HorizontalPager(
    state = pagerState,
    verticalAlignment = Alignment.Top,
    contentPadding = PaddingValues(horizontal = 20.dp),
  ) { pageIndex ->
    val tasks = tasksByCategories[sortedCategories[pageIndex].id] ?: emptyList()
    if (grid) {
      Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
          Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
            translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
          },
      ) {
        for (i in tasks.indices step 2) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            // First item in the row
            TaskCard(
              task = tasks[i],
              index = i,
              animate =
                (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
              onClick = { navigateToTaskScreen(tasks[i]) },
              modifier = Modifier.weight(1f),
              square = true,
            )

            // Second item in the row, if it exists
            if (i + 1 < tasks.size) {
              TaskCard(
                task = tasks[i + 1],
                index = i + 1,
                animate =
                  (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
                onClick = { navigateToTaskScreen(tasks[i + 1]) },
                modifier = Modifier.weight(1f),
                square = true,
              )
            } else {
              // Add a spacer to fill the remaining space if there's only one item in the last row
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      }
    } else {
      Column(
        modifier =
          Modifier.fillMaxWidth().padding(4.dp).graphicsLayer {
            translationY = (CONTENT_COMPOSABLES_OFFSET_Y.dp * (1 - progress)).toPx()
          },
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        for ((index, task) in tasks.withIndex()) {
          TaskCard(
            task = task,
            index = index,
            animate =
              (pageIndex == 0 || pageIndex == 1) && !initialAnimationDone && enableAnimation,
            onClick = { navigateToTaskScreen(task) },
            modifier = Modifier.fillMaxWidth(),
            square = false,
          )
        }
      }
    }
  }
}
