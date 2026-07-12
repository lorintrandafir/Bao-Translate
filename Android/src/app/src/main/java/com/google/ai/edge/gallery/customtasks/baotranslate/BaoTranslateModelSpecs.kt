package com.google.ai.edge.gallery.customtasks.baotranslate

/**
 * Model spec data classes + the four spec lists (archives, single files, translation models,
 * OpenVoice files) + the streaming ASR constants. Lives outside [BaoTranslateModelManager] to
 * keep the manager focused on orchestration; the object references these tables by name.
 *
 * These tables are the single source of truth for download URLs, expected sizes, and required
 * extracted-file paths. They were previously embedded in [BaoTranslateModelManager], which
 * pushed that file past 1100 LOC.
 */

internal const val STREAMING_ASR_DIR = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
internal const val STREAMING_ASR_URL =
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$STREAMING_ASR_DIR.tar.bz2"

internal data class ArchiveSpec(
  val modelId: String,
  val archiveFileName: String,
  val downloadUrl: String,
  val sizeBytes: Long,
  val requiredFiles: List<String>,
  val extractDir: String,
)

internal data class FileSpec(
  val modelId: String,
  val fileName: String,
  val downloadUrl: String,
  val sizeBytes: Long,
)

internal data class TranslationModelSpec(
  val modelId: String,
  val fileName: String,
  val downloadUrl: String,
  val sizeBytes: Long,
)

internal data class OpenVoiceFileSpec(
  val downloadUrl: String,
  val targetName: String,
  val sizeBytes: Long,
)

internal val ARCHIVES = listOf(
  ArchiveSpec(
    modelId = "kokoro_tts",
    archiveFileName = "kokoro-multi-lang-v1_0.tar.bz2",
    downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
    sizeBytes = 350_000_000L,
    requiredFiles = listOf(
      "kokoro-multi-lang-v1_0/model.onnx",
      "kokoro-multi-lang-v1_0/voices.bin",
      "kokoro-multi-lang-v1_0/tokens.txt",
      "kokoro-multi-lang-v1_0/espeak-ng-data",
    ),
    extractDir = "kokoro-multi-lang-v1_0",
  ),
  ArchiveSpec(
    modelId = "streaming_asr",
    archiveFileName = "$STREAMING_ASR_DIR.tar.bz2",
    downloadUrl = STREAMING_ASR_URL,
    sizeBytes = 44_000_000L,
    requiredFiles = listOf(
      "$STREAMING_ASR_DIR/encoder-epoch-99-avg-1.int8.onnx",
      "$STREAMING_ASR_DIR/decoder-epoch-99-avg-1.int8.onnx",
      "$STREAMING_ASR_DIR/joiner-epoch-99-avg-1.int8.onnx",
      "$STREAMING_ASR_DIR/tokens.txt",
    ),
    extractDir = STREAMING_ASR_DIR,
  ),
  ArchiveSpec(
    modelId = "supertonic_tts",
    archiveFileName = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
    downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
    sizeBytes = 80_000_000L,
    requiredFiles = listOf(
      "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/duration_predictor.int8.onnx",
      "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/text_encoder.int8.onnx",
      "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/vector_estimator.int8.onnx",
      "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/vocoder.int8.onnx",
      "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/tts.json",
      "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/unicode_indexer.bin",
      "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/voice.bin",
    ),
    extractDir = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
  ),
)

internal val FILES = listOf(
  FileSpec(
    modelId = "silero_vad",
    fileName = "silero_vad.onnx",
    downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
    sizeBytes = 2_000_000L,
  ),
)

internal val TRANSLATION_MODELS = listOf(
  TranslationModelSpec(
    modelId = "qwen25_1b",
    fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
    downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
    sizeBytes = 1_597_931_520L,
  ),
  TranslationModelSpec(
    modelId = "gemma4_e2b",
    fileName = "gemma-4-E2B-it.litertlm",
    downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
    sizeBytes = 2_588_147_712L,
  ),
)

// OpenVoice v2 ToneColorConverter ONNX (public, downloadable export). ONE pair clones EVERY voice
// and language — the speaker identity is a 256-d embedding computed on-device at enrollment, never
// baked into the model — so there is no per-voice download. Saved under the app's local names
// (BaoTranslateModelManager.getOpenVoiceConverterFile / getOpenVoiceRefEncFile); sizes pinned to
// the HF LFS blob sizes so a truncated download is rejected (see downloadOpenVoiceModels).
internal val OPENVOICE_FILES = listOf(
  OpenVoiceFileSpec(
    downloadUrl = "https://huggingface.co/seasonstudio/openvoice_tone_clone_onnx/resolve/main/tone_clone_model.onnx",
    targetName = "ov_converter.onnx",
    sizeBytes = 127_891_564L,
  ),
  OpenVoiceFileSpec(
    downloadUrl = "https://huggingface.co/seasonstudio/openvoice_tone_clone_onnx/resolve/main/tone_color_extract_model.onnx",
    targetName = "ov_refenc.onnx",
    sizeBytes = 3_257_992L,
  ),
)