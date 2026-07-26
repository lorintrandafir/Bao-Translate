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
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.common.LenientJson
import com.google.ai.edge.gallery.common.ProjectConfig
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.common.isAICoreSupported
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import com.google.ai.edge.gallery.data.Task
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGModelManagerAllowlist"
private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val MODEL_ALLOWLIST_TEST_FILENAME = "model_allowlist_test.json"
private const val ALLOWLIST_BASE_URL = BuildConfig.MODEL_ALLOWLIST_BASE_URL

private const val TEST_MODEL_ALLOW_LIST = ""

/**
 * Loads the model allowlist from the network or disk, converts it to Model objects, associates them
 * with their tasks, and updates the UI state.
 */
internal fun performLoadModelAllowlist(vm: ModelManagerViewModel) {
  vm._uiState.update { it.copy(loadingModelAllowlist = true, loadingModelAllowlistError = "") }

  vm.viewModelScope.launch(Dispatchers.IO) {
    runCatching {

      // Clear existing allowlist models.
      vm._allowlistModels.clear()

      // Load model allowlist json.
      var modelAllowlist: ModelAllowlist? = null

      // Try to read the test allowlist first.
      BaoLog.d(TAG, "Loading test model allowlist.")
      modelAllowlist = vm.readModelAllowlistFromDisk(fileName = MODEL_ALLOWLIST_TEST_FILENAME)

      // Local test only.
      if (TEST_MODEL_ALLOW_LIST.isNotEmpty()) {
        BaoLog.d(TAG, "Loading local model allowlist for testing.")
        runCatching {
            modelAllowlist = LenientJson.decodeFromString<ModelAllowlist>(TEST_MODEL_ALLOW_LIST)
          }
          .onFailure { e -> BaoLog.e(TAG, "Failed to parse local test json", e) }
      }

      if (modelAllowlist == null) {
        // Load from github.
        var version = ProjectConfig.versionName.replace(".", "_")
        val url = getAllowlistUrl(version)

        BaoLog.d(TAG, "Loading model allowlist from internet. Url: $url")
        val data = getJsonResponse<ModelAllowlist>(url = url)
        modelAllowlist = data?.jsonObj

        if (modelAllowlist == null) {
          BaoLog.w(TAG, "Failed to load model allowlist from internet. Trying to load it from disk")
          modelAllowlist = vm.readModelAllowlistFromDisk()
        } else {
          BaoLog.d(TAG, "Done: loading model allowlist from internet")
          vm.saveModelAllowlistToDisk(modelAllowlistContent = data.textContent)
        }
      }

      if (modelAllowlist == null) {
        val curTasks = vm.getActiveCustomTasks().map { it.task }
        vm.processTasks()
        val errorFallbackState =
          vm.createUiState()
            .copy(
              loadingModelAllowlist = false,
              loadingModelAllowlistError = "Failed to load model list",
              tasks = curTasks,
              tasksByCategory = vm.groupTasksByCategory(),
            )
        vm._uiState.update { errorFallbackState }
        return@launch
      }

      BaoLog.d(TAG, "Allowlist: $modelAllowlist")

      val isAICoreAvailable by lazy {
        // Build a fast-lookup set of all supported device models.
        // This extracts the models from all allowed groups, flattens them into a single stream,
        // lowercases them for case-insensitive matching, and stores them in a Set.
        val allowedDeviceModelsSet =
          modelAllowlist.aicoreRequirements
            ?.allowedDeviceGroups
            ?.asSequence()
            ?.flatMap { it.deviceModels }
            ?.map { it.lowercase() }
            ?.toSet()
        isAICoreSupported(allowedDeviceModelsSet)
      }

      // Convert models in the allowlist.
      val curTasks = vm.getActiveCustomTasks().map { it.task }
      val nameToModel = mutableMapOf<String, Model>()
      for (allowedModel in modelAllowlist.models) {
        if (allowedModel.disabled == true) {
          continue
        }

        if (allowedModel.runtimeType == RuntimeType.AICORE && !isAICoreAvailable) {
          continue
        }

        // Ignore the allowedModel if its accelerator is only npu and this device's soc is not in
        // its socToModelFiles.
        val accelerators = allowedModel.defaultConfig.accelerators ?: ""
        val acceleratorList = accelerators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (acceleratorList.size == 1 && acceleratorList[0] == "npu") {
          val socToModelFiles = allowedModel.socToModelFiles
          if (socToModelFiles != null && !socToModelFiles.containsKey(SOC)) {
            BaoLog.d(
              TAG,
              "Ignoring model '${allowedModel.name}' because it's NPU-only and not supported on SOC: $SOC",
            )
            continue
          }
        }

        val model = allowedModel.toModel()
        vm._allowlistModels.add(model)
        nameToModel.put(model.name, model)
        for (taskType in allowedModel.taskTypes) {
          val task = curTasks.find { it.id == taskType }
          task?.models?.add(model)

          if (task?.id == BuiltInTaskId.LLM_TINY_GARDEN) {
            val newConfigs = model.configs.toMutableList()
            newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
            model.configs = newConfigs
          }
        }
      }

      // Find models from allowlist if a task's `modelNames` field is not empty.
      for (task in curTasks) {
        if (task.modelNames.isNotEmpty()) {
          for (modelName in task.modelNames) {
            val model = nameToModel[modelName]
            if (model == null) {
              BaoLog.w(TAG, "Model '${modelName}' in task '${task.label}' not found in allowlist.")
              continue
            }
            task.models.add(model)
          }
        }
      }

      // Process all tasks.
      vm.processTasks()

      // Update UI state.
      val updatedState =
        vm.createUiState()
          .copy(
            loadingModelAllowlist = false,
            tasks = curTasks,
            tasksByCategory = vm.groupTasksByCategory(),
          )
      vm._uiState.update { updatedState }

      // Process pending downloads.
      vm.processPendingDownloads()

      // Wait for AICore models statuses and update download indicators
      vm.checkAICoreModelStatuses()
    
}.onFailure { e ->
      BaoLog.e(TAG, "Failed to load model allowlist", e)
      vm._uiState.update {
        it.copy(
          loadingModelAllowlist = false,
          loadingModelAllowlistError = "Failed to load model list: ${e.message}",
        )
      }
}
  }
}

internal fun performClearLoadModelAllowlistError(vm: ModelManagerViewModel) {
  vm.viewModelScope.launch(Dispatchers.IO) {
    val curTasks = vm.getActiveCustomTasks().map { it.task }
    vm.processTasks()
    val newState =
      vm.createUiState()
        .copy(
          loadingModelAllowlist = false,
          tasks = curTasks,
          loadingModelAllowlistError = "",
          tasksByCategory = vm.groupTasksByCategory(),
        )
    vm._uiState.update {
      newState
    }
  }
}

private fun ModelManagerViewModel.saveModelAllowlistToDisk(modelAllowlistContent: String) {
  runCatching {

    BaoLog.d(TAG, "Saving model allowlist to disk...")
    val file = File(externalFilesDir, MODEL_ALLOWLIST_FILENAME)
    file.writeText(modelAllowlistContent)
    BaoLog.d(TAG, "Done: saving model allowlist to disk.")
  
}.onFailure { e ->

    BaoLog.e(TAG, "failed to write model allowlist to disk", e)
  
}
}

private fun ModelManagerViewModel.readModelAllowlistFromDisk(
  fileName: String = MODEL_ALLOWLIST_FILENAME
): ModelAllowlist? {
  runCatching {

    BaoLog.d(TAG, "Reading model allowlist from disk: $fileName")
    val baseDir =
      if (fileName == MODEL_ALLOWLIST_TEST_FILENAME) File("/data/local/tmp") else externalFilesDir
    val file = File(baseDir, fileName)
    if (file.exists()) {
      val content = file.readText()
      BaoLog.d(TAG, "Model allowlist content from local file: $content")

      return LenientJson.decodeFromString<ModelAllowlist>(content)
    }
  
}.onFailure { e ->

    BaoLog.e(TAG, "failed to read model allowlist from disk", e)
    return null
  
}

  return null
}

private fun ModelManagerViewModel.isModelPartiallyDownloaded(model: Model): Boolean {
  if (model.localModelFilePathOverride.isNotEmpty()) {
    return false
  }

  // A model is partially downloaded when the tmp file exists.
  val tmpFilePath =
    model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
  return File(tmpFilePath).exists()
}

internal suspend fun ModelManagerViewModel.createUiState(): ModelManagerUiState {
  val modelDownloadStatus: MutableMap<String, ModelDownloadStatus> = mutableMapOf()
  val modelInstances: MutableMap<String, ModelInitializationStatus> = mutableMapOf()
  val tasks: MutableMap<String, Task> = mutableMapOf()
  val checkedModelNames = mutableSetOf<String>()
  for (customTask in getActiveCustomTasks()) {
    val task = customTask.task
    tasks.put(key = task.id, value = task)
    for (model in task.models) {
      if (checkedModelNames.contains(model.name)) {
        continue
      }
      modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
      modelInstances[model.name] =
        ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)
      checkedModelNames.add(model.name)
    }
  }

  // Load imported models.
  for (importedModel in dataStoreRepository.readImportedModels()) {
    BaoLog.d(TAG, "stored imported model: $importedModel")

    // Create model.
    val model = createModelFromImportedModelInfo(info = importedModel)

    // Add to task.
    tasks.get(key = BuiltInTaskId.LLM_CHAT)?.models?.add(model)
    tasks.get(key = BuiltInTaskId.LLM_PROMPT_LAB)?.models?.add(model)
    tasks.get(key = BuiltInTaskId.LLM_AGENT_CHAT)?.models?.add(model)
    if (model.llmSupportImage) {
      tasks.get(key = BuiltInTaskId.LLM_ASK_IMAGE)?.models?.add(model)
    }
    if (model.llmSupportAudio) {
      tasks.get(key = BuiltInTaskId.LLM_ASK_AUDIO)?.models?.add(model)
    }
    if (model.llmSupportTinyGarden) {
      tasks.get(key = BuiltInTaskId.LLM_TINY_GARDEN)?.models?.add(model)
      val newConfigs = model.configs.toMutableList()
      newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
      model.configs = newConfigs
      model.preProcess()
    }
    if (model.llmSupportMobileActions) {
      tasks.get(key = BuiltInTaskId.LLM_MOBILE_ACTIONS)?.models?.add(model)
    }

    // Update status.
    modelDownloadStatus[model.name] =
      ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = importedModel.fileSize,
        totalBytes = importedModel.fileSize,
      )
  }

  val textInputHistory = dataStoreRepository.readTextInputHistory()
  BaoLog.d(TAG, "text input history: $textInputHistory")

  BaoLog.d(TAG, "model download status: $modelDownloadStatus")
  return ModelManagerUiState(
    tasks = getActiveCustomTasks().map { it.task }.toList(),
    tasksByCategory = mapOf(),
    modelDownloadStatus = modelDownloadStatus,
    modelInitializationStatus = modelInstances,
    textInputHistory = textInputHistory,
  )
}

internal fun ModelManagerViewModel.createInitialUiState(): ModelManagerUiState {
  val tasks = getActiveCustomTasks().map { it.task }.toList()
  val tasksByCategory = tasks.groupBy { it.category.id }
  return ModelManagerUiState(
    tasks = tasks,
    tasksByCategory = tasksByCategory,
    modelDownloadStatus = mapOf(),
    modelInitializationStatus = mapOf(),
    loadingModelAllowlist = false,
  )
}

internal fun ModelManagerViewModel.groupTasksByCategory(): Map<String, List<Task>> {
  val tasks = getActiveCustomTasks().map { it.task }

  val categoryMap: Map<String, CategoryInfo> =
    tasks.associateBy { it.category.id }.mapValues { it.value.category }

  val groupedTasks = tasks.groupBy { it.category.id }
  val groupedSortedTasks: MutableMap<String, List<Task>> = mutableMapOf()
  // Sort the tasks in categories by pre-defined order. Sort other tasks by label.
  for (categoryId in groupedTasks.keys) {
    val sortedTasks =
      (groupedTasks[categoryId] ?: emptyList()).sortedWith { a, b ->
        if (categoryId == Category.LLM.id) {
          val order: List<String> =
            when (categoryId) {
              Category.LLM.id -> PREDEFINED_LLM_TASK_ORDER
              else -> listOf()
            }
          val indexA = order.indexOf(a.id)
          val indexB = order.indexOf(b.id)
          if (indexA != -1 && indexB != -1) {
            indexA.compareTo(indexB)
          } else if (indexA != -1) {
            -1
          } else if (indexB != -1) {
            1
          } else {
            val ca = categoryMap[a.id] ?: return@sortedWith 0
            val cb = categoryMap[b.id] ?: return@sortedWith 0
            val caLabel = getCategoryLabel(context = context, category = ca)
            val cbLabel = getCategoryLabel(context = context, category = cb)
            caLabel.compareTo(cbLabel)
          }
        } else {
          a.label.compareTo(b.label)
        }
      }
    for ((index, task) in sortedTasks.withIndex()) {
      task.index = index
    }
    groupedSortedTasks[categoryId] = sortedTasks
  }

  return groupedSortedTasks
}

private fun ModelManagerViewModel.getCategoryLabel(context: Context, category: CategoryInfo): String {
  val stringRes = category.labelStringRes
  val label = category.label
  if (stringRes != null) {
    return context.getString(stringRes)
  } else if (label != null) {
    return label
  }
  return context.getString(R.string.category_unlabeled)
}

/**
 * Retrieves the download status of a model.
 *
 * This function determines the download status of a given model by checking if it's fully
 * downloaded, partially downloaded, or not downloaded at all. It also retrieves the received and
 * total bytes for partially downloaded models.
 */
private fun ModelManagerViewModel.getModelDownloadStatus(model: Model): ModelDownloadStatus {
  BaoLog.d(TAG, "Checking model ${model.name} download status...")

  if (model.localFileRelativeDirPathOverride.isNotEmpty()) {
    BaoLog.d(TAG, "Model has localFileRelativeDirPathOverride set. Set status to SUCCEEDED")
    return ModelDownloadStatus(
      status = ModelDownloadStatusType.SUCCEEDED,
      receivedBytes = 0,
      totalBytes = 0,
    )
  }

  var status = ModelDownloadStatusType.NOT_DOWNLOADED
  var receivedBytes = 0L
  var totalBytes = 0L

  // Partially downloaded.
  if (isModelPartiallyDownloaded(model = model)) {
    status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
    val tmpFilePath =
      model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
    val tmpFile = File(tmpFilePath)
    receivedBytes = tmpFile.length()
    totalBytes = model.totalBytes
    BaoLog.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
  }
  // Fully downloaded.
  else if (isModelDownloaded(model = model)) {
    status = ModelDownloadStatusType.SUCCEEDED
    BaoLog.d(TAG, "${model.name} has been downloaded.")
  }
  // Not downloaded.
  else {
    BaoLog.d(TAG, "${model.name} has not been downloaded.")
  }

  return ModelDownloadStatus(
    status = status,
    receivedBytes = receivedBytes,
    totalBytes = totalBytes,
  )
}

private fun getAllowlistUrl(version: String): String {
  return "$ALLOWLIST_BASE_URL/${version}.json"
}
