package com.google.ai.edge.gallery.customtasks.baotranslate

import android.annotation.SuppressLint
import android.app.Application
import com.google.ai.edge.gallery.common.BaoLog
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioRouter
import com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleConversationManager
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioCache
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.EncryptedBlobStore
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfile
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfileManager
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import com.google.ai.edge.gallery.data.BaoTranslateStoredSettings
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private const val TAG = "BaoTranslateVM"

@SuppressLint("RestrictedApi")
@HiltViewModel
class BaoTranslateViewModel @Inject constructor(
  application: Application,
  private val dataStoreRepository: DataStoreRepository,
) : AndroidViewModel(application) {

  internal val _uiState = MutableStateFlow(BaoTranslateUiState())
  val uiState: StateFlow<BaoTranslateUiState> = _uiState.asStateFlow()

  val bleManager = BleConversationManager(application)
  val audioRouter = AudioRouter(application)
  private val voiceProfileEncryptedStore = EncryptedBlobStore(
    application,
    EncryptedBlobStore.voiceProfilesDir(application),
  )
  private val voiceProfileManager = VoiceProfileManager(
    application,
    encryptedStore = voiceProfileEncryptedStore,
  )

  internal val pipelines = PipelineLifecycleManager(
    voiceProfileManager = voiceProfileManager,
    activeProfileId = { _uiState.value.activeVoiceProfileId },
  )

  /**
   * True once the OPTIONAL OpenVoice cross-lingual clone converter has finished initializing. Required
   * models becoming ready (the record control appearing) does NOT imply the clone converter is loaded —
   * it inits a few seconds later — so a peer-timbre test must wait on this before asserting a clone.
   */
  @VisibleForTesting(otherwise = VisibleForTesting.NONE)
  internal val testOpenVoiceCloneReady: Boolean
    get() = pipelines.openVoiceConverter != null

  private val localParticipantId = UUID.randomUUID().toString()
  internal val modelManager = BaoTranslateModelManager

  internal val participantStateManager = ParticipantStateManager(
    pipelines = pipelines,
    audioRouter = audioRouter,
    voiceProfileManager = voiceProfileManager,
    bleManager = bleManager,
    uiState = _uiState,
    localParticipantId = localParticipantId,
    getApp = { getApplication() },
  )

  private val viewModelJob = SupervisorJob()
  val viewModelScope = CoroutineScope(viewModelJob + Dispatchers.Main.immediate)

  private val voiceLanguageCoordinator = VoiceLanguageCoordinator(
    pipelines = pipelines,
    voiceProfileManager = voiceProfileManager,
    bleManager = bleManager,
    uiState = _uiState,
    viewModelScope = viewModelScope,
    getApp = { getApplication() },
    localParticipantId = localParticipantId,
    reinitializePipeline = ::reinitializePipeline,
  )

  internal val recordingController = RecordingController(
    pipelines = pipelines,
    audioRouter = audioRouter,
    bleManager = bleManager,
    uiState = _uiState,
    viewModelScope = viewModelScope,
    getApp = { getApplication() },
    voiceProfileManager = voiceProfileManager,
    onAutoAcceptDetectedLanguage = { language ->
      if (!_uiState.value.faceToFaceMode) {
        voiceLanguageCoordinator.setSourceLanguage(language)
        persistBaoTranslateSettings()
      }
    },
  )

  internal val downloadCoordinator = ModelDownloadCoordinator(
    pipelines = pipelines,
    modelManager = modelManager,
    uiState = _uiState,
    viewModelScope = viewModelScope,
    getApp = { getApplication() },
    refreshLocalRuntimeState = participantStateManager::refreshLocalRuntimeState,
    resolveTranslationModel = { resolveAndPersistTranslationModel(it) },
    reinitializePipeline = ::reinitializePipeline,
  )

  @SuppressLint("RestrictedApi")
  internal companion object {
    // Test-only overrides: when set, the ViewModel ignores the corresponding persisted setting.
    // Lets instrumentation pin a deterministic, fast config (qwen25_1b + English->Spanish)
    // regardless of whatever a prior session persisted. Never set in production.
    @Volatile @VisibleForTesting(otherwise = VisibleForTesting.NONE) internal var testForcedTranslationModel: String? = null
    @Volatile @VisibleForTesting(otherwise = VisibleForTesting.NONE) internal var testForcedSourceLanguage: String? = null
    @Volatile @VisibleForTesting(otherwise = VisibleForTesting.NONE) internal var testForcedTargetLanguage: String? = null

    // Test-only: the most recently created instance, so instrumentation can reach the live
    // bleManager + uiState to verify multi-speaker receive routing. Cleared in onCleared.
    @Volatile @VisibleForTesting(otherwise = VisibleForTesting.NONE) internal var testInstance: BaoTranslateViewModel? = null
  }

  init {
    testInstance = this
    bleManager.setLocalEmbeddingProvider { pipelines.openVoiceTargetSe }
    val storedSettings = dataStoreRepository.getBaoTranslateSettings()
    _uiState.update {
      it.copy(
        welcomeDismissed = dataStoreRepository.getHasDismissedBaoTranslateWelcome(),
        transcripts = emptyList(),
        // Restore persisted preferences so model/language/tts choices survive a cold start.
        // Null => never saved, keep the in-memory defaults.
        translationModel = testForcedTranslationModel
          ?: storedSettings?.translationModel?.takeIf { m -> m.isNotBlank() } ?: it.translationModel,
        sourceLanguage = testForcedSourceLanguage
          ?: storedSettings?.sourceLanguage?.takeIf { l -> l.isNotBlank() } ?: it.sourceLanguage,
        targetLanguage = testForcedTargetLanguage
          ?: storedSettings?.targetLanguage?.takeIf { l -> l.isNotBlank() } ?: it.targetLanguage,
        wifiOnlyDownloads = storedSettings?.wifiOnlyDownloads ?: it.wifiOnlyDownloads,
        autoAcceptDetectedLanguage = storedSettings?.autoAcceptDetectedLanguage ?: it.autoAcceptDetectedLanguage,
      )
    }

    modelManager.refreshStatuses(application)
    _uiState.update { it.copy(storageBreakdown = modelManager.getStorageBreakdown(application)) }
    AudioCache.attachDiskStore(
      EncryptedBlobStore(application, EncryptedBlobStore.ttsCacheDir(application)),
    )
    refreshVoiceProfiles()

    startCollectors()
  }

  fun initializeModels() = initializeModelsImpl()

  fun downloadModel(modelId: String) = downloadCoordinator.downloadModel(modelId)

  fun downloadRequiredModels() = downloadCoordinator.downloadRequiredModels()

  fun deleteModel(modelId: String) = deleteModelImpl(modelId)

  fun deleteAllModels() = deleteAllModelsImpl()

  fun startRecording() = recordingController.startRecording()

  fun stopRecording() = recordingController.stopRecording()

  fun cancelRecording() = recordingController.cancelRecording()

  fun replayAudio(message: TranslationMessage) {
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(replayMessageId = message.id) }
      val result = runCatching {
        recordingController.synthesizeSpeech(
          text = message.translatedText,
          language = message.targetLanguage,
          preserveRecordingStatus = true,
        )
      }
      _uiState.update { s ->
        if (s.replayMessageId == message.id) s.copy(replayMessageId = null) else s
      }
      result.getOrThrow()
    }
  }

  // Clears this device's conversation transcript and any in-flight preview. Local-only: it does NOT
  // touch BLE participants/connection state, the enrolled voice profile, downloaded models, or the
  // recording session. No-op while recording so it can't race RecordingController's append path.
  fun clearTranscripts() {
    if (_uiState.value.isRecording || _uiState.value.isStartingRecording) return
    _uiState.update {
      it.copy(
        transcripts = emptyList(),
        liveTranslationPreview = null,
        liveSourcePreview = null,
        detectedLanguage = null,
        errorMessage = null,
      )
    }
  }

  // Leaves a live conversation: disconnects the peer and stops advertising/scanning. Wired to the
  // per-device disconnect control in ConversationModeScreen (the BLE manager already supports it; it
  // was never reachable from the UI before).
  fun disconnectPeer(deviceAddress: String) = bleManager.disconnectFromDevice(deviceAddress)

  fun leaveConversation() = bleManager.stopConversationDiscovery()

  fun setSourceLanguage(language: String) {
    voiceLanguageCoordinator.setSourceLanguage(language)
    persistBaoTranslateSettings()
  }

  fun setTargetLanguage(language: String) {
    voiceLanguageCoordinator.setTargetLanguage(language)
    persistBaoTranslateSettings()
  }

  /**
   * Assigns the output device for a face-to-face speaker's translations, keyed by their ISO language
   * code. Selecting the global speaker clears the per-speaker override (falls back to [currentAudioDevice]).
   */
  fun setFaceToFaceOutput(langCode: String, device: AudioDevice) {
    _uiState.update { state ->
      val next = state.faceToFaceOutputs.toMutableMap()
      if (device is AudioDevice.Speaker) next.remove(langCode) else next[langCode] = device
      state.copy(faceToFaceOutputs = next)
    }
  }

  /**
   * Enter/exit single-device, 2-speaker face-to-face mode. The two languages form a bidirectional
   * pair, so [sourceLanguage] must be a concrete language (not AUTO) — defaults a missing/Auto source
   * to English. Re-initialises STT to auto-detect so either speaker's language is transcribed; the
   * bidirectional routing lives in RecordingController.processAudioSegment.
   */
  fun setFaceToFaceMode(enabled: Boolean) {
    if (_uiState.value.faceToFaceMode == enabled) return
    if (!enabled) {
      // Face-to-face is hands-free with no silence auto-stop; leaving the mode is the session
      // boundary, so close the live mic here instead of leaving it running headless.
      stopRecording()
    }
    val source = _uiState.value.sourceLanguage.takeIf { it != SupportedLanguages.AUTO.key }
      ?: SupportedLanguages.keyForCode("en")
      ?: SupportedLanguages.TRANSLATION_TARGETS.first().key
    _uiState.update {
      it.copy(
        faceToFaceMode = enabled,
        sourceLanguage = if (enabled) source else it.sourceLanguage,
        detectedLanguage = null,
      )
    }
    persistBaoTranslateSettings()
    reinitializePipeline("stt")
  }

  fun onLanguageChanged(source: String, target: String) {
    voiceLanguageCoordinator.onLanguageChanged(source, target)
    persistBaoTranslateSettings()
  }

  fun swapLanguages() {
    voiceLanguageCoordinator.swapLanguages()
    persistBaoTranslateSettings()
  }

  fun onVoiceEnrolled(audioPath: String) = voiceLanguageCoordinator.onVoiceEnrolled(audioPath)

  fun startEnrollmentRecording(audioPcm: ShortArray, sampleRate: Int, profileName: String? = null) {
    voiceLanguageCoordinator.startEnrollmentRecording(audioPcm, sampleRate, profileName)
    refreshVoiceProfiles()
  }

  fun deleteVoiceProfile(profileId: String? = null) {
    voiceLanguageCoordinator.deleteVoiceProfile(profileId)
    refreshVoiceProfiles()
  }

  /** Switch the active voice profile. Loads the speaker embedding for the selected profile. */
  fun switchVoiceProfile(profileId: String) {
    voiceLanguageCoordinator.switchVoiceProfile(profileId)
  }

  /** Refresh the list of enrolled voice profiles from disk. */
  fun refreshVoiceProfiles() {
    viewModelScope.launch(Dispatchers.IO) {
      val profiles = voiceProfileManager.listProfiles()
      val activeId = _uiState.value.activeVoiceProfileId
        .takeIf { id -> profiles.any { it.id == id } }
        ?: profiles.firstOrNull()?.id
        ?: VoiceProfileManager.DEFAULT_PROFILE_ID
      val profile = voiceProfileManager.loadProfile(activeId)
      pipelines.openVoiceTargetSe = voiceProfileManager.loadSpeakerEmbedding(activeId)
      _uiState.update { state ->
        state.copy(
          voiceProfiles = profiles,
          activeVoiceProfileId = activeId,
          voiceProfileEnrolled = profile != null,
          voiceProfilePath = profile?.wavPath,
        )
      }
    }
  }

  fun setSttModel(model: String) = voiceLanguageCoordinator.setSttModel(model)

  fun setTranslationModel(model: String) {
    voiceLanguageCoordinator.setTranslationModel(model)
    persistBaoTranslateSettings()
  }

  fun setWifiOnly(enabled: Boolean) {
    voiceLanguageCoordinator.setWifiOnly(enabled)
    persistBaoTranslateSettings()
  }

  fun setAutoAcceptDetectedLanguage(enabled: Boolean) {
    _uiState.update { it.copy(autoAcceptDetectedLanguage = enabled) }
    persistBaoTranslateSettings()
  }

  internal fun persistBaoTranslateSettings() {
    val s = _uiState.value
    viewModelScope.launch(Dispatchers.IO) {
      dataStoreRepository.setBaoTranslateSettings(
        BaoTranslateStoredSettings(
          translationModel = s.translationModel,
          sourceLanguage = s.sourceLanguage,
          targetLanguage = s.targetLanguage,
          wifiOnlyDownloads = s.wifiOnlyDownloads,
          autoAcceptDetectedLanguage = s.autoAcceptDetectedLanguage,
        )
      )
    }
  }

  fun onSettingChanged(key: String) = voiceLanguageCoordinator.onSettingChanged(key)

  private fun reinitializePipeline(component: String) = reinitializePipelineImpl(component)

  fun refreshAudioDevice() {
    _uiState.update { it.copy(
      currentAudioDevice = audioRouter.detectCurrentDevice(),
      availableAudioDevices = audioRouter.getAvailableOutputDevices(),
    ) }
  }

  fun selectAudioDevice(device: AudioDevice) {
    when (device) {
      is AudioDevice.BluetoothHeadset -> audioRouter.preferBluetooth(target = device)
      is AudioDevice.WiredHeadset -> audioRouter.selectWired(device)
      AudioDevice.Speaker -> audioRouter.resetToSpeaker()
    }
  }

  fun selectInputDevice(device: com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice.BluetoothHeadset?) {
    audioRouter.selectPreferredInput(device)
  }

  fun setErrorMessage(message: String) {
    _uiState.update { it.copy(errorMessage = message, pipelineStatus = PipelineStatus.Idle) }
  }

  fun clearError() {
    _uiState.update { it.copy(errorMessage = null, pipelineStatus = PipelineStatus.Idle) }
  }

  fun dismissError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  fun retryLastAction() {
    clearError()
    if (_uiState.value.requiredModelsReady) {
      initializeModels()
    }
  }

  fun dismissWelcome() {
    viewModelScope.launch { dataStoreRepository.setHasDismissedBaoTranslateWelcome(true) }
    _uiState.update { it.copy(welcomeDismissed = true) }
  }

  @VisibleForTesting
  internal fun setTestLocalVoiceEmbeddingForTest(embedding: FloatArray?) {
    pipelines.openVoiceTargetSe = embedding
    _uiState.update { it.copy(voiceProfileEnrolled = embedding != null) }
  }

  override fun onCleared() {
    if (testInstance === this) testInstance = null
    recordingController.cancelRecording()
    recordingController.releaseStreamingStt()
    viewModelJob.cancel()
    val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    cleanupScope.launch {
      pipelines.cleanupPipelines()
      audioRouter.cleanup()
      bleManager.cleanup()
    }
  }
}
