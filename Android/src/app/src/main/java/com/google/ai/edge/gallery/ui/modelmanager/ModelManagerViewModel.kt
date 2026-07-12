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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.runtime.aicore.AICoreModelHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationService

private const val TAG = "AGModelManagerViewModel"
private const val TEXT_INPUT_HISTORY_MAX_SIZE = 50

data class ModelInitializationStatus(
  val status: ModelInitializationStatusType,
  var error: String = "",
  var initializedBackends: Set<String> = setOf(),
) {
  fun isFirstInitialization(model: Model): Boolean {
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    return !initializedBackends.contains(backend)
  }
}

enum class ModelInitializationStatusType {
  NOT_INITIALIZED,
  INITIALIZING,
  INITIALIZED,
  ERROR,
}

// TokenStatus, TokenRequestResultType, TokenStatusAndData, TokenRequestResult moved to
// ModelManagerTokenAuth.kt alongside getTokenStatusAndData(), getAuthorizationRequest(), and
// handleAuthResult(). Re-imported here for backward compat at use sites.

data class ModelManagerUiState(
  /** A list of tasks available in the application. */
  val tasks: List<Task>,

  /** Tasks grouped by category. */
  val tasksByCategory: Map<String, List<Task>>,

  /** A map that tracks the download status of each model, indexed by model name. */
  val modelDownloadStatus: Map<String, ModelDownloadStatus>,

  /** A map that tracks the initialization status of each model, indexed by model name. */
  val modelInitializationStatus: Map<String, ModelInitializationStatus>,

  /** Whether the app is loading and processing the model allowlist. */
  val loadingModelAllowlist: Boolean = true,

  /** The error message when loading the model allowlist. */
  val loadingModelAllowlistError: String = "",

  /** The currently selected model. */
  val selectedModel: Model = EMPTY_MODEL,

  /** The history of text inputs entered by the user. */
  val textInputHistory: List<String> = listOf(),
  val configValuesUpdateTrigger: Long = 0L,
  // Updated when model is imported of an imported model is deleted.
  val modelImportingUpdateTrigger: Long = 0L,
) {
  fun isModelInitialized(model: Model): Boolean {
    return modelInitializationStatus[model.name]?.status ==
      ModelInitializationStatusType.INITIALIZED
  }

  fun isModelInitializing(model: Model): Boolean {
    return modelInitializationStatus[model.name]?.status ==
      ModelInitializationStatusType.INITIALIZING
  }
}

internal val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )

internal val PREDEFINED_LLM_TASK_ORDER =
  listOf(
    BuiltInTaskId.BAO_TRANSLATE,
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_TINY_GARDEN,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
  )

/**
 * ViewModel responsible for managing models, their download status, and initialization.
 *
 * This ViewModel handles model-related operations such as downloading, deleting, initializing, and
 * cleaning up models. It also manages the UI state for model management, including the list of
 * tasks, models, download statuses, and initialization statuses.
 */
@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(
  internal val downloadRepository: DownloadRepository,
  val dataStoreRepository: DataStoreRepository,
  internal val lifecycleProvider: AppLifecycleProvider,
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
  internal val systemPromptRepository: SystemPromptRepository,
  @ApplicationContext internal val context: Context,
) : ViewModel() {
  internal val externalFilesDir = context.getExternalFilesDir(null)
  internal val _uiState = MutableStateFlow(createInitialUiState())
  open val uiState = _uiState.asStateFlow()

  internal var _allowlistModels: MutableList<Model> = mutableListOf()
  val allowlistModels: List<Model>
    get() = _allowlistModels

  val authService = AuthorizationService(context)
  var curAccessToken: String = ""

  override fun onCleared() {
    authService.dispose()
  }

  fun getTaskById(id: String): Task? {
    return uiState.value.tasks.find { it.id == id }
  }

  fun getTasksByIds(ids: Set<String>): List<Task> {
    return uiState.value.tasks.filter { ids.contains(it.id) }
  }

  fun getCustomTaskByTaskId(id: String): CustomTask? {
    return getActiveCustomTasks().find { it.task.id == id }
  }

  fun getActiveCustomTasks(): List<CustomTask> {
    return customTasks.toList()
  }

  fun getSelectedModel(): Model? {
    return uiState.value.selectedModel
  }

  fun getModelByName(name: String): Model? {
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        if (model.name == name) {
          return model
        }
      }
    }
    return null
  }

  fun getAllModels(): List<Model> {
    val allModels = mutableSetOf<Model>()
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        allModels.add(model)
      }
    }
    return allModels.toList().sortedBy { it.displayName.ifEmpty { it.name } }
  }

  fun getAllDownloadedModels(): List<Model> {
    return getAllModels().filter {
      uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED &&
        it.isLlm
    }
  }

  fun processTasks() {
    val curTasks = getActiveCustomTasks().map { it.task }
    for (task in curTasks) {
      for (model in task.models) {
        model.preProcess()
      }
      // Move the model that is best for this task to the front.
      val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) }
      if (bestModel != null) {
        task.models.remove(bestModel)
        task.models.add(0, bestModel)
      }
    }
  }

  fun updateConfigValuesUpdateTrigger() {
    _uiState.update { it.copy(configValuesUpdateTrigger = System.currentTimeMillis()) }
  }

  fun selectModel(model: Model) {
    if (_uiState.value.selectedModel.name != model.name) {
      _uiState.update { it.copy(selectedModel = model) }
    }
  }

  open fun downloadModel(task: Task?, model: Model) {
    // Update status.
    setDownloadStatus(
      curModel = model,
      status = ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS),
    )

    // Tracked in b/494029782: LiteRT-LM and AICore downloads still use separate storage paths.
    if (model.runtimeType == RuntimeType.AICORE) {
      AICoreModelHelper.downloadModel(
        context = context,
        coroutineScope = viewModelScope,
        model = model,
        onProgress = { downloaded: Long, total: Long ->
          setDownloadStatus(
            curModel = model,
            status =
              ModelDownloadStatus(
                status = ModelDownloadStatusType.IN_PROGRESS,
                receivedBytes = downloaded,
                totalBytes = total,
              ),
          )
        },
        onDone = {
          setDownloadStatus(
            curModel = model,
            status =
              ModelDownloadStatus(
                status = ModelDownloadStatusType.SUCCEEDED,
                receivedBytes = model.sizeInBytes,
                totalBytes = model.sizeInBytes,
              ),
          )
        },
        onError = { error: String ->
          setDownloadStatus(
            curModel = model,
            status =
              ModelDownloadStatus(status = ModelDownloadStatusType.FAILED, errorMessage = error),
          )
        },
      )
      return
    }

    // Delete the model files first.
    deleteModel(model = model)

    // Start to send download request.
    downloadRepository.downloadModel(
      task = task,
      model = model,
      onStatusUpdated = this::setDownloadStatus,
    )
  }

  fun cancelDownloadModel(model: Model) {
    // Tracked in b/494029782: AICore model deletion is controlled outside the local repository.
    // AICore models cannot be deleted from the download repository within the app.
    if (model.runtimeType == RuntimeType.AICORE) {
      return
    }
    downloadRepository.cancelDownloadModel(model)
    deleteModel(model = model)
  }

  fun deleteModel(model: Model) {
    // If the currently downloaded model is an updatable version, reset the model to its latest
    // version and mark it as not updatable upon deletion.
    if (model.updatable) {
      model.updatable = false
      model.latestModelFile?.let {
        model.version = it.commitHash
        model.downloadFileName = it.fileName
      }
    }

    if (model.imported) {
      deleteFilesFromImportDir(model.downloadFileName)
    } else {
      deleteDirFromExternalFilesDir(model.normalizedName)
    }

    // Update model download status to NotDownloaded.
    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[model.name] =
      ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)

    // Delete model from the list if model is imported as a local model.
    if (model.imported) {
      for (curTask in uiState.value.tasks) {
        val index = curTask.models.indexOf(model)
        if (index >= 0) {
          curTask.models.removeAt(index)
        }
        curTask.updateTrigger.value = System.currentTimeMillis()
      }
      curModelDownloadStatus.remove(model.name)

      viewModelScope.launch(Dispatchers.IO) {
        val importedModels = dataStoreRepository.readImportedModels().toMutableList()
        val importedModelIndex = importedModels.indexOfFirst { it.fileName == model.name }
        if (importedModelIndex >= 0) {
          importedModels.removeAt(importedModelIndex)
        }
        dataStoreRepository.saveImportedModels(importedModels = importedModels)
      }
    }
    _uiState.update {
      it.copy(
        modelDownloadStatus = curModelDownloadStatus,
        tasks = it.tasks.toList(),
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }
  }

  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    // Update model download progress.
    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[curModel.name] = status
    // Delete downloaded file if status is failed or not_downloaded.
    if (
      status.status == ModelDownloadStatusType.FAILED ||
        status.status == ModelDownloadStatusType.NOT_DOWNLOADED
    ) {
      deleteFileFromExternalFilesDir(curModel.downloadFileName)
    }

    _uiState.update { it.copy(modelDownloadStatus = curModelDownloadStatus) }
  }

  fun setInitializationStatus(model: Model, status: ModelInitializationStatus) {
    val curStatus = uiState.value.modelInitializationStatus.toMutableMap()
    if (curStatus.containsKey(model.name)) {
      val initializedBackends = curStatus[model.name]?.initializedBackends ?: setOf()
      val backend =
        model.getStringConfigValue(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = Accelerator.GPU.label,
        )
      val newInitializedBackends =
        if (status.status == ModelInitializationStatusType.INITIALIZED) {
          initializedBackends + backend
        } else {
          initializedBackends
        }
      curStatus[model.name] = status.copy(initializedBackends = newInitializedBackends)
      _uiState.update { it.copy(modelInitializationStatus = curStatus) }
    }
  }

  internal fun updateModelInitializationStatus(
    model: Model,
    status: ModelInitializationStatusType,
    error: String = "",
  ) {
    val curModelInstance = uiState.value.modelInitializationStatus.toMutableMap()
    val initializedBackends = curModelInstance[model.name]?.initializedBackends ?: setOf()
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val newInitializedBackends =
      if (status == ModelInitializationStatusType.INITIALIZED) {
        initializedBackends + backend
      } else {
        initializedBackends
      }
    curModelInstance[model.name] =
      ModelInitializationStatus(
        status = status,
        error = error,
        initializedBackends = newInitializedBackends,
      )
    _uiState.update { it.copy(modelInitializationStatus = curModelInstance) }
  }

  fun addTextInputHistory(text: String) {
    if (uiState.value.textInputHistory.indexOf(text) < 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.add(0, text)
      if (newHistory.size > TEXT_INPUT_HISTORY_MAX_SIZE) {
        newHistory.removeAt(newHistory.size - 1)
      }
      _uiState.update { it.copy(textInputHistory = newHistory) }
      viewModelScope.launch { dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory) }
    } else {
      promoteTextInputHistoryItem(text)
    }
  }

  fun promoteTextInputHistoryItem(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      newHistory.add(0, text)
      _uiState.update { it.copy(textInputHistory = newHistory) }
      viewModelScope.launch { dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory) }
    }
  }

  fun deleteTextInputHistory(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      _uiState.update { it.copy(textInputHistory = newHistory) }
      viewModelScope.launch { dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory) }
    }
  }

  fun clearTextInputHistory() {
    _uiState.update { it.copy(textInputHistory = mutableListOf()) }
    viewModelScope.launch { dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory) }
  }

  fun readThemeOverride(): Theme {
    return dataStoreRepository.readTheme()
  }

  fun saveThemeOverride(theme: Theme) {
    viewModelScope.launch { dataStoreRepository.saveTheme(theme = theme) }
  }

  // getTokenStatusAndData, getAuthorizationRequest, handleAuthResult moved to
  // ModelManagerTokenAuth.kt as extension functions. The original methods on the
  // ModelManagerViewModel instance are now extension functions imported via the same package.

  fun saveAccessToken(accessToken: String, refreshToken: String, expiresAt: Long) {
    viewModelScope.launch {
      dataStoreRepository.saveAccessTokenData(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
      )
    }
  }

  fun clearAccessToken() {
    viewModelScope.launch { dataStoreRepository.clearAccessTokenData() }
  }

  /** Persists that a promo has been viewed (fire-and-forget). */
  fun markPromoViewed(promoId: String) {
    viewModelScope.launch { dataStoreRepository.addViewedPromoId(promoId = promoId) }
  }

  fun setAppInForeground(foreground: Boolean) {
    lifecycleProvider.isAppInForeground = foreground
  }

  fun loadModelAllowlist() {
    performLoadModelAllowlist(this)
  }

  fun clearLoadModelAllowlistError() {
    performClearLoadModelAllowlistError(this)
  }

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) {
    performInitializeModel(this, context, task, model, force, onDone, onError)
  }

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) {
    performCleanupModel(this, context, task, model, instanceToCleanUp, onDone)
  }

  fun addImportedLlmModel(info: ImportedModel) {
    performAddImportedLlmModel(this, info)
  }

  fun getModelUrlResponse(model: Model, accessToken: String? = null): Int {
    return performGetModelUrlResponse(this, model, accessToken)
  }
}
