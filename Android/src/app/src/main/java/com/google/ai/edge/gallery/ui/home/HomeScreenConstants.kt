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

import android.content.Context
import androidx.annotation.StringRes
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo

private const val TAG = "AGHomeScreen"
const val TASK_COUNT_ANIMATION_DURATION = 250
const val ANIMATION_INIT_DELAY = 0L
const val TOP_APP_BAR_ANIMATION_DURATION = 600
const val TITLE_FIRST_LINE_ANIMATION_DURATION = 600
const val TITLE_SECOND_LINE_ANIMATION_DURATION = 600
const val TITLE_SECOND_LINE_ANIMATION_DURATION2 = 800
const val TITLE_SECOND_LINE_ANIMATION_START =
  ANIMATION_INIT_DELAY + (TITLE_FIRST_LINE_ANIMATION_DURATION * 0.5).toInt()
const val TASK_LIST_ANIMATION_START = TITLE_SECOND_LINE_ANIMATION_START + 110
const val TASK_CARD_ANIMATION_DELAY_OFFSET = 100
const val TASK_CARD_ANIMATION_DURATION = 600
const val CONTENT_COMPOSABLES_ANIMATION_DURATION = 1200
const val CONTENT_COMPOSABLES_OFFSET_Y = 16

/** Navigation destination data */
object HomeScreenDestination {
  @StringRes val titleRes = R.string.app_name
}

val PREDEFINED_CATEGORY_ORDER = listOf(Category.LLM.id, Category.EXPERIMENTAL.id)

fun getCategoryLabel(context: Context, category: CategoryInfo): String {
  val stringRes = category.labelStringRes
  val label = category.label
  if (stringRes != null) {
    return context.getString(stringRes)
  } else if (label != null) {
    return label
  }
  return context.getString(R.string.category_unlabeled)
}
