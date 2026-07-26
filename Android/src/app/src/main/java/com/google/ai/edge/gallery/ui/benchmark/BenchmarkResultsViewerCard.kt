package com.google.ai.edge.gallery.ui.benchmark

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.Accordions
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun BenchmarkResultCard(
  index: Int,
  result: BenchmarkResultInfo,
  uiState: BenchmarkUiState,
  viewModel: BenchmarkViewModel,
  showLazyListPlacementAnimation: Boolean,
  totalFilteredResultsSize: Int,
  onDeleteRequest: (String) -> Unit,
  onAnimateItem: (Modifier) -> Modifier,
  onAnimateItemNullPlacement: (Modifier) -> Modifier,
) {
  val locale = LocalLocale.current.platformLocale
  var cardModifier = Modifier.clip(RoundedCornerShape(20.dp)).fillMaxWidth()
  if (showLazyListPlacementAnimation) {
    cardModifier = onAnimateItem(cardModifier)
  }
  result.benchmarkResult.llmResult?.let { llmResult ->
    val modelName = llmResult.baiscInfo.modelName
    Accordions(
      title = "$modelName · ${llmResult.baiscInfo.accelerator}",
      subtitle =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale)
          .format(Date(llmResult.baiscInfo.startMs)),
      boldTitle = true,
      expanded = result.expanded,
      onExpandedChange = { viewModel.setExpanded(id = result.id, expanded = it) },
      modifier = cardModifier,
      titleRowAction = {
        // A chip to toggle on/off baseline, used for set the comparison base.
        // Only visible when there are >2 results.
        if (totalFilteredResultsSize > 1) {
          FilterChip(
            onClick = { viewModel.setBaseline(id = result.id) },
            label = {
              Text(
                stringResource(R.string.baseline),
                style = MaterialTheme.typography.labelSmall,
              )
            },
            selected = result.id == uiState.baselineResult?.id,
            leadingIcon =
              if (result.id == uiState.baselineResult?.id) {
                {
                  Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).offset(x = 2.dp),
                  )
                }
              } else {
                null
              },
            modifier = Modifier.height(24.dp),
          )
        }
      },
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 2.dp),
      ) {
        // Basic info.
        Accordions(
          title = stringResource(R.string.basic_info),
          bgColor = MaterialTheme.colorScheme.surfaceContainerLow,
          expanded = result.basicInfoExpanded,
          onExpandedChange = {
            viewModel.setBasicInfoExpanded(id = result.id, expanded = it)
          },
          modifier = Modifier.clip(MaterialTheme.shapes.medium),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp),
          ) {
            StatRow(
              label = stringResource(R.string.benchmark_stat_model),
              value = llmResult.baiscInfo.modelName,
            )
            StatRow(
              label = stringResource(R.string.benchmark_stat_accelerator),
              value = llmResult.baiscInfo.accelerator,
            )
            StatRow(
              label = stringResource(R.string.benchmark_stat_prefill_tokens),
              value = "${llmResult.baiscInfo.prefillTokens}",
            )
            StatRow(
              label = stringResource(R.string.benchmark_stat_decode_tokens),
              value = "${llmResult.baiscInfo.decodeTokens}",
            )
            StatRow(
              label = stringResource(R.string.benchmark_stat_number_of_runs),
              value = "${llmResult.baiscInfo.numberOfRuns}",
            )
            StatRow(
              label = stringResource(R.string.benchmark_stat_app_version),
              value = llmResult.baiscInfo.appVersion,
            )
          }
        }

        // Stats
        val resources = LocalResources.current
        Accordions(
          title =
            "${stringResource(R.string.results)} (${resources.getQuantityString(
              R.plurals.runs,
              llmResult.baiscInfo.numberOfRuns,
              llmResult.baiscInfo.numberOfRuns,
            )})",
          bgColor = MaterialTheme.colorScheme.surfaceContainerLow,
          expanded = result.statsExpanded,
          onExpandedChange = {
            viewModel.setStatsExpanded(id = result.id, expanded = it)
          },
          modifier = Modifier.clip(MaterialTheme.shapes.medium),
          titleRowAction = {
            if (
              (result.benchmarkResult.llmResult?.baiscInfo?.numberOfRuns ?: 0) > 1
            ) {
              var showAggregationDropdown by remember { mutableStateOf(false) }
              // Aggregation method.
              Box {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier =
                    Modifier.clip(MaterialTheme.shapes.small)
                      .clickable { showAggregationDropdown = true }
                      .background(
                        MaterialTheme.colorScheme.surfaceContainerLowest
                      )
                      .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.small,
                      )
                      .padding(start = 8.dp, end = 0.dp)
                      .height(24.dp),
                ) {
                  Text(
                    result.aggregation.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                  )
                  Icon(
                    Icons.Rounded.ArrowDropDown,
                    modifier = Modifier.size(20.dp),
                    contentDescription = null,
                  )
                }
                DropdownMenu(
                  expanded = showAggregationDropdown,
                  onDismissRequest = { showAggregationDropdown = false },
                ) {
                  for (aggregation in Aggregation.entries) {
                    DropdownMenuItem(
                      text = { Text(aggregation.label) },
                      onClick = {
                        showAggregationDropdown = false
                        viewModel.setAggregation(
                          id = result.id,
                          aggregation = aggregation,
                        )
                      },
                    )
                  }
                }
              }
            }
          },
          hideTitleRowActionOnCollapse = true,
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 6.dp, top = 6.dp),
          ) {
            val baselineStats =
              uiState.baselineResult?.benchmarkResult?.llmResult?.stats
            ValueSeriesRow(
              label = "Prefill speed",
              valueSeries = llmResult.stats.prefillSpeed,
              aggregation = result.aggregation,
              unit = "tokens/sec",
              baselineValueSeries =
                if (result.id != uiState.baselineResult?.id) {
                  baselineStats?.prefillSpeed
                } else {
                  null
                },
              baselineAggregation =
                if (result.id != uiState.baselineResult?.id) {
                  uiState.baselineResult?.aggregation
                } else {
                  null
                },
            )
            ValueSeriesRow(
              label = "Decode speed",
              valueSeries = llmResult.stats.decodeSpeed,
              aggregation = result.aggregation,
              unit = "tokens/sec",
              baselineValueSeries =
                if (result.id != uiState.baselineResult?.id) {
                  baselineStats?.decodeSpeed
                } else {
                  null
                },
              baselineAggregation =
                if (result.id != uiState.baselineResult?.id) {
                  uiState.baselineResult?.aggregation
                } else {
                  null
                },
            )
            ValueSeriesRow(
              label = "Time to first token",
              valueSeries = llmResult.stats.timeToFirstToken,
              aggregation = result.aggregation,
              unit = "sec",
              baselineValueSeries =
                if (result.id != uiState.baselineResult?.id) {
                  baselineStats?.timeToFirstToken
                } else {
                  null
                },
              baselineAggregation =
                if (result.id != uiState.baselineResult?.id) {
                  uiState.baselineResult?.aggregation
                } else {
                  null
                },
              lessIsBetter = true,
            )
            StatRow(
              label = "First init time",
              value =
                String.format(
                  locale,
                  "%.2f",
                  llmResult.stats.firstInitTimeMs,
                ),
              unit = "ms",
              rawValue = llmResult.stats.firstInitTimeMs,
              baselineValue =
                if (result.id != uiState.baselineResult?.id) {
                  baselineStats?.firstInitTimeMs
                } else {
                  null
                },
              lessIsBetter = true,
            )
            if (llmResult.stats.nonFirstInitTimeMs.valueCount > 1) {
              ValueSeriesRow(
                label = "Steady init time",
                valueSeries = llmResult.stats.nonFirstInitTimeMs,
                aggregation = result.aggregation,
                unit = "ms",
                baselineValueSeries =
                  if (result.id != uiState.baselineResult?.id) {
                    baselineStats?.nonFirstInitTimeMs
                  } else {
                    null
                  },
                baselineAggregation =
                  if (result.id != uiState.baselineResult?.id) {
                    uiState.baselineResult?.aggregation
                  } else {
                    null
                  },
                lessIsBetter = true,
              )
            }
          }
        }

        // Buttons.
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.End,
          modifier = Modifier.fillMaxWidth(),
        ) {
          // Delete.
          OutlinedButton(
            onClick = { onDeleteRequest(result.id) },
            contentPadding = SMALL_BUTTON_CONTENT_PADDING,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
              Text(stringResource(R.string.delete))
            }
          }

          Spacer(modifier = Modifier.width(8.dp))

          // Copy
          val scope = rememberCoroutineScope()
          val clipboard = LocalClipboard.current
          Button(
            onClick = {
              scope.launch {
                // Copy csv to clipboard.
                val csv =
                  getBenchmarkResultCsv(
                    llmResult = llmResult,
                    aggregation = result.aggregation,
                  )
                val clipData =
                  ClipData.newPlainText("benchmark results for ${modelName}", csv)
                val clipEntry = ClipEntry(clipData = clipData)
                clipboard.setClipEntry(clipEntry = clipEntry)
              }
            },
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
              ),
            contentPadding = SMALL_BUTTON_CONTENT_PADDING,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
              )
              Text(
                stringResource(R.string.copy),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }
        }
      }
    }
  }
  if (index != totalFilteredResultsSize - 1) {
    Spacer(modifier = onAnimateItemNullPlacement(Modifier.height(12.dp)))
  }
}
