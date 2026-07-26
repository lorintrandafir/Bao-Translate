package com.google.ai.edge.gallery.ui.benchmark

import com.google.ai.edge.gallery.proto.LlmBenchmarkResult
import com.google.ai.edge.gallery.proto.ValueSeries

internal fun getBenchmarkResultCsv(
  llmResult: LlmBenchmarkResult,
  aggregation: Aggregation,
): String {
  val basicInfo = llmResult.baiscInfo
  val stats = llmResult.stats

  val header =
    listOf(
        "start time (ms)",
        "end time (ms)",
        "model name",
        "accelerator",
        "prefill tokens count",
        "decode tokens count",
        "runs count",
        "app version",
        "prefill speed (tokens/sec)",
        "decode speed (tokens/sec)",
        "time to first token (sec)",
        "first init time (ms)",
        "steady init time (ms)",
      )
      .joinToString(",")

  val data =
    listOf(
        basicInfo.startMs,
        basicInfo.endMs,
        basicInfo.modelName,
        basicInfo.accelerator,
        basicInfo.prefillTokens,
        basicInfo.decodeTokens,
        basicInfo.numberOfRuns,
        basicInfo.appVersion,
        getAggregationValue(stats.prefillSpeed, aggregation),
        getAggregationValue(stats.decodeSpeed, aggregation),
        getAggregationValue(stats.timeToFirstToken, aggregation),
        stats.firstInitTimeMs,
        getAggregationValue(stats.nonFirstInitTimeMs, aggregation),
      )
      .joinToString(",")

  return "$header\n$data"
}

internal fun getAggregationValue(
  valueSeries: ValueSeries,
  aggregation: Aggregation,
): Double {
  return when (aggregation) {
    Aggregation.AVG -> valueSeries.avg
    Aggregation.MEDIAN -> valueSeries.medium
    Aggregation.MIN -> valueSeries.min
    Aggregation.MAX -> valueSeries.max
  }
}
