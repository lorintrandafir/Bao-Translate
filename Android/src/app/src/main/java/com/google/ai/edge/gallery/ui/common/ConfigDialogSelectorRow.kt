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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.google.ai.edge.gallery.data.BottomSheetSelectorConfig
import com.google.ai.edge.gallery.data.BottomSheetSelectorItem
import com.google.ai.edge.gallery.data.ConfigValue
import com.google.ai.edge.gallery.ui.theme.Dimensions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Composable function to display a row with a bottom sheet selector.
 *
 * This function renders a row containing a label and a button, allowing users to select an option
 * from a bottom sheet.
 *
 * Split out of [ConfigDialogRows] so that file stays within the repository's per-file size budget.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetSelectorRow(
  config: BottomSheetSelectorConfig,
  values: SnapshotStateMap<String, Any>,
  showLabel: Boolean = true,
  onSelected: (BottomSheetSelectorItem) -> Unit = {},
) {
  var selectedOption by remember {
    mutableStateOf(
      if (config.options.isEmpty()) {
        null
      } else {
        config.options.find { option ->
          when (val value = config.defaultValue) {
            is ConfigValue.StringValue -> option.label == value.value
            else -> false
          }
        }
      }
    )
  }
  var showBottomSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  Column(
    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.xs),
  ) {
    if (showLabel) {
      Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    }
    Row(
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.height(Dimensions.Component.rowHeight)
          .clip(CircleShape)
          .clickable { showBottomSheet = true }
          .border(Dimensions.Stroke.hairline, MaterialTheme.colorScheme.outline, CircleShape)
          .padding(start = Dimensions.Spacing.md, end = Dimensions.Spacing.small),
    ) {
      Text(
        selectedOption?.label ?: "-",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
      )
      Icon(
        Icons.Rounded.ArrowDropDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }
  }

  if (showBottomSheet) {
    ModalBottomSheet(
      onDismissRequest = { showBottomSheet = false },
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        val titleResId = config.bottomSheetTitleResId
        if (titleResId != null) {
          Text(
            stringResource(titleResId),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(Dimensions.Spacing.medium),
          )
        }
        LazyColumn {
          items(config.options) { option ->
            Row(
              modifier =
                Modifier.clickable {
                    selectedOption = option
                    values[config.key.label] = option.label
                    onSelected(option)
                    scope.launch {
                      delay(200)
                      sheetState.hide()
                      showBottomSheet = false
                    }
                  }
                  .padding(horizontal = Dimensions.Spacing.medium, vertical = Dimensions.Spacing.md)
                  .fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.medium),
            ) {
              Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.alpha(if (option == selectedOption) 1f else 0f),
              )
              Text(
                option.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
              )
            }
          }
        }
      }
    }
  }
}
