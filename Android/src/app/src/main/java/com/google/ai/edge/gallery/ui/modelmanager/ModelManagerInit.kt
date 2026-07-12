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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.litertlm.Contents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGModelManagerInit"

internal fun performInitializeModel(
  vm: ModelManagerViewModel,
  context: Context,
  task: Task,
  model: Model,
  force: Boolean = false,
  onDone: () -> Unit = {},
  onError: (String) -> Unit = {},
) {
  vm.viewModelScope.launch(Dispatchers.Default) {
    if (
      !force &&
        vm.uiState.value.modelInitializationStatus[model.name]?.status ==
          ModelInitializationStatusType.INITIALIZED
    ) {
      BaoLog.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
      return@launch
    }

    if (model.initializing) {
      model.cleanUpAfterInit = false
      BaoLog.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
      return@launch
    }

    performCleanupModel(vm, context = context, task = task, model = model)

    BaoLog.d(TAG, "Initializing model '${model.name}'...")
    model.initializing = true
    vm.updateModelInitializationStatus(
      model = model,
      status = ModelInitializationStatusType.INITIALIZING,
    )

    val onDoneFn: (error: String) -> Unit = { error ->
      model.initializing = false
      if (model.instance != null) {
        BaoLog.d(TAG, "Model '${model.name}' initialized successfully")
        vm.updateModelInitializationStatus(
          model = model,
          status = ModelInitializationStatusType.INITIALIZED,
        )
        if (model.cleanUpAfterInit) {
          BaoLog.d(TAG, "Model '${model.name}' needs cleaning up after init.")
          performCleanupModel(vm, context = context, task = task, model = model)
        }
        onDone()
      } else if (error.isNotEmpty()) {
        BaoLog.d(TAG, "Model '${model.name}' failed to initialize")
        vm.updateModelInitializationStatus(
          model = model,
          status = ModelInitializationStatusType.ERROR,
          error = error,
        )
        onError(error)
      }
    }

    val systemPrompt = SystemPromptHelper.getEffectiveSystemPrompt(vm.systemPromptRepository, task)
    vm.getCustomTaskByTaskId(id = task.id)
      ?.initializeModelFn(
        context = context,
        coroutineScope = vm.viewModelScope,
        model = model,
        systemInstruction = Contents.of(systemPrompt),
        onDone = onDoneFn,
      )
  }
}

internal fun performCleanupModel(
  vm: ModelManagerViewModel,
  context: Context,
  task: Task,
  model: Model,
  instanceToCleanUp: Any? = model.instance,
  onDone: () -> Unit = {},
) {
  if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
    BaoLog.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
    onDone()
    return
  }

  if (model.instance != null) {
    model.cleanUpAfterInit = false
    BaoLog.d(TAG, "Cleaning up model '${model.name}'...")
    val onDoneFn: () -> Unit = {
      model.instance = null
      model.initializing = false
      vm.updateModelInitializationStatus(
        model = model,
        status = ModelInitializationStatusType.NOT_INITIALIZED,
      )
      BaoLog.d(TAG, "Clean up model '${model.name}' done")
      onDone()
    }
    vm.getCustomTaskByTaskId(id = task.id)
      ?.cleanUpModelFn(
        context = context,
        coroutineScope = vm.viewModelScope,
        model = model,
        onDone = onDoneFn,
      )
  } else {
    if (model.initializing) {
      BaoLog.d(
        TAG,
        "Model '${model.name}' is still initializing.. Will clean up after it is done initializing",
      )
      model.cleanUpAfterInit = true
    }
  }
}

internal fun ModelManagerViewModel.checkAICoreModelStatuses() {
  viewModelScope.launch(Dispatchers.Main) {
    val aicoreModels =
      uiState.value.tasks
        .flatMap { it.models }
        .filter { it.runtimeType == RuntimeType.AICORE }
        .distinctBy { it.name }

    for (model in aicoreModels) {
      downloadModel(task = null, model = model)
    }
  }
}

internal fun ModelManagerViewModel.processPendingDownloads() {
  val vm = this
  downloadRepository.cancelAll {
    BaoLog.d(TAG, "All workers are cancelled.")

    vm.viewModelScope.launch(Dispatchers.Main) {
      val checkedModelNames = mutableSetOf<String>()
      val tokenStatusAndData = vm.getTokenStatusAndData()
      for (task in vm.uiState.value.tasks) {
        for (model in task.models) {
          if (checkedModelNames.contains(model.name)) {
            continue
          }

          val downloadStatus = vm.uiState.value.modelDownloadStatus[model.name]?.status
          if (downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED) {
            if (
              tokenStatusAndData.status == TokenStatus.NOT_EXPIRED &&
                tokenStatusAndData.data != null
            ) {
              model.accessToken = tokenStatusAndData.data.accessToken
            }
            BaoLog.d(TAG, "Sending a new download request for '${model.name}'")
            vm.downloadRepository.downloadModel(
              task = task,
              model = model,
              onStatusUpdated = vm::setDownloadStatus,
            )
          }

          checkedModelNames.add(model.name)
        }
      }
    }
  }
}
