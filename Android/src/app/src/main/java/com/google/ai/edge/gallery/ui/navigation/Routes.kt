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

package com.google.ai.edge.gallery.ui.navigation

import android.net.Uri
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType

internal const val ROUTE_HOMESCREEN = "homepage"
internal const val ROUTE_MODEL_LIST = "model_list"
internal const val ROUTE_MODEL = "route_model"
internal const val ROUTE_BENCHMARK = "benchmark"
internal const val ROUTE_MODEL_MANAGER = "model_manager"
internal const val ROUTE_NOTIFICATIONS = "notifications"
internal const val ROUTE_BAO_TRANSLATE = "bao_translate"
internal const val ROUTE_LIBRE_DROP = "libre_drop"

internal fun Model.supportsBenchmark(): Boolean = isLlm && runtimeType == RuntimeType.LITERT_LM

internal fun modelRoute(taskId: String, model: Model, query: String? = null): String {
  val base = "$ROUTE_MODEL/${Uri.encode(taskId)}/${Uri.encode(model.name)}"
  return if (!query.isNullOrEmpty()) "$base?query=${Uri.encode(query)}" else base
}

internal fun benchmarkRoute(model: Model): String = "$ROUTE_BENCHMARK/${Uri.encode(model.name)}"
