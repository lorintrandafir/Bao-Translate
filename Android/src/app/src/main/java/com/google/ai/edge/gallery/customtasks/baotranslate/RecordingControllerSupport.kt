/*
 * Copyright 2026 Google LLC
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
package com.google.ai.edge.gallery.customtasks.baotranslate

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Companion types hoisted out of [RecordingController] to keep the recording-lifecycle file
 * focused on capture, queueing, segment processing, and TTS. Tuning constants for capture pacing
 * and silence/turn detection live next to the only call-sites that use them.
 */

/** Capture pacing and endpoint-detector tuning. See [RecordingController] for the consumers. */
internal const val REC_TAG = "BaoTranslateRec"
internal const val INPUT_ROUTE_TIMEOUT_MS = 2_000L
internal const val LIVE_TRANSLATION_WINDOW_SECONDS = 8
internal const val LIVE_TRANSLATION_STRIDE_SECONDS = 4
internal const val MIN_TRANSLATION_SEGMENT_SECONDS = 1
internal const val AUDIO_READ_EMPTY_SLEEP_MS = 20L
internal const val AUDIO_READ_STALL_TIMEOUT_MS = 3_000L
// Extra window, past the end of TTS playback, during which the mic stays gated so the speaker's
// acoustic decay isn't captured as the next conversational turn (echo/feedback guard).
// Increased from 150ms to 300ms to prevent cutoff of dual-speaker transitions and allow
// sufficient acoustic decay tail before the mic re-opens.
internal const val PLAYBACK_TAIL_MUTE_MS = 300L
// Silence timeout for continuous conversation mode: if no speech is detected (no live segments
// queued) for this duration after the last translation, auto-stop recording. Enables buttonless
// conversation — user starts once, speaks, pauses, system translates, then auto-stops.
internal const val SILENCE_AUTO_STOP_MS = 15_000L
// Mic frame amplitude above which a frame counts as speech activity (shared by the silence
// auto-stop timer and face-to-face turn endpointing). Silero VAD remains the authority on what
// is actually speech — this only gates WHEN a captured turn is handed to it.
internal const val SPEECH_AMPLITUDE_THRESHOLD = 0.01f
// Face-to-face turn endpoint: after speech was heard, this much trailing quiet ends the turn and
// flushes the whole utterance as ONE segment. Turn-based capture (instead of the 8s/4s overlapped
// live windows) is what stops the same sentence being re-transcribed and re-spoken per window.
internal const val F2F_TURN_END_SILENCE_MS = 700L

/**
 * Resolution policy for [RecordingController.synthesizeSpeech] when a per-turn speaker embedding
 * is supplied alongside an enrolled local timbre.
 */
enum class SpeechTimbre {
  /** Use [speakerSe] when provided; otherwise fall back to the locally-enrolled timbre. */
  LocalEnrolled,

  /** Use [speakerSe] only — never fall back to the local enrolled timbre. */
  PeerOnly,
}

/**
 * Real-time-paced frame reader over a fixed PCM buffer, mimicking a live mic: it only releases
 * audio up to the wall-clock playback position, so VAD turn-endpointing (which keys off wall-clock
 * silence) and the streaming-partial cadence behave exactly as with a real microphone. Returns 0
 * while waiting for the next frame's real time (the read loop sleeps), and 0 once exhausted.
 *
 * Test-only seam: instrumentation sets the [RecordingController.testPcmSource] companion field to
 * drive the production read loop with deterministic PCM. The device echo canceller cancels its
 * own speaker output captured by its own mic, so an automated speaker->mic self-loopback cannot
 * feed STT; this routes clean PCM through the exact production VAD -> turn-endpoint ->
 * streaming-partial -> translate -> UI loop.
 */
internal class InjectedFrameSource(private val pcm: ShortArray, private val sampleRate: Int) {
  private var position = 0
  private var startMs = 0L

  fun read(buffer: ShortArray): Int {
    val now = SystemClock.elapsedRealtime()
    if (startMs == 0L) startMs = now
    if (position >= pcm.size) return 0
    val playbackSamples = ((now - startMs) * sampleRate / 1000L).toInt()
    val available = minOf(playbackSamples - position, pcm.size - position)
    if (available <= 0) return 0
    val count = minOf(buffer.size, available)
    System.arraycopy(pcm, position, buffer, 0, count)
    position += count
    return count
  }
}

/** Single source of truth for "is the controller actively capturing". */
internal val BaoTranslateUiState.isRecordingActive: Boolean
  get() = isRecording || isStartingRecording

/** Per-frame audio stats, surfaced into the waveform amplitude history. */
internal data class AudioStats(val samples: Int, val rms: String, val peak: Int)

/** Compute peak and RMS for a PCM frame. Returns zeros for an empty buffer. */
internal fun ShortArray.audioStats(): AudioStats {
  if (isEmpty()) {
    return AudioStats(samples = 0, rms = "0.000000", peak = 0)
  }

  var peak = 0
  var sumSquares = 0.0
  forEach { sample ->
    val value = sample.toInt()
    peak = maxOf(peak, abs(value))
    sumSquares += value.toDouble() * value.toDouble()
  }
  val rms = sqrt(sumSquares / size) / 32768.0
  return AudioStats(
    samples = size,
    rms = "%.6f".format(rms),
    peak = peak,
  )
}

