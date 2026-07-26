package com.google.ai.edge.gallery.customtasks.baotranslate

import android.app.Application
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Init-block flow collectors extracted from [BaoTranslateViewModel] so the ViewModel constructor
 * stays readable. Called once from the ViewModel `init {}` block.
 *
 * Collects: model status updates, audio router state (output device, available devices, input
 * preference, routing status), and incoming BLE conversation messages (translate + speak peer turns).
 */
internal fun BaoTranslateViewModel.startCollectors() {
  viewModelScope.launch {
    modelManager.modelStatuses.collect { statuses ->
      val requiredReady =
        statuses["whisper_base"] == ModelStatus.Ready &&
          statuses["qwen25_1b"] == ModelStatus.Ready &&
          statuses["silero_vad"] == ModelStatus.Ready &&
          statuses["kokoro_tts"] == ModelStatus.Ready
      _uiState.update { state ->
        state.copy(
          modelStatuses = statuses,
          modelsReady = requiredReady && pipelines.requiredPipelinesReady(),
        )
      }
    }
  }

  viewModelScope.launch {
    audioRouter.currentDevice.collect { device ->
      _uiState.update { state ->
        val updated = state.copy(currentAudioDevice = device)
        val participant = participantStateManager.updateLocalParticipant(updated)
        updated.copy(localParticipant = participant)
      }
      // Broadcast once on the committed participant, outside the update{} CAS lambda.
      _uiState.value.localParticipant?.let { bleManager.setLocalParticipant(it) }
    }
  }

  viewModelScope.launch {
    audioRouter.availableOutputDevices.collect { devices ->
      _uiState.update { it.copy(availableAudioDevices = devices) }
    }
  }

  viewModelScope.launch {
    audioRouter.availableInputDevices.collect { inputs ->
      _uiState.update { it.copy(availableInputDevices = inputs) }
    }
  }

  viewModelScope.launch {
    audioRouter.preferredInputDevice.collect { device ->
      _uiState.update { it.copy(preferredInputDevice = device) }
    }
  }

  viewModelScope.launch {
    audioRouter.routingStatus.collect { status ->
      _uiState.update { it.copy(routingStatus = status) }
    }
  }

  viewModelScope.launch(Dispatchers.IO) {
    bleManager.messages.collect { bleMsg ->
      handleBleMessage(bleMsg)
    }
  }
}

/**
 * Translates and speaks an incoming BLE conversation message from a peer device. Extracted so the
 * collector body in [startCollectors] stays a one-liner.
 *
 * Translation and TTS operate on ISO codes (mirroring the local recording path); the KEYs
 * ("German", "Korean", ...) are kept only for the display fields. Passing a KEY to
 * synthesizeSpeech silently broke platform TTS for non-Kokoro target languages because
 * PlatformTtsPipeline feeds it to Locale.forLanguageTag, which needs "de"/"ko", not
 * "German"/"Korean". codeFor() normalizes display keys and leaves ISO codes unchanged.
 */
private suspend fun BaoTranslateViewModel.handleBleMessage(bleMsg: com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth.BleTranscriptMessage) {
  val (translation, targetLang) = pipelines.pipelineMutex.withLock {
    pipelines.translationPipeline to _uiState.value.targetLanguage
  }
  val sourceCode = SupportedLanguages.codeFor(bleMsg.sourceLanguage)
  val targetCode = SupportedLanguages.codeFor(targetLang)
  var translationSucceeded = false
  val translatedText = if (translation != null) {
    when (val outcome = translation.translateBlocking(
      sourceText = bleMsg.text,
      sourceLanguage = sourceCode,
      targetLanguage = targetCode,
    )) {
      is TranslationOutcome.Success -> {
        translationSucceeded = true
        outcome.result.translatedText
      }
      is TranslationOutcome.Failure -> {
        _uiState.update { it.copy(errorMessage = getApplication<Application>().getString(R.string.bao_translate_error_translation_failed, outcome.reason)) }
        bleMsg.text
      }
    }
  } else {
    bleMsg.text
  }

  val messageId = UUID.randomUUID().toString()
  val message = TranslationMessage(
    id = messageId,
    originalText = bleMsg.text,
    translatedText = translatedText,
    sourceLanguage = bleMsg.sourceLanguage,
    targetLanguage = targetLang,
    timestamp = bleMsg.timestamp,
    isUser = false,
    speakerName = bleMsg.senderName,
  )
  _uiState.update { it.copy(transcripts = it.transcripts + message) }

  // Speak the peer's translated message aloud so a live conversation can be *heard*, not just
  // read — mirroring the local-speech path. Previously received messages were silent
  // (synthesizeSpeech was never called and audioPlayed stayed null).
  if (translationSucceeded) {
    // Speak the peer's turn in THEIR own cloned voice when they've shared a timbre over BLE
    // (multi-speaker cloning); otherwise synthesizeSpeech falls back to the local voice/TTS.
    val audioPlayed = recordingController.synthesizeSpeech(
      translatedText,
      targetCode,
      speakerSe = bleManager.voiceEmbeddingFor(bleMsg.senderId),
      timbre = SpeechTimbre.PeerOnly,
    )
    _uiState.update { state ->
      state.copy(
        transcripts = state.transcripts.map { existing ->
          if (existing.id == messageId) existing.copy(audioPlayed = audioPlayed) else existing
        },
      )
    }
  }
}