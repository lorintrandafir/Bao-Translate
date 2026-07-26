package com.google.ai.edge.gallery.customtasks.baotranslate.bluetooth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import com.google.ai.edge.gallery.customtasks.baotranslate.tts.OpenVoiceVoiceConverter
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject

internal const val MSG_TRANSCRIPT = "T"
internal const val MSG_METADATA = "M"

@Serializable
data class BleTranscriptMessage(
  val text: String,
  val senderId: String,
  val senderName: String,
  val sourceLanguage: String,
  val targetLanguage: String,
  val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class BleMetadataMessage(
  val participantId: String,
  val participantName: String,
  val sourceLanguage: String,
  val targetLanguage: String,
  val hasVoiceProfile: Boolean,
  // The sender's 256-d OpenVoice speaker embedding (~2.5 KB as JSON), shared so the receiver can
  // speak this peer's translated turns in THAT peer's own voice (multi-speaker voice cloning).
  // Null/omitted when the peer hasn't enrolled a voice. Well under MAX_BLE_MESSAGE_SIZE.
  val voiceEmbedding: List<Float>? = null,
)

/**
 * Pure (Context-free) encode/validate/decode for conversation payloads. Decoding is DEFENSIVE:
 * a connected peer — or link corruption from a legitimate peer — can put arbitrary bytes on the
 * wire. The kotlinx decode is wrapped to return null on any SerializationException instead of
 * throwing out of the Nearby Connections main-thread callback and crashing the process.
 * Standalone object so the decode contract is unit-testable without a Context.
 */
internal object BleMessageCodec {
  private val json = Json { ignoreUnknownKeys = true }

  fun encodeTranscript(message: BleTranscriptMessage): String =
    MSG_TRANSCRIPT + json.encodeToString(BleTranscriptMessage.serializer(), message)

  fun encodeMetadata(message: BleMetadataMessage): String =
    MSG_METADATA + json.encodeToString(BleMetadataMessage.serializer(), message)

  fun isValidTranscriptJson(payload: String): Boolean =
    payload.startsWith("{") && payload.endsWith("}") &&
      payload.contains("\"text\"") &&
      payload.contains("\"senderId\"") &&
      payload.contains("\"senderName\"") &&
      payload.contains("\"sourceLanguage\"") &&
      payload.contains("\"targetLanguage\"")

  fun isValidMetadataJson(payload: String): Boolean =
    payload.startsWith("{") && payload.endsWith("}") &&
      payload.contains("\"participantId\"") &&
      payload.contains("\"participantName\"") &&
      payload.contains("\"sourceLanguage\"") &&
      payload.contains("\"targetLanguage\"") &&
      payload.contains("\"hasVoiceProfile\"")

  fun decodeTranscript(payload: String): BleTranscriptMessage? {
    val obj = parseObject(payload) ?: return null
    val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return null
    val senderId = (obj["senderId"] as? JsonPrimitive)?.contentOrNull ?: ""
    val senderName = (obj["senderName"] as? JsonPrimitive)?.contentOrNull ?: ""
    val sourceLanguage = (obj["sourceLanguage"] as? JsonPrimitive)?.contentOrNull ?: ""
    val targetLanguage = (obj["targetLanguage"] as? JsonPrimitive)?.contentOrNull ?: ""
    val timestamp = (obj["timestamp"] as? JsonPrimitive)?.longOrNull ?: System.currentTimeMillis()
    return BleTranscriptMessage(text, senderId, senderName, sourceLanguage, targetLanguage, timestamp)
  }

  fun decodeMetadata(payload: String): BleMetadataMessage? {
    val obj = parseObject(payload) ?: return null
    val participantId = (obj["participantId"] as? JsonPrimitive)?.contentOrNull ?: return null
    val participantName = (obj["participantName"] as? JsonPrimitive)?.contentOrNull ?: ""
    val sourceLanguage = (obj["sourceLanguage"] as? JsonPrimitive)?.contentOrNull ?: ""
    val targetLanguage = (obj["targetLanguage"] as? JsonPrimitive)?.contentOrNull ?: ""
    val hasVoiceProfile = (obj["hasVoiceProfile"] as? JsonPrimitive)?.booleanOrNull ?: false
    // A peer (or link corruption) can put non-finite floats on the wire. kotlinx parses bare
    // NaN/Infinity/-Infinity tokens into Float.NaN/±Float.INFINITY (floatOrNull does NOT drop them),
    // so size alone would not catch a 256-d array of NaN. Reject any element that is not a finite
    // float so a poisoned timbre never reaches peerVoiceEmbeddings / the OpenVoice clone pipeline.
    val voiceEmbedding = (obj["voiceEmbedding"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull?.takeIf { f -> f.isFinite() } }
      ?.takeIf { it.size == OpenVoiceVoiceConverter.SE_DIM }
    return BleMetadataMessage(participantId, participantName, sourceLanguage, targetLanguage, hasVoiceProfile, voiceEmbedding)
  }

  // Never throws: malformed JSON (passes the substring gate but isn't parseable) yields null.
  private fun parseObject(payload: String): JsonObject? =
    runCatching { json.decodeFromString<JsonElement>(payload) }.getOrNull() as? JsonObject
}

data class DiscoveredPeer(
  val id: String,
  val name: String,
  val deviceAddress: String,
)

enum class ConnectionState {
  DISCONNECTED,
  ADVERTISING,
  SCANNING,
  CONNECTING,
  CONNECTED,
}

