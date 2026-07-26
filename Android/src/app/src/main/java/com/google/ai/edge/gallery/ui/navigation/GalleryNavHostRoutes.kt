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

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.isLegacyTasks
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.benchmark.BenchmarkScreen
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.notifications.NotificationsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGGalleryNavHostRoutes"

/**
 * Every destination below the home screen and the model list: the model page, the direct
 * task routes that bypass the model picker, the global model manager, notifications, and
 * benchmark creation.
 *
 * Split out of [GalleryNavHost] so that file stays focused on NavHost setup and lifecycle
 * wiring. The caller's local UI flags are threaded through as accessors rather than captured,
 * so their scope and update semantics are unchanged.
 */
internal fun NavGraphBuilder.galleryTaskAndModelRoutes(
  navController: NavHostController,
  modelManagerViewModel: ModelManagerViewModel,
  lastNavigatedModelName: () -> String,
  setLastNavigatedModelName: (String) -> Unit,
  setModelListAnimationEnabled: (Boolean) -> Unit,
  setHomeScreenAnimationEnabled: (Boolean) -> Unit,
) {
  // Model page.
  composable(
    route = "$ROUTE_MODEL/{taskId}/{modelName}?query={query}",
    arguments =
      listOf(
        navArgument("taskId") { type = NavType.StringType },
        navArgument("modelName") { type = NavType.StringType },
        navArgument("query") {
          type = NavType.StringType
          nullable = true
          defaultValue = null
        },
      ),
    enterTransition = { slideEnter() },
    exitTransition = { slideExit() },
  ) { backStackEntry ->
    val modelName = backStackEntry.arguments?.getString("modelName") ?: ""
    val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
    val queryParam = backStackEntry.arguments?.getString("query")
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    modelManagerViewModel.getModelByName(name = modelName)?.let { initialModel ->
      if (lastNavigatedModelName() != modelName) {
        modelManagerViewModel.selectModel(initialModel)
        setLastNavigatedModelName(modelName)
      }

      val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
      if (customTask != null) {
        if (isLegacyTasks(customTask.task.id)) {
          customTask.MainScreen(
            data =
              CustomTaskDataForBuiltinTask(
                modelManagerViewModel = modelManagerViewModel,
                onNavUp = {
                  setModelListAnimationEnabled(false)
                  setLastNavigatedModelName("")
                  navController.navigateUp()
                },
                initialQuery = queryParam,
              )
          )
        } else {
          var disableAppBarControls by remember { mutableStateOf(false) }
          var hideTopBar by remember { mutableStateOf(false) }
          var customNavigateUpCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
          CustomTaskScreen(
            task = customTask.task,
            modelManagerViewModel = modelManagerViewModel,
            onNavigateUp = {
              if (customNavigateUpCallback != null) {
                customNavigateUpCallback?.invoke()
              } else {
                setModelListAnimationEnabled(false)
                setLastNavigatedModelName("")
                navController.navigateUp()

                // clean up all models.
                for (curModel in customTask.task.models) {
                  val instanceToCleanUp = curModel.instance
                  scope.launch(Dispatchers.Default) {
                    modelManagerViewModel.cleanupModel(
                      context = context,
                      task = customTask.task,
                      model = curModel,
                      instanceToCleanUp = instanceToCleanUp,
                    )
                  }
                }
              }
            },
            disableAppBarControls = disableAppBarControls,
            hideTopBar = hideTopBar,
            useThemeColor = customTask.task.useThemeColor,
          ) { bottomPadding ->
            customTask.MainScreen(
              data =
                CustomTaskData(
                  modelManagerViewModel = modelManagerViewModel,
                  bottomPadding = bottomPadding,
                  setAppBarControlsDisabled = { disableAppBarControls = it },
                  setTopBarVisible = { hideTopBar = !it },
                  setCustomNavigateUpCallback = { customNavigateUpCallback = it },
                )
            )
          }
        }
      }
    }
  }

  // BaoTranslate direct route — bypasses model picker & CustomTaskScreen
  // since BaoTranslate manages its own model downloads via BaoTranslateModelManager.
  composable(
    route = ROUTE_BAO_TRANSLATE,
    enterTransition = { slideEnter() },
    exitTransition = { slideExit() },
  ) {
    val customTask =
      modelManagerViewModel.getCustomTaskByTaskId(BuiltInTaskId.BAO_TRANSLATE)
    val innerPadding = WindowInsets.statusBars.asPaddingValues()
    BackHandler {
      setHomeScreenAnimationEnabled(false)
      navController.navigateUp()
    }
    Scaffold(
      topBar = {},
      containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
    ) { padding ->
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        customTask?.MainScreen(
          data = CustomTaskData(
            modelManagerViewModel = modelManagerViewModel,
          )
        )
      }
    }
  }

  // LibreDrop direct route — bypasses model picker since it has no models
  composable(
    route = ROUTE_LIBRE_DROP,
    enterTransition = { slideEnter() },
    exitTransition = { slideExit() },
  ) {
    val customTask = modelManagerViewModel.getCustomTaskByTaskId(BuiltInTaskId.LIBRE_DROP)
    BackHandler {
      setHomeScreenAnimationEnabled(false)
      navController.navigateUp()
    }
    Scaffold(
      topBar = {},
      containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
    ) { padding ->
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        customTask?.MainScreen(
          data = CustomTaskData(
            modelManagerViewModel = modelManagerViewModel,
          )
        )
      }
    }
  }

  // Global model manager page.
  composable(
    route = ROUTE_MODEL_MANAGER,
    enterTransition = {
      if (
        initialState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
          initialState.destination.route?.startsWith(ROUTE_MODEL) == true
      ) {
        null
      } else {
        slideUpEnter()
      }
    },
    exitTransition = {
      if (
        targetState.destination.route?.startsWith(ROUTE_BENCHMARK) == true ||
          targetState.destination.route?.startsWith(ROUTE_MODEL) == true
      ) {
        null
      } else {
        slideDownExit()
      }
    },
  ) { backStackEntry ->
    GlobalModelManager(
      viewModel = modelManagerViewModel,
      navigateUp = {
        setHomeScreenAnimationEnabled(false)
        navController.navigateUp()
      },
      onModelSelected = { task, model ->
        navController.navigate(modelRoute(taskId = task.id, model = model))
      },
      onBenchmarkClicked = { model ->
        firebaseAnalytics?.logEvent(
          GalleryEvent.CAPABILITY_SELECT.id,
          Bundle().apply { putString("capability_name", "benchmark_${model.name}") },
        )
        if (model.supportsBenchmark()) {
          navController.navigate(benchmarkRoute(model))
        } else {
          BaoLog.w(TAG, "Ignoring benchmark request for unsupported model: ${model.name}")
        }
      },
    )
  }

  // Notifications page.
  composable(
    route = ROUTE_NOTIFICATIONS,
    enterTransition = { slideUpEnter() },
    exitTransition = { slideDownExit() },
  ) {
    NotificationsScreen(navigateUp = { navController.navigateUp() })
  }

  // Benchmark creation page.
  composable(
    route = "$ROUTE_BENCHMARK/{modelName}",
    arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
    enterTransition = { slideEnter() },
    exitTransition = { slideExit() },
  ) { backStackEntry ->
    val modelName = backStackEntry.arguments?.getString("modelName") ?: ""

    modelManagerViewModel.getModelByName(name = modelName)?.let { model ->
      BenchmarkScreen(
        initialModel = model,
        modelManagerViewModel = modelManagerViewModel,
        onBackClicked = {
          setModelListAnimationEnabled(false)
          navController.navigateUp()
        },
      )
    }
  }
}
