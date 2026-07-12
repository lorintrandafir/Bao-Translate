package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private const val TAG = "BaoTranslateVM"

/**
 * Model lifecycle methods extracted from [BaoTranslateViewModel] so the ViewModel file stays
 * focused on UI delegation. These extension functions access internal ViewModel state
 * (`_uiState`, `modelManager`, `pipelines`, `downloadCoordinator`, `participantStateManager`)
 * which is why those fields are `internal` rather than `private`.
 */

// Whisper decode language for the chosen source: "" (auto-detect) only when the user picks Auto;
// otherwise force the selected language so recognition isn't corrupted by mis-detection.
internal fun BaoTranslateViewModel.sttLanguageCode(): String =
  // Face-to-face must decode EITHER paired language, so it forces Whisper auto-detect ("") just like
  // the AUTO source — forcing one side would mis-transcribe the other speaker.
  if (_uiState.value.faceToFaceMode || _uiState.value.sourceLanguage == SupportedLanguages.AUTO.key) ""
  else SupportedLanguages.codeFor(_uiState.value.sourceLanguage)

// Returns a translation model whose files are actually present on disk. When the selected/persisted
// model is missing (deleted, or a partial install), falls back to the required qwen25_1b (or any
// other ready translation model) and PERSISTS the correction. Self-heals the cold-start brick where
// a stale persisted model id forced ModelsNotReady even though a usable model was installed.
internal fun BaoTranslateViewModel.resolveAndPersistTranslationModel(app: Application): String {
  val current = _uiState.value.translationModel
  if (modelManager.checkModelStatus(app, current) == ModelStatus.Ready) return current
  val fallback = listOf("qwen25_1b", "gemma4_e2b")
    .firstOrNull { modelManager.checkModelStatus(app, it) == ModelStatus.Ready }
    ?: "qwen25_1b"
  if (fallback != current) {
    _uiState.update { it.copy(translationModel = fallback) }
    persistBaoTranslateSettings()
    BaoLog.w(TAG, "Translation model '$current' unavailable; fell back to '$fallback'")
  }
  return fallback
}

internal fun BaoTranslateViewModel.initializeModelsImpl() {
  viewModelScope.launch(Dispatchers.IO) {
    val app = getApplication<Application>()
    _uiState.update { it.copy(pipelineStatus = PipelineStatus.Initializing) }

    modelManager.refreshStatuses(app)
    _uiState.update { it.copy(storageBreakdown = modelManager.getStorageBreakdown(app)) }
    participantStateManager.refreshLocalRuntimeState(app)

    if (!modelManager.areRequiredModelsReady(app)) {
      _uiState.update { it.copy(
        pipelineStatus = PipelineStatus.ModelsNotReady,
      ) }
      return@launch
    }

    pipelines.initializePipelines(app, resolveAndPersistTranslationModel(app), sttLanguageCode())
    if (!pipelines.requiredPipelinesReady()) {
      val missing = pipelines.missingPipelineComponents(app)
      pipelines.cleanupPipelines()
      _uiState.update { it.copy(
        modelsReady = false,
        pipelineStatus = PipelineStatus.ModelsNotReady,
        errorMessage = app.getString(
          R.string.bao_translate_error_runtime_not_ready,
          missing.joinToString(),
        ),
      ) }
      return@launch
    }

    participantStateManager.refreshLocalRuntimeState(app)
    // Re-broadcast metadata so connected peers pick up the (just-loaded) enrolled timbre.
    bleManager.rebroadcastMetadata()

    _uiState.update {
      it.copy(
        modelsReady = true,
        pipelineStatus = PipelineStatus.Idle,
      )
    }
  }
}

internal fun BaoTranslateViewModel.deleteModelImpl(modelId: String) {
  viewModelScope.launch(Dispatchers.IO) {
    val app = getApplication<Application>()
    // Cancel and await any in-flight download of this model BEFORE deleting its files, so the
    // delete can't race a live writer (no surviving partial, no model resurrected by a late
    // completion write).
    downloadCoordinator.cancelDownload(modelId)
    when (modelId) {
      "whisper_base" -> {
        pipelines.pipelineMutex.withLock {
          pipelines.whisperPipeline?.cleanup()
          pipelines.whisperPipeline = null
        }
      }
      "silero_vad" -> {
        pipelines.pipelineMutex.withLock {
          pipelines.vadProcessor?.cleanup()
          pipelines.vadProcessor = null
        }
      }
      "qwen25_1b", "gemma4_e2b" -> {
        pipelines.pipelineMutex.withLock {
          pipelines.translationPipeline?.cleanup()
          pipelines.translationPipeline = null
        }
      }
      "kokoro_tts" -> {
        pipelines.pipelineMutex.withLock {
          pipelines.kokoroTts?.cleanup()
          pipelines.kokoroTts = null
        }
      }
      "supertonic_tts" -> {
        pipelines.pipelineMutex.withLock {
          pipelines.supertonicTts?.cleanup()
          pipelines.supertonicTts = null
        }
      }
    }
    modelManager.deleteModel(app, modelId)
    // If the deleted model was the active translation model, fall back to one that still exists
    // and PERSIST it, so a cold start can't restore a pointer to the deleted model and wedge the
    // pipeline into ModelsNotReady while the required model is present.
    resolveAndPersistTranslationModel(app)
    val requiredReady = modelManager.areRequiredModelsReady(app)
    _uiState.update { it.copy(
      storageBreakdown = modelManager.getStorageBreakdown(app),
      modelsReady = requiredReady && pipelines.requiredPipelinesReady(),
      pipelineStatus = if (requiredReady) PipelineStatus.Idle else PipelineStatus.ModelsNotReady,
    ) }
  }
}

internal fun BaoTranslateViewModel.deleteAllModelsImpl() {
  viewModelScope.launch(Dispatchers.IO) {
    val app = getApplication<Application>()
    // Cancel and await every in-flight download before wiping the model dirs (avoids the
    // delete-vs-download race and a download re-marking a model Ready after the wipe).
    downloadCoordinator.cancelAllDownloads()
    pipelines.pipelineMutex.withLock {
      pipelines.cleanupPipelinesLocked()
    }
    modelManager.deleteAllModels(app)
    // No translation model remains; reset the persisted selection to the required default.
    resolveAndPersistTranslationModel(app)
    _uiState.update { it.copy(
      storageBreakdown = emptyMap(),
      modelsReady = false,
      pipelineStatus = PipelineStatus.ModelsNotReady,
    ) }
  }
}

internal fun BaoTranslateViewModel.reinitializePipelineImpl(component: String) {
  viewModelScope.launch(Dispatchers.IO) {
    val app = getApplication<Application>()
    _uiState.update { it.copy(pipelineStatus = PipelineStatus.Initializing) }

    pipelines.reinitializePipeline(app, component, _uiState.value.translationModel, sttLanguageCode())

    if (pipelines.requiredPipelinesReady()) {
      participantStateManager.refreshLocalRuntimeState(app)
      _uiState.update { it.copy(
        modelsReady = true,
        pipelineStatus = PipelineStatus.Idle,
        errorMessage = null,
      ) }
    } else {
      val missing = pipelines.missingPipelineComponents(app)
      _uiState.update { it.copy(
        modelsReady = false,
        pipelineStatus = PipelineStatus.ModelsNotReady,
        errorMessage = app.getString(
          R.string.bao_translate_error_runtime_not_ready,
          missing.joinToString(),
        ),
      ) }
    }
  }
}