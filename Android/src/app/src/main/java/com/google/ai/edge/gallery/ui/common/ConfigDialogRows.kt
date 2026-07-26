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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.data.BooleanSwitchConfig
import com.google.ai.edge.gallery.data.BottomSheetSelectorConfig
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.ConfigValue
import com.google.ai.edge.gallery.data.LabelConfig
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.SegmentedButtonConfig
import com.google.ai.edge.gallery.data.ValueType

/** Composable function to display a list of config editor rows. */
@Composable
fun ConfigEditorsPanel(configs: List<Config>, values: SnapshotStateMap<String, Any>) {
  for (config in configs) {
    when (config) {
      // Label.
      is LabelConfig -> {
        LabelRow(config = config, values = values)
      }

      // Number slider.
      is NumberSliderConfig -> {
        NumberSliderRow(config = config, values = values)
      }

      // Boolean switch.
      is BooleanSwitchConfig -> {
        BooleanSwitchRow(config = config, values = values)
      }

      // Segmented button.
      is SegmentedButtonConfig -> {
        SegmentedButtonRow(config = config, values = values)
      }

      // Bottom sheet selector.
      is BottomSheetSelectorConfig -> {
        BottomSheetSelectorRow(config = config, values = values)
      }

      else -> {}
    }
  }
}

@Composable
fun LabelRow(config: LabelConfig, values: SnapshotStateMap<String, Any>) {
  Column(modifier = Modifier.fillMaxWidth()) {
    // Field label.
    Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    // Content label.
    val label = values[config.key.label]?.toString().orEmpty()
    Text(label, style = MaterialTheme.typography.bodyMedium)
  }
}

/** Returns a numeric config value as a slider-ready float. */
private fun numericConfigValue(value: Any?): Float =
  when (value) {
    is ConfigValue.FloatValue -> value.value
    is ConfigValue.DoubleValue -> value.value.toFloat()
    is ConfigValue.IntValue -> value.value.toFloat()
    is Number -> value.toFloat()
    is String -> value.toFloatOrNull() ?: 0f
    else -> 0f
  }

fun getTextFieldDisplayValue(valueType: ValueType, value: Float): String {
  return runCatching {
    when (valueType) {
      ValueType.FLOAT -> {
        "%.2f".format(value)
      }

      ValueType.INT -> {
        "${value.toInt()}"
      }

      else -> {
        ""
      }
    }
  }.getOrElse {
    ""
  }
}

/**
 * Composable function to display a number slider with an associated text input field.
 *
 * This function renders a row containing a slider and a text field, both used to modify a numeric
 * value. The slider allows users to visually adjust the value within a specified range, while the
 * text field provides precise numeric input.
 */
@Composable
fun NumberSliderRow(config: NumberSliderConfig, values: SnapshotStateMap<String, Any>) {
  val focusManager = LocalFocusManager.current

  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    // Field label.
    val minStr = getTextFieldDisplayValue(config.valueType, config.sliderMin)
    val maxStr = getTextFieldDisplayValue(config.valueType, config.sliderMax)
    Text("${config.key.label} ($minStr-$maxStr)", style = MaterialTheme.typography.titleSmall)

    // Controls row.
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      var isFocused by remember { mutableStateOf(false) }
      val focusRequester = remember { FocusRequester() }

      // Display value for the text field. It can temporarily hold invalid or out-of-range input
      // while the user is editing.
      var textFieldDisplayValue by remember {
        mutableStateOf(
          getTextFieldDisplayValue(config.valueType, numericConfigValue(values[config.key.label]))
        )
      }

      // Number slider.
      val sliderValue = numericConfigValue(values[config.key.label])

      Slider(
        modifier = Modifier.height(Dimensions.Icon.medium).weight(1f).padding(end = Dimensions.Spacing.small),
        value = sliderValue,
        valueRange = config.sliderMin..config.sliderMax,
        onValueChange = {
          values[config.key.label] = it
          textFieldDisplayValue = getTextFieldDisplayValue(config.valueType, it)
        },
      )

      Spacer(modifier = Modifier.width(Dimensions.Spacing.small))

      // A smaller text field.
      BasicTextField(
        value = textFieldDisplayValue,
        modifier =
          Modifier.width(Dimensions.Component.imagePreviewHeight).focusRequester(focusRequester).onFocusChanged {
            isFocused = it.isFocused

            // When leaving focus, display the internal value so that any invalid value is cleared.
            if (!isFocused) {
              textFieldDisplayValue =
                getTextFieldDisplayValue(config.valueType, numericConfigValue(values[config.key.label]))
            }
          },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        singleLine = true,
        onValueChange = {
          // Always update the display value to reflect the update on the UI.
          textFieldDisplayValue = it

          // Only if the new value could be converted to a float, then update the internal value,
          // bounded by the slider range. It prevents invalid values like NaN from crashing the app.
          it.toFloatOrNull()?.let { floatValue ->
            values[config.key.label] = minOf(maxOf(floatValue, config.sliderMin), config.sliderMax)
          }
        },
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
      ) { innerTextField ->
        Box(
          modifier =
            Modifier.border(
              width = if (isFocused) Dimensions.Component.strokeWidth else Dimensions.Stroke.hairline,
              color =
                if (isFocused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
              shape = MaterialTheme.shapes.extraSmall,
            )
        ) {
          Box(modifier = Modifier.padding(Dimensions.Spacing.small)) { innerTextField() }
        }
      }
    }

    if (config.key == ConfigKeys.MAX_TOKENS) {
      val sliderValue = numericConfigValue(values[config.key.label])
      if (sliderValue >= 10000f) {
        Text(
          text = stringResource(R.string.max_tokens_warning_message),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = Dimensions.Spacing.small),
        )
      }
    }
  }
}

/**
 * Composable function to display a row with a boolean switch.
 *
 * This function renders a row containing a label and a switch, allowing users to toggle a boolean
 * value.
 */
@Composable
fun BooleanSwitchRow(config: BooleanSwitchConfig, values: SnapshotStateMap<String, Any>) {
  val switchValue = values[config.key.label] == true
  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    Switch(checked = switchValue, onCheckedChange = { values[config.key.label] = it })
  }
}

/**
 * Composable function to display a row with a segmented button.
 *
 * This function renders a row containing a label and a segmented button, allowing users to select
 * one or more options from a list.
 */
@Composable
fun SegmentedButtonRow(config: SegmentedButtonConfig, values: SnapshotStateMap<String, Any>) {
  val selectedOptions: List<String> = remember {
    values[config.key.label]?.toString().orEmpty().split(",")
  }
  var selectionStates: List<Boolean> by remember {
    mutableStateOf(
      List(config.options.size) { index -> selectedOptions.contains(config.options[index]) }
    )
  }

  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(config.key.label, style = MaterialTheme.typography.titleSmall)
    MultiChoiceSegmentedButtonRow {
      config.options.forEachIndexed { index, label ->
        SegmentedButton(
          shape = SegmentedButtonDefaults.itemShape(index = index, count = config.options.size),
          onCheckedChange = {
            var newSelectionStates = selectionStates.toMutableList()
            val selectedCount = newSelectionStates.count { it }

            // Single select.
            if (!config.allowMultiple) {
              if (!newSelectionStates[index]) {
                newSelectionStates = MutableList(config.options.size) { it == index }
              }
            }
            // Multiple select.
            else {
              if (!(selectedCount == 1 && newSelectionStates[index])) {
                newSelectionStates[index] = !newSelectionStates[index]
              }
            }
            selectionStates = newSelectionStates

            values[config.key.label] =
              config.options
                .filterIndexed { index, option -> selectionStates[index] }
                .joinToString(",")
          },
          checked = selectionStates[index],
          label = { Text(label) },
        )
      }
    }
  }
}

