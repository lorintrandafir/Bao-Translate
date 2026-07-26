package com.google.ai.edge.gallery.ui.benchmark

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.style.TextOverflow
import com.google.ai.edge.gallery.proto.ValueSeries
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlin.math.abs

@Composable
internal fun StatRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  unit: String = "",
  rawValue: Double? = null,
  baselineValue: Double? = null,
  lessIsBetter: Boolean = false,
) {
  val locale = LocalLocale.current.platformLocale
  Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    // label.
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(0.6f),
      maxLines = 1,
      overflow = TextOverflow.MiddleEllipsis,
    )
    // Value
    Column(
      verticalArrangement = Arrangement.Top,
      horizontalAlignment = Alignment.Start,
      modifier = Modifier.weight(0.4f),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          value,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
        )
        AnimatedContent(
          baselineValue,
          contentAlignment = Alignment.CenterStart,
          transitionSpec = { fadeIn() togetherWith fadeOut() },
        ) { curBaselineValue ->
          if (curBaselineValue != null) {
            val doubleValue = rawValue ?: value.toDoubleOrNull() ?: 0.0
            val pct = (doubleValue - curBaselineValue) / curBaselineValue * 100
            val strPct = String.format(locale, "%.1f", abs(pct))
            val sign = if (pct >= 0.0) "+" else "-"
            val betterSign = if (lessIsBetter) "-" else "+"
            val color =
              if (sign == betterSign) {
                MaterialTheme.customColors.successColor
              } else {
                MaterialTheme.customColors.errorTextColor
              }
            Text("$sign$strPct%", style = MaterialTheme.typography.labelMedium, color = color)
          }
        }
      }
      if (unit.isNotEmpty()) {
        Text(
          unit,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
      }
    }
  }
}

@Composable
internal fun ValueSeriesRow(
  label: String,
  valueSeries: ValueSeries,
  aggregation: Aggregation,
  modifier: Modifier = Modifier,
  unit: String = "",
  baselineValueSeries: ValueSeries? = null,
  baselineAggregation: Aggregation? = null,
  lessIsBetter: Boolean = false,
) {
  val locale = LocalLocale.current.platformLocale
  val value = getAggregationValue(valueSeries = valueSeries, aggregation = aggregation)
  var baselineValue: Double? = null
  if (baselineValueSeries != null && baselineAggregation != null) {
    baselineValue =
      getAggregationValue(valueSeries = baselineValueSeries, aggregation = baselineAggregation)
  }
  var showValueSeriesBottomSheet by remember { mutableStateOf(false) }

  Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    // label.
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(0.6f),
      maxLines = 1,
      overflow = TextOverflow.MiddleEllipsis,
    )
    // Value
    Column(
      verticalArrangement = Arrangement.Top,
      horizontalAlignment = Alignment.Start,
      modifier = Modifier.weight(0.4f),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        val linkColor = MaterialTheme.customColors.linkColor
        val isMultipleRuns = valueSeries.valueCount > 1
        val textColor = if (isMultipleRuns) linkColor else MaterialTheme.colorScheme.onSurface
        val textModifier =
          if (isMultipleRuns) {
            Modifier.drawBehind {
                val strokeWidth = 2f
                val y = size.height - strokeWidth

                // Define the dash pattern: 8px line, 8px gap
                val dashPath = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                drawLine(
                  color = linkColor,
                  start = Offset(0f, y),
                  end = Offset(size.width, y),
                  strokeWidth = strokeWidth,
                  pathEffect = dashPath,
                )
              }
              .clickable { showValueSeriesBottomSheet = true }
          } else {
            Modifier
          }
        AnimatedContent(value) { curValue ->
          Text(
            String.format(locale, "%.2f", curValue),
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = textModifier,
          )
        }
        AnimatedContent(
          baselineValue,
          contentAlignment = Alignment.CenterStart,
          transitionSpec = { fadeIn() togetherWith fadeOut() },
        ) { curBaselineValue ->
          if (curBaselineValue != null && abs(curBaselineValue) > 1e-6) {
            val pct = (value - curBaselineValue) / curBaselineValue * 100
            val strPct = String.format(locale, "%.1f", abs(pct))
            val sign = if (pct >= 0.0) "+" else "-"
            val betterSign = if (lessIsBetter) "-" else "+"
            val color =
              if (sign == betterSign) {
                MaterialTheme.customColors.successColor
              } else {
                MaterialTheme.customColors.errorTextColor
              }
            Text("$sign$strPct%", style = MaterialTheme.typography.labelMedium, color = color)
          }
        }
      }
      if (unit.isNotEmpty()) {
        Text(
          unit,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
      }
    }
  }

  if (showValueSeriesBottomSheet) {
    BenchmarkValueSeriesViewer(
      title = "$label ($unit)",
      valueSeries = valueSeries,
      onDismiss = { showValueSeriesBottomSheet = false },
    )
  }
}
