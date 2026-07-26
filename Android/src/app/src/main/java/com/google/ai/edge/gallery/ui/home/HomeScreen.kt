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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.rememberDelayedAnimationProgress
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.Dimensions
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel,
  navigateToTaskScreen: (Task) -> Unit,
  onModelsClicked: () -> Unit,
  onNotificationsClicked: () -> Unit,
  enableAnimation: Boolean,
  modifier: Modifier = Modifier,
  gm4: Boolean = false,
) {
  val uiState by modelManagerViewModel.uiState.collectAsState()
  var showSettingsDialog by remember { mutableStateOf(false) }
  var showTosDialog by remember { mutableStateOf(!tosViewModel.getIsTosAccepted()) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  // Release gate for experimental/unverified demo tasks (e.g. Tiny Garden — a 270M FunctionGemma
  // gardening mini-game, author-labeled "responses may vary"). They stay available in debug builds
  // for development but are NOT shipped in the production task grid: experimental != released.
  // Reversible (drop this filter to ship them) and non-destructive (no task code removed).
  var tasks =
    if (BuildConfig.DEBUG) uiState.tasks else uiState.tasks.filterNot { it.experimental }

  val categoryMap: Map<String, CategoryInfo> =
    remember(tasks) { tasks.associateBy { it.category.id }.mapValues { it.value.category } }
  val sortedCategories =
    remember(categoryMap) {
      categoryMap.keys
        .toList()
        .sortedWith { a, b ->
          val indexA = PREDEFINED_CATEGORY_ORDER.indexOf(a)
          val indexB = PREDEFINED_CATEGORY_ORDER.indexOf(b)
          // Check if both categories are in the predefined order
          if (indexA != -1 && indexB != -1) {
            indexA.compareTo(indexB)
          }
          // Check if only category 'a' is in the predefined order
          else if (indexA != -1) {
            -1
          }
          // Check if only category 'b' is in the predefined order
          else if (indexB != -1) {
            1
          }
          // If neither is in the predefined order, sort by label
          else {
            val ca = categoryMap[a]
            val cb = categoryMap[b]
            if (ca == null || cb == null) {
              0
            } else {
              val caLabel = getCategoryLabel(context = context, category = ca)
              val cbLabel = getCategoryLabel(context = context, category = cb)
              caLabel.compareTo(cbLabel)
            }
          }
        }
        .mapNotNull { categoryMap[it] }
    }

  // Show home screen content when TOS has been accepted.
  if (!showTosDialog) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val requestPermissionLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted: Boolean ->
        if (isGranted) {
          // FCM SDK (and your app) can post notifications.
        }
      }

    LaunchedEffect(Unit) {
      delay(2000)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
          requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
    }

    // Close the menu when back button is pressed.
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
      drawerState = drawerState,
      drawerContent = {
        ModalDrawerSheet {
          Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
            Row(modifier = Modifier.fillMaxWidth()) {
              SquareDrawerItem(
                label = stringResource(R.string.drawer_settings_label),
                description = stringResource(R.string.drawer_settings_description),
                icon = Icons.Rounded.Settings,
                onClick = {
                  showSettingsDialog = true
                  scope.launch { drawerState.close() }
                },
                modifier = Modifier.weight(1f),
                iconBrush =
                  linearGradient(
                    colors =
                      listOf(
                        MaterialTheme.customColors.taskBgGradientColors[2][0],
                        MaterialTheme.customColors.taskBgGradientColors[2][1],
                      )
                  ),
              )
              Spacer(modifier = Modifier.width(Dimensions.Spacing.medium))
              SquareDrawerItem(
                label = stringResource(R.string.drawer_models_label),
                description = stringResource(R.string.drawer_models_description),
                icon = Icons.AutoMirrored.Rounded.ListAlt,
                onClick = {
                  scope.launch { drawerState.close() }
                  scope.launch {
                    delay(50)
                    onModelsClicked()
                  }
                },
                modifier = Modifier.weight(1f),
                iconBrush =
                  linearGradient(
                    colors =
                      listOf(
                        MaterialTheme.customColors.taskBgGradientColors[1][0],
                        MaterialTheme.customColors.taskBgGradientColors[1][1],
                      )
                  ),
              )
            }
            Spacer(modifier = Modifier.height(Dimensions.Spacing.medium))
            Row(modifier = Modifier.fillMaxWidth()) {
              SquareDrawerItem(
                label = stringResource(R.string.drawer_notifications_label),
                description = stringResource(R.string.drawer_notifications_description),
                icon = Icons.Rounded.Notifications,
                onClick = {
                  scope.launch { drawerState.close() }
                  scope.launch {
                    delay(50)
                    onNotificationsClicked()
                  }
                },
                modifier = Modifier.weight(1f),
                iconBrush =
                  linearGradient(
                    colors =
                      listOf(
                        MaterialTheme.customColors.taskBgGradientColors[3][0],
                        MaterialTheme.customColors.taskBgGradientColors[3][1],
                      )
                  ),
              )
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      },
      gesturesEnabled = drawerState.isOpen,
    ) {
      Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
          // Top bar animation:
          //
          // Fade in and move down at the same time.
          val progress =
            if (!enableAnimation) 1f
            else
              rememberDelayedAnimationProgress(
                initialDelay = ANIMATION_INIT_DELAY - 50,
                animationDurationMs = TOP_APP_BAR_ANIMATION_DURATION,
                animationLabel = "top bar",
              )
          Box(
            modifier =
              Modifier.graphicsLayer {
                alpha = progress
                translationY = (-Dimensions.Home.entranceOffset * (1 - progress)).toPx()
              }
          ) {
            GalleryTopAppBar(
              title = stringResource(HomeScreenDestination.titleRes),
              leftAction =
                AppBarAction(
                  actionType = AppBarActionType.MENU,
                  actionFn = {
                    scope.launch { drawerState.apply { if (isClosed) open() else close() } }
                  },
                ),
            )
          }
        },
      ) { innerPadding ->
        // Outer box for coloring the background edge to edge.
        Box(
          contentAlignment = Alignment.TopCenter,
          modifier =
            Modifier.fillMaxSize()
              .background(
                if (gm4) {
                  MaterialTheme.colorScheme.surface
                } else {
                  MaterialTheme.colorScheme.surfaceContainer
                }
              ),
        ) {
          // Non-blocking error banner for allowlist fetch failure.
          // Positioned outside scrollable area so it stays visible.
          if (uiState.loadingModelAllowlistError.isNotEmpty()) {
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .padding(
                  start = Dimensions.Spacing.medium,
                  end = Dimensions.Spacing.medium,
                  top = innerPadding.calculateTopPadding() + Dimensions.Spacing.small
                ),
              colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
              ),
            ) {
              Row(
                modifier = Modifier.padding(Dimensions.Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
              ) {
                Icon(
                  Icons.Rounded.Error,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.size(Dimensions.Icon.medium),
                )
                Text(
                  text = stringResource(R.string.error_check_internet),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { modelManagerViewModel.loadModelAllowlist() }) {
                  Text(stringResource(R.string.retry))
                }
                TextButton(onClick = { modelManagerViewModel.clearLoadModelAllowlistError() }) {
                  Text(stringResource(R.string.cancel))
                }
              }
            }
          }

          // Inner box to hold content.
          Box(
            contentAlignment = Alignment.TopCenter,
            modifier =
              Modifier.fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .verticalScroll(rememberScrollState()),
          ) {
            // Background star at top.
            if (gm4) {
              val progress =
                if (!enableAnimation) {
                  1f
                } else {
                  rememberDelayedAnimationProgress(
                    initialDelay = ANIMATION_INIT_DELAY,
                    animationDurationMs = 2000,
                    animationLabel = "bg star",
                  )
                }
              val screenWidth =
                with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
              val targetWidth = screenWidth * 1.5f
              Image(
                painter = painterResource(id = R.drawable.bg_star),
                contentDescription = null,
                modifier =
                  Modifier.requiredWidth(targetWidth)
                    .blur(
                      radius = Dimensions.Home.backgroundStarBlur,
                      edgeTreatment = BlurredEdgeTreatment.Unbounded,
                    )
                    .offset(x = screenWidth * 0.25f, y = -screenWidth * 0.1f)
                    .graphicsLayer {
                      rotationZ = (1f - progress) * 40f
                      scaleX = 0.4f + 0.6f * progress
                      scaleY = 0.4f + 0.6f * progress
                      alpha = progress * 2f
                    },
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(MaterialTheme.customColors.bgStarColor),
              )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
              var selectedCategoryIndex by remember { mutableIntStateOf(0) }

              // App title and intro text.
              Column(
                modifier =
                  Modifier.padding(
                      horizontal =
                        if (gm4) Dimensions.Spacing.large
                        else Dimensions.Home.heroHorizontalPadding,
                      vertical =
                        if (gm4) 0.dp
                        else Dimensions.Home.heroVerticalPadding,
                    )
                    .padding(top = Dimensions.Spacing.large, bottom = Dimensions.Spacing.medium)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(Dimensions.Spacing.small),
              ) {
                if (gm4) {
                  AppTitleGm4(enableAnimation = enableAnimation)
                } else {
                  AppTitle(enableAnimation = enableAnimation)
                }
                IntroText(enableAnimation = enableAnimation, gm4 = gm4)
                if (gm4) {
                  TryGm4IntroText(enableAnimation = enableAnimation)
                }
              }

              // Tab header for categories.
              //
              // synchronizes the `pagerState` and the `selectedCategoryIndex` to ensure that
              //  both the tab header and the task list always show the correct category and page.
              val pagerState = rememberPagerState(pageCount = { sortedCategories.size })
              LaunchedEffect(pagerState.settledPage) {
                selectedCategoryIndex = pagerState.settledPage
              }
              if (sortedCategories.size > 1) {
                CategoryTabHeader(
                  sortedCategories = sortedCategories,
                  selectedIndex = selectedCategoryIndex,
                  enableAnimation = enableAnimation,
                  onCategorySelected = { index ->
                    selectedCategoryIndex = index
                    scope.launch { pagerState.animateScrollToPage(page = index) }
                  },
                )
              }

              // Task list in a horizontal pager. Each page shows the list of tasks for the
              // category.
              val grid = gm4
              TaskList(
                modelManagerViewModel = modelManagerViewModel,
                pagerState = pagerState,
                sortedCategories = sortedCategories,
                tasksByCategories = uiState.tasksByCategory,
                enableAnimation = enableAnimation,
                navigateToTaskScreen = navigateToTaskScreen,
                gm4 = gm4,
                grid = grid,
              )

              Spacer(modifier = Modifier.height(innerPadding.calculateBottomPadding() + Dimensions.Spacing.smd))
            }
          }

          // Gradient overlay at the bottom.
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .height(innerPadding.calculateBottomPadding())
                .background(
                  Brush.verticalGradient(
                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer)
                  )
                )
                .align(Alignment.BottomCenter)
          )
        }
      }
    }
  }

  // Show TOS dialog for users to accept.
  if (showTosDialog) {
    AppTosDialog(
      onTosAccepted = {
        showTosDialog = false
        tosViewModel.acceptTos()
      }
    )
  }

  // Settings dialog.
  if (showSettingsDialog) {
    SettingsDialog(
      curThemeOverride = modelManagerViewModel.readThemeOverride(),
      modelManagerViewModel = modelManagerViewModel,
      onDismissed = { showSettingsDialog = false },
    )
  }
}
