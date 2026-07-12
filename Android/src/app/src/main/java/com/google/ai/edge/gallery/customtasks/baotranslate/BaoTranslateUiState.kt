package com.google.ai.edge.gallery.customtasks.baotranslate

import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.data.Participant
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.VoiceProfile

/**
 * Pipeline UI status, extracted from [BaoTranslateViewModel] so the ViewModel file stays focused
 * on orchestration. These types are referenced by the ViewModel, the UI composables, tests, and the
 * coordinators.
 */

sealed interface PipelineStatus {
  data object Idle : PipelineStatus
  data object Initializing : PipelineStatus
  data object StartingRecording : PipelineStatus
  data object Recording : PipelineStatus
  data object Processing : PipelineStatus
  data object Speaking : PipelineStatus
  data object ModelsNotReady : PipelineStatus
  data class Error(val message: String) : PipelineStatus
}

data class BaoTranslateUiState(
  val pipelineStatus: PipelineStatus = PipelineStatus.Idle,
  val transcripts: List<com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage> = emptyList(),
  val sourceLanguage: String = SupportedLanguages.AUTO.key,
  val targetLanguage: String = SupportedLanguages.DEFAULT_TARGET_KEY,
  val voiceProfileEnrolled: Boolean = false,
  val voiceProfilePath: String? = null,
  // Active voice profile id. "default" is the original single-profile id; additional profiles
  // use user-provided names. Enables multiple voice enrollment — user can switch between profiles
  // without re-recording.
  val activeVoiceProfileId: String = "default",
  // All enrolled voice profiles available for selection.
  val voiceProfiles: List<VoiceProfile> = emptyList(),
  val errorMessage: String? = null,
  val modelsReady: Boolean = false,
  val modelStatuses: Map<String, ModelStatus> = emptyMap(),
  val amplitudes: List<Float> = emptyList(),
  val elapsedSeconds: Float = 0f,
  val liveTranslationPreview: String? = null,
  // Recognized SOURCE text, surfaced the instant STT completes — before the (slower) translation —
  // so the live caption appears ~1s+ sooner. Cleared once the translated message commits.
  val liveSourcePreview: String? = null,
  val currentAudioDevice: AudioDevice = AudioDevice.Speaker,
  // Face-to-face per-speaker output routing: ISO language code -> the output device that speaker's
  // translations play on (e.g. one person's earbuds, the other the phone speaker). Empty => both use
  // the global [currentAudioDevice]. Drives the per-panel output chip and RecordingController routing.
  val faceToFaceOutputs: Map<String, AudioDevice> = emptyMap(),
  val availableAudioDevices: List<AudioDevice> = emptyList(),
  val availableInputDevices: List<com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioInputOption> = emptyList(),
  val preferredInputDevice: com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice.BluetoothHeadset? = null,
  val routingStatus: com.google.ai.edge.gallery.customtasks.baotranslate.audio.RoutingStatus = com.google.ai.edge.gallery.customtasks.baotranslate.audio.RoutingStatus.IDLE,
  val sttModel: String = "whisper_base",
  val translationModel: String = "qwen25_1b",
  val wifiOnlyDownloads: Boolean = true,
  val storageBreakdown: Map<String, Long> = emptyMap(),
  val localParticipant: Participant? = null,
  val detectedLanguage: String? = null,
  val welcomeDismissed: Boolean = false,
  // Single-device, 2-speaker "face-to-face" mode: [sourceLanguage] and [targetLanguage] form a
  // bidirectional pair. STT auto-detects each turn and the engine routes to the OTHER language, so two
  // people sharing one phone are each understood without a second device (cf. the BLE multi-device path).
  val faceToFaceMode: Boolean = false,
  val replayMessageId: String? = null,
  /** When true and source is AUTO, adopt Whisper's detected language as the source without a tap. */
  val autoAcceptDetectedLanguage: Boolean = false,
  /**
   * Live hands-free turn phase mirrored from [ConversationManager] so the UI can surface
   * Listening / Translating / Speaking without polling the controller.
   */
  val conversationPhase: ConversationPhase = ConversationPhase.Idle,
) {
  val isRecording: Boolean get() = pipelineStatus == PipelineStatus.Recording
  val isStartingRecording: Boolean get() = pipelineStatus == PipelineStatus.StartingRecording
  val isProcessing: Boolean get() = pipelineStatus == PipelineStatus.Processing
  val isSpeaking: Boolean get() = pipelineStatus == PipelineStatus.Speaking
  val isInitializing: Boolean get() = pipelineStatus == PipelineStatus.Initializing
  val totalStorageMb: Float
    get() = storageBreakdown.values.sum().toFloat() / (1024f * 1024f)

  val requiredModelsReady: Boolean
    get() {
      val whisper = modelStatuses["whisper_base"]
      val translation = modelStatuses["qwen25_1b"]
      val vad = modelStatuses["silero_vad"]
      val tts = modelStatuses["kokoro_tts"]
      return whisper == ModelStatus.Ready &&
        translation == ModelStatus.Ready &&
        vad == ModelStatus.Ready &&
        tts == ModelStatus.Ready
    }

  val allModelsReady: Boolean
    get() = modelStatuses.values.all { it == ModelStatus.Ready }
}