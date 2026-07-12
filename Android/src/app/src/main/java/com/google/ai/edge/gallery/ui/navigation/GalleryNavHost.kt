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
import com.google.ai.edge.gallery.common.BaoLog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.isLegacyTasks
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.benchmark.BenchmarkScreen
import com.google.ai.edge.gallery.ui.home.HomeScreen
import com.google.ai.edge.gallery.ui.home.PromoScreenGm4
import com.google.ai.edge.gallery.ui.modelmanager.GlobalModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.notifications.NotificationsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGGalleryNavGraph"

/** Navigation routes. */
@Composable
fun GalleryNavHost(
  navController: NavHostController,
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  var showModelManager by remember { mutableStateOf(false) }
  var pickedTask by remember { mutableStateOf<Task?>(null) }
  var enableHomeScreenAnimation by remember { mutableStateOf(true) }
  var enableModelListAnimation by remember { mutableStateOf(true) }
  var lastNavigatedModelName = remember { "" }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()

  // Track whether app is in foreground.
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> {
          modelManagerViewModel.setAppInForeground(foreground = true)
        }
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_PAUSE -> {
          modelManagerViewModel.setAppInForeground(foreground = false)
        }
        else -> Unit
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  NavHost(
    navController = navController,
    startDestination = ROUTE_HOMESCREEN,
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
  ) {
    // Home screen.
    composable(route = ROUTE_HOMESCREEN) {
      // Create a state to trigger PromoScreen fade in animation.
      val promoId = "gm4"
      Box(modifier = modifier.fillMaxSize()) {
        var promoDismissed by remember { mutableStateOf(false) }

        val homeScreenContent: @Composable () -> Unit = {
          HomeScreen(
            modelManagerViewModel = modelManagerViewModel,
            tosViewModel = hiltViewModel(),
            enableAnimation = enableHomeScreenAnimation,
            navigateToTaskScreen = { task ->
              pickedTask = task
              enableModelListAnimation = true
              val route =
                if (task.id == BuiltInTaskId.BAO_TRANSLATE) ROUTE_BAO_TRANSLATE
                else ROUTE_MODEL_LIST
              navController.navigate(route)
              firebaseAnalytics?.logEvent(
                GalleryEvent.CAPABILITY_SELECT.id,
                Bundle().apply { putString("capability_name", task.id) },
              )
            },
            onModelsClicked = { navController.navigate(ROUTE_MODEL_MANAGER) },
            onNotificationsClicked = { navController.navigate(ROUTE_NOTIFICATIONS) },
            gm4 = false,
          )
        }

        // Show home page directly if promo has been viewed.
        if (modelManagerViewModel.dataStoreRepository.hasViewedPromo(promoId = promoId)) {
          homeScreenContent()
        }
        // If the promo has not been viewed, show promo screen first.
        else {
          AnimatedContent(
            targetState = promoDismissed,
            label = "PromoToHome",
            transitionSpec = { fadeIn() togetherWith fadeOut() },
          ) { dismissed ->
            if (dismissed) {
              homeScreenContent()
            } else {
              var startAnimation by remember { mutableStateOf(false) }
              LaunchedEffect(Unit) {
                delay(0L)
                startAnimation = true
              }
              AnimatedVisibility(
                visible = startAnimation,
                enter = scaleIn(initialScale = 1.05f, animationSpec = tween(durationMillis = 1000)),
              ) {
                PromoScreenGm4(
                  onDismiss = {
                    modelManagerViewModel.markPromoViewed(promoId = promoId)
                    promoDismissed = true
                  }
                )
              }
            }
          }
        }
      }
    }

    // Model list.
    composable(
      route = ROUTE_MODEL_LIST,
      enterTransition = {
        if (initialState.destination.route == ROUTE_HOMESCREEN) {
          slideEnter()
        } else {
          EnterTransition.None
        }
      },
      exitTransition = {
        if (targetState.destination.route == ROUTE_HOMESCREEN) {
          slideExit()
        } else {
          ExitTransition.None
        }
      },
    ) {
      pickedTask?.let {
        ModelManager(
          viewModel = modelManagerViewModel,
          task = it,
          enableAnimation = enableModelListAnimation,
          onModelClicked = { model ->
            navController.navigate(modelRoute(taskId = it.id, model = model))
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
          navigateUp = {
            enableHomeScreenAnimation = false
            navController.navigateUp()
          },
        )
      }
    }

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
        if (lastNavigatedModelName != modelName) {
          modelManagerViewModel.selectModel(initialModel)
          lastNavigatedModelName = modelName
        }

        val customTask = modelManagerViewModel.getCustomTaskByTaskId(id = taskId)
        if (customTask != null) {
          if (isLegacyTasks(customTask.task.id)) {
            customTask.MainScreen(
              data =
                CustomTaskDataForBuiltinTask(
                  modelManagerViewModel = modelManagerViewModel,
                  onNavUp = {
                    enableModelListAnimation = false
                    lastNavigatedModelName = ""
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
                  enableModelListAnimation = false
                  lastNavigatedModelName = ""
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
        enableHomeScreenAnimation = false
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
          enableHomeScreenAnimation = false
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
            enableModelListAnimation = false
            navController.navigateUp()
          },
        )
      }
    }
  }

  handleDeepLink(navController, modelManagerViewModel, modelManagerUiState)
}
