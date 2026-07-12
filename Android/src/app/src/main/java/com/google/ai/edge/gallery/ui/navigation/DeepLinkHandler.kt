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

import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerUiState
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController

private const val TAG = "AGGalleryNavGraph"

@Composable
fun handleDeepLink(
  navController: NavHostController,
  modelManagerViewModel: ModelManagerViewModel,
  modelManagerUiState: ModelManagerUiState,
) {
  // Handle incoming intents for deep links
  val intent = androidx.activity.compose.LocalActivity.current?.intent
  val data = intent?.data
  var handledDeepLinkIntent by remember { mutableStateOf<android.content.Intent?>(null) }

  // Wait until the model manager has been initialized and the tasks are available.
  if (
    intent != null &&
      data != null &&
      handledDeepLinkIntent !== intent &&
      modelManagerUiState.tasks.isNotEmpty()
  ) {
    val uriStr = data.toString()
    val modelAllowlistFinished = !modelManagerUiState.loadingModelAllowlist
    var handled = false
    BaoLog.d(TAG, "navigation link clicked: $data")
    // 1. Precise model deep links: com.google.ai.edge.gallery://model/<taskId>/<modelName>
    if (uriStr.startsWith("com.google.ai.edge.gallery://model/")) {
      if (data.pathSegments.size >= 2) {
        val taskId = data.pathSegments.get(data.pathSegments.size - 2)
        val modelName = data.pathSegments.last()
        val queryStr = data.getQueryParameter("query")
        val model = modelManagerViewModel.getModelByName(name = modelName)
        if (model != null) {
          navController.navigate(modelRoute(taskId = taskId, model = model, query = queryStr))
          handled = true
        } else if (modelAllowlistFinished) {
          BaoLog.e(TAG, "No model found for deep link: $data")
          handled = true
        }
      } else {
        BaoLog.e(TAG, "Malformed deep link URI received: $data")
        handled = true
      }
    } else if (data.host == "benchmark") {
      val requestedModelName = data.pathSegments.lastOrNull()
      val model =
        if (requestedModelName.isNullOrBlank()) {
          modelManagerViewModel.getAllModels().firstOrNull { it.supportsBenchmark() }
        } else {
          modelManagerViewModel.getModelByName(name = requestedModelName)
        }
      if (model?.supportsBenchmark() == true) {
        navController.navigate(benchmarkRoute(model))
        handled = true
      } else if (modelAllowlistFinished) {
        BaoLog.e(TAG, "No benchmark-capable model found for deep link: $data")
        handled = true
      }
    } else if (uriStr == "com.google.ai.edge.gallery://global_model_manager") {
      navController.navigate(ROUTE_MODEL_MANAGER)
      handled = true
    } else {
      // 2. Dynamic task-level deep links: com.google.ai.edge.gallery://<taskId>
      val host = data.host
      if (host != null) {
        val queryStr = data.getQueryParameter("query")
        val task = modelManagerUiState.tasks.find { it.id == host }
        if (task != null) {
          // Pick the first successfully downloaded model or the default active model for this task
          val defaultModel =
            task.models.firstOrNull { model ->
              modelManagerUiState.modelDownloadStatus[model.name]?.status ==
                ModelDownloadStatusType.SUCCEEDED
            } ?: task.models.firstOrNull()

          if (defaultModel != null) {
            navController.navigate(
              modelRoute(taskId = task.id, model = defaultModel, query = queryStr)
            )
            handled = true
          } else if (modelAllowlistFinished) {
            BaoLog.e(TAG, "No available model found for task: $host")
            handled = true
          }
        } else if (modelAllowlistFinished) {
          BaoLog.e(TAG, "No task found for deep link: $data")
          handled = true
        }
      } else if (modelAllowlistFinished) {
        BaoLog.e(TAG, "Malformed deep link URI received: $data")
        handled = true
      }
    }

    if (handled) {
      handledDeepLinkIntent = intent
    }
  }
}
