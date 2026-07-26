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

import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.proto.ImportedModel
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGModelManagerImports"

internal fun createModelFromImportedModelInfo(info: ImportedModel): Model {
  val accelerators: MutableList<Accelerator> =
    info.llmConfig.compatibleAcceleratorsList
      .mapNotNull { acceleratorLabel ->
        when (acceleratorLabel.trim()) {
          Accelerator.GPU.label -> Accelerator.GPU
          Accelerator.CPU.label -> Accelerator.CPU
          Accelerator.NPU.label -> Accelerator.NPU
          else -> null
        }
      }
      .toMutableList()
  val llmMaxToken = info.llmConfig.defaultMaxTokens
  val llmSupportImage = info.llmConfig.supportImage
  val llmSupportAudio = info.llmConfig.supportAudio
  val llmSupportTinyGarden = info.llmConfig.supportTinyGarden
  val llmSupportMobileActions = info.llmConfig.supportMobileActions
  val llmSupportThinking = info.llmConfig.supportThinking
  val llmSupportSpeculativeDecoding = info.llmConfig.supportSpeculativeDecoding
  val configs: MutableList<Config> =
    createLlmChatConfigs(
        defaultMaxToken = llmMaxToken,
        defaultTopK = info.llmConfig.defaultTopk,
        defaultTopP = info.llmConfig.defaultTopp,
        defaultTemperature = info.llmConfig.defaultTemperature,
        accelerators = accelerators,
        supportThinking = llmSupportThinking,
        supportSpeculativeDecoding = llmSupportSpeculativeDecoding,
      )
      .toMutableList()
  val capabilities: MutableList<ModelCapability> = mutableListOf()
  val capabilityToTaskTypes: MutableMap<ModelCapability, List<String>> = mutableMapOf()
  if (llmSupportThinking) {
    capabilities.add(ModelCapability.LLM_THINKING)
    capabilityToTaskTypes[ModelCapability.LLM_THINKING] =
      listOf(
        BuiltInTaskId.LLM_CHAT,
        BuiltInTaskId.LLM_ASK_IMAGE,
        BuiltInTaskId.LLM_ASK_AUDIO,
      )
  }
  if (llmSupportSpeculativeDecoding) {
    capabilities.add(ModelCapability.SPECULATIVE_DECODING)
    capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING] =
      listOf(
        BuiltInTaskId.LLM_CHAT,
        BuiltInTaskId.LLM_ASK_IMAGE,
        BuiltInTaskId.LLM_ASK_AUDIO,
        BuiltInTaskId.LLM_PROMPT_LAB,
        BuiltInTaskId.LLM_AGENT_CHAT,
      )
  }
  val model =
    Model(
      name = info.fileName,
      url = "",
      configs = configs,
      sizeInBytes = info.fileSize,
      downloadFileName = "$IMPORTS_DIR/${info.fileName}",
      showBenchmarkButton = false,
      showRunAgainButton = false,
      imported = true,
      llmSupportImage = llmSupportImage,
      llmSupportAudio = llmSupportAudio,
      llmSupportTinyGarden = llmSupportTinyGarden,
      llmSupportMobileActions = llmSupportMobileActions,
      capabilities = capabilities.toList(),
      capabilityToTaskTypes = capabilityToTaskTypes.toMap(),
      llmMaxToken = llmMaxToken,
      accelerators = accelerators,
      isLlm = true,
      runtimeType = RuntimeType.LITERT_LM,
    )
  model.preProcess()

  return model
}

internal fun performAddImportedLlmModel(vm: ModelManagerViewModel, info: ImportedModel) {
  BaoLog.d(TAG, "adding imported llm model: $info")

  val model = createModelFromImportedModelInfo(info = info)

  val setOfTasks =
    mutableSetOf(
      BuiltInTaskId.LLM_CHAT,
      BuiltInTaskId.LLM_ASK_IMAGE,
      BuiltInTaskId.LLM_ASK_AUDIO,
      BuiltInTaskId.LLM_PROMPT_LAB,
      BuiltInTaskId.LLM_TINY_GARDEN,
      BuiltInTaskId.LLM_MOBILE_ACTIONS,
      BuiltInTaskId.LLM_AGENT_CHAT,
    )
  for (task in vm.getTasksByIds(ids = setOfTasks)) {
    val modelIndex = task.models.indexOfFirst { info.fileName == it.name && it.imported }
    if (modelIndex >= 0) {
      BaoLog.d(TAG, "duplicated imported model found in task. Removing it first")
      task.models.removeAt(modelIndex)
    }
    if (
      (task.id == BuiltInTaskId.LLM_ASK_IMAGE && model.llmSupportImage) ||
        (task.id == BuiltInTaskId.LLM_ASK_AUDIO && model.llmSupportAudio) ||
        (task.id == BuiltInTaskId.LLM_TINY_GARDEN && model.llmSupportTinyGarden) ||
        (task.id == BuiltInTaskId.LLM_MOBILE_ACTIONS && model.llmSupportMobileActions) ||
        (task.id != BuiltInTaskId.LLM_ASK_IMAGE &&
          task.id != BuiltInTaskId.LLM_ASK_AUDIO &&
          task.id != BuiltInTaskId.LLM_TINY_GARDEN &&
          task.id != BuiltInTaskId.LLM_MOBILE_ACTIONS)
    ) {
      task.models.add(model)
      if (task.id == BuiltInTaskId.LLM_TINY_GARDEN) {
        val newConfigs = model.configs.toMutableList()
        newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
        model.configs = newConfigs
        model.preProcess()
      }
    }
    task.updateTrigger.value = System.currentTimeMillis()
  }

  val curModelDownloadStatus = vm.uiState.value.modelDownloadStatus.toMutableMap()
  val modelInstances = vm.uiState.value.modelInitializationStatus.toMutableMap()
  curModelDownloadStatus[model.name] =
    ModelDownloadStatus(
      status = ModelDownloadStatusType.SUCCEEDED,
      receivedBytes = info.fileSize,
      totalBytes = info.fileSize,
    )
  modelInstances[model.name] =
    ModelInitializationStatus(status = ModelInitializationStatusType.NOT_INITIALIZED)

  vm._uiState.update {
    it.copy(
      tasks = it.tasks.toList(),
      modelDownloadStatus = curModelDownloadStatus,
      modelInitializationStatus = modelInstances,
      modelImportingUpdateTrigger = System.currentTimeMillis(),
    )
  }

  vm.viewModelScope.launch(Dispatchers.IO) {
    val importedModels = vm.dataStoreRepository.readImportedModels().toMutableList()
    val importedModelIndex = importedModels.indexOfFirst { info.fileName == it.fileName }
    if (importedModelIndex >= 0) {
      BaoLog.d(TAG, "duplicated imported model found in data store. Removing it first")
      importedModels.removeAt(importedModelIndex)
    }
    importedModels.add(info)
    vm.dataStoreRepository.saveImportedModels(importedModels = importedModels)
  }
}

internal fun performGetModelUrlResponse(vm: ModelManagerViewModel, model: Model, accessToken: String? = null): Int {
  return runCatching {
    val url = URL(model.url)
    val connection = com.google.ai.edge.gallery.common.network.HttpClient.openConnection(
      url = url,
      accessToken = accessToken,
    )
    connection.connect()

    connection.responseCode
  }.getOrElse { e ->
    BaoLog.e(TAG, "$e")
    -1
  }
}
