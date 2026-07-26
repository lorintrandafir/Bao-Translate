package com.google.ai.edge.gallery.customtasks.baotranslate

import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.customtasks.baotranslate.audio.AudioDevice
import com.google.ai.edge.gallery.customtasks.baotranslate.data.SupportedLanguages
import com.google.ai.edge.gallery.customtasks.baotranslate.data.TranslationMessage
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.EmptyTranscriptionException
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VadInitResult
import com.google.ai.edge.gallery.customtasks.baotranslate.stt.VadProcessor
import com.google.ai.edge.gallery.customtasks.baotranslate.translate.TranslationOutcome
import com.google.ai.edge.gallery.customtasks.baotranslate.validation.isValidTranscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal suspend fun RecordingController.runSegmentPipeline(
    audioSamples: ShortArray,
    recordingSessionId: Long?,
    preserveRecordingStatus: Boolean,
    reportEmptySpeech: Boolean,
      ) {
        val inputStats = audioSamples.audioStats()
        BaoLog.i(
          REC_TAG,
          "Process audio segment preserve=$preserveRecordingStatus reportEmpty=$reportEmptySpeech $inputStats",
        )

    if (!preserveRecordingStatus) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Processing) }
    }

    val (whisper, translation, vad) = pipelines.pipelineMutex.withLock {
      Triple(pipelines.whisperPipeline, pipelines.translationPipeline, pipelines.vadProcessor)
    }

    if (whisper == null) {
      uiState.update { it.copy(
        pipelineStatus = statusAfterSegment(preserveRecordingStatus),
        errorMessage = getApp().getString(R.string.bao_translate_error_stt_not_init),
      ) }
      return
    }

    if (translation == null) {
      uiState.update { it.copy(
        pipelineStatus = statusAfterSegment(preserveRecordingStatus),
        errorMessage = getApp().getString(R.string.bao_translate_error_translation_not_init),
      ) }
      return
    }

    val vadProcessor = vad ?: run {
      val newVad = VadProcessor(getApp())
      if (newVad.initialize() is VadInitResult.Initialized) {
        pipelines.pipelineMutex.withLock { pipelines.vadProcessor = newVad }
        newVad
      } else {
        uiState.update { it.copy(
          pipelineStatus = statusAfterSegment(preserveRecordingStatus),
          errorMessage = getApp().getString(R.string.bao_translate_error_vad_init),
        ) }
        return
      }
    }

        val speechSegments = vadProcessor.processAudioSegment(audioSamples)
        BaoLog.i(
          REC_TAG,
          "VAD returned segments=${speechSegments.size} preserve=$preserveRecordingStatus inputSamples=${audioSamples.size}",
        )
        if (isStaleRecordingSegment(recordingSessionId)) return

    if (speechSegments.isEmpty()) {
      if (reportEmptySpeech) {
        uiState.update { it.copy(
          pipelineStatus = statusAfterSegment(preserveRecordingStatus),
          errorMessage = getApp().getString(R.string.bao_translate_error_no_speech_detected),
        ) }
      }
      return
    }

    for (segment in speechSegments) {
      // Re-arm per segment: a previous segment's playback chain lands the phase back on
      // Listening, and this transition (Listening -> Processing) marks the next STT pass.
      conversationEvent { onProcessingStart() }
      val transcriptionResult = whisper.transcribeBlocking(segment.toShortArray())
      BaoLog.i(REC_TAG, "STT done success=${transcriptionResult.isSuccess} chars=${transcriptionResult.getOrNull()?.text?.length ?: 0}")
      if (isStaleRecordingSegment(recordingSessionId)) return

      val transcription = transcriptionResult.fold(
        onSuccess = { it },
        onFailure = { error ->
          BaoLog.w(REC_TAG, "Transcription failed: ${error.message}")
          // A blank decode (VAD false-positive / noise window) is benign in continuous live mode;
          // suppress it there to match the VAD-empty and invalid-transcription branches. A real
          // native decode failure still surfaces so a corrupt model is never silent.
          val benignEmpty = error is EmptyTranscriptionException
          if ((reportEmptySpeech || !benignEmpty) && !isStaleRecordingSegment(recordingSessionId)) {
            uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_error_transcription_failed, error.message)) }
          }
          null
        },
      ) ?: continue

      if (!isValidTranscription(transcription.text)) {
        if (reportEmptySpeech) {
          uiState.update { it.copy(errorMessage = getApp().getString(R.string.bao_translate_error_no_clear_speech)) }
        }
        continue
      }

      // Surface the recognized SOURCE text immediately — the translation below (Qwen) is the
      // dominant latency (~1s+), so showing the live caption now makes the conversation feel live
      // instead of waiting for the full translated commit.
      uiState.update { it.copy(liveSourcePreview = transcription.text) }

      // Face-to-face (single-device, 2-speaker) auto-detects every turn (like AUTO source) so either
      // person's language is transcribed; STT is already in auto-detect for both.
      val twoWay = uiState.value.faceToFaceMode
      val autoDetected = twoWay || uiState.value.sourceLanguage == SupportedLanguages.AUTO.key
      val sourceLang = if (autoDetected) {
        SupportedLanguages.normalizeDetectedCode(transcription.language)
          ?: SupportedLanguages.CODE_MAP[SupportedLanguages.AUTO.key]
          ?: "auto"
      } else {
        SupportedLanguages.codeFor(uiState.value.sourceLanguage)
      }

      // In face-to-face the two configured languages are a bidirectional pair: a turn spoken in the
      // target language is translated BACK to the source language, so both speakers are understood on
      // one device. Everywhere else, translation always goes to the configured target.
      val langA = SupportedLanguages.codeFor(uiState.value.sourceLanguage)
      val langB = SupportedLanguages.codeFor(uiState.value.targetLanguage)
      val targetLang = if (twoWay && sourceLang == langB) langA else langB

      if (autoDetected) {
        if (isStaleRecordingSegment(recordingSessionId)) return
        val detectedKey = SupportedLanguages.keyForCode(sourceLang)
          ?.takeIf { key -> key != SupportedLanguages.AUTO.key }
        val shouldAutoAccept = !twoWay && uiState.value.autoAcceptDetectedLanguage
        uiState.update { it.copy(detectedLanguage = detectedKey) }
        if (detectedKey != null && shouldAutoAccept) {
          onAutoAcceptDetectedLanguage?.invoke(detectedKey)
        }
      }

      val translationOutcome = translation.translateBlocking(
        sourceText = transcription.text,
        sourceLanguage = sourceLang,
        targetLanguage = targetLang,
      )
      if (isStaleRecordingSegment(recordingSessionId)) return

      when (translationOutcome) {
            is TranslationOutcome.Success -> {
          if (isStaleRecordingSegment(recordingSessionId)) return
              val translatedText = translationOutcome.result.translatedText
              BaoLog.i(
                REC_TAG,
                "Translation success preserve=$preserveRecordingStatus source=$sourceLang target=$targetLang translatedChars=${translatedText.length}",
              )
          // Overlapping live windows (8s window / 4s stride) re-translate the shared 4s overlap,
          // yielding a duplicate of the previous live commit. Skip exact consecutive duplicates so
          // the same phrase is neither appended nor spoken twice; still refresh the live preview.
          // Only unique content reaches here, so no speech is lost.
          if (preserveRecordingStatus) {
            val lastUserText = uiState.value.transcripts.lastOrNull { it.isUser }?.translatedText
            if (lastUserText != null && lastUserText.trim().equals(translatedText.trim(), ignoreCase = true)) {
              uiState.update { it.copy(liveTranslationPreview = translatedText, liveSourcePreview = null) }
              continue
            }
          }

          val messageId = UUID.randomUUID().toString()
          val message = TranslationMessage(
            id = messageId,
            originalText = transcription.text,
            translatedText = translatedText,
            sourceLanguage = sourceLang,
            targetLanguage = targetLang,
            timestamp = System.currentTimeMillis(),
            isUser = true,
            audioPlayed = null,
            translationError = null,
          )

          uiState.update {
            it.copy(
              transcripts = it.transcripts + message,
              liveTranslationPreview = if (preserveRecordingStatus) translatedText else it.liveTranslationPreview,
              liveSourcePreview = null,
            )
          }

          // Live playback policy: one-shot mode always speaks on stop; headset routes speak live.
          // Face-to-face speaks live ON the phone speaker too — that is the product: the
          // capturePaused gate plus hardware AEC (both set up in the read loop) exist precisely so
          // speaker playback cannot feed back into continuous capture.
          val shouldPlayNow =
            !preserveRecordingStatus ||
              uiState.value.faceToFaceMode ||
              uiState.value.currentAudioDevice !is AudioDevice.Speaker
          val audioPlayed = if (shouldPlayNow) {
            synthesizeSpeech(
              text = translatedText,
              language = targetLang,
              recordingSessionId = recordingSessionId,
              preserveRecordingStatus = preserveRecordingStatus,
            )
          } else {
            false
          }
          if (isStaleRecordingSegment(recordingSessionId)) return
          uiState.update { state ->
            state.copy(
              transcripts = state.transcripts.map { existing ->
                if (existing.id == messageId) existing.copy(audioPlayed = audioPlayed) else existing
              },
            )
          }

          if (bleManager.getConnectedCount() > 0) {
            bleManager.sendTranscript(transcription.text, sourceLang, targetLang)
          }
        }
            is TranslationOutcome.Failure -> {
              BaoLog.w(
                REC_TAG,
                "Translation failed preserve=$preserveRecordingStatus source=$sourceLang target=$targetLang reasonChars=${translationOutcome.reason.length}",
              )
          if (isStaleRecordingSegment(recordingSessionId)) return
          val message = TranslationMessage(
            id = UUID.randomUUID().toString(),
            originalText = transcription.text,
            translatedText = "",
            sourceLanguage = sourceLang,
            targetLanguage = targetLang,
            timestamp = System.currentTimeMillis(),
            isUser = true,
            translationError = translationOutcome.reason,
          )

          uiState.update { it.copy(
            transcripts = it.transcripts + message,
            errorMessage = translationOutcome.reason,
          ) }
        }
      }
    }

    if (!preserveRecordingStatus && !isStaleRecordingSegment(recordingSessionId)) {
      uiState.update { it.copy(pipelineStatus = PipelineStatus.Idle) }
    }
  }
