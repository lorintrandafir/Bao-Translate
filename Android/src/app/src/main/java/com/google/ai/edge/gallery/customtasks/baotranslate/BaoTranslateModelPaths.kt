package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.Context
import java.io.File

private const val SHERPA_ONNX_DIR = "sherpa_onnx_models"
private const val TRANSLATION_DIR = "translation_models"

internal fun getSherpaOnnxDir(context: Context): File =
  File(context.filesDir, SHERPA_ONNX_DIR).also { it.mkdirs() }

internal fun getTranslationDir(context: Context): File =
  File(context.filesDir, TRANSLATION_DIR).also { it.mkdirs() }

internal fun getKokoroModelDir(context: Context): File =
  File(getSherpaOnnxDir(context), "kokoro-multi-lang-v1_0")

internal fun getSupertonicModelDir(context: Context): File =
  File(getSherpaOnnxDir(context), "sherpa-onnx-supertonic-3-tts-int8-2026-05-11")

internal fun getVadModelPath(context: Context): String =
  File(getSherpaOnnxDir(context), "silero_vad.onnx").absolutePath

internal fun getWhisperModelDir(context: Context): File =
  File(getSherpaOnnxDir(context), "sherpa-onnx-whisper-base")

// Streaming ASR (sherpa-onnx zipformer transducer) — token-by-token live captions during a turn.
internal fun getStreamingAsrModelDir(context: Context): File =
  File(getSherpaOnnxDir(context), STREAMING_ASR_DIR)

internal fun getOpenVoiceDir(context: Context): File =
  File(context.filesDir, "openvoice").also { it.mkdirs() }

internal fun getOpenVoiceConverterFile(context: Context): File =
  File(getOpenVoiceDir(context), "ov_converter.onnx")

internal fun getOpenVoiceRefEncFile(context: Context): File =
  File(getOpenVoiceDir(context), "ov_refenc.onnx")

// length()>0 (not just exists()): a zero-byte file left by a killed write must not count as ready
// (it would feed a corrupt model into ONNX Runtime).
internal fun isOpenVoiceCloneAvailable(context: Context): Boolean =
  getOpenVoiceConverterFile(context).length() > 0 && getOpenVoiceRefEncFile(context).length() > 0

internal fun getTranslationModelDir(context: Context, modelId: String = "qwen25_1b"): File =
  File(getTranslationDir(context), modelId)