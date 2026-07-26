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

package com.google.ai.edge.gallery.ui.common.chat

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Helper function to construct the first message when a session is restored from history.
 *
 * It prepends the entire text chat history (from User and Model) as context for the message,
 * ensuring the model understands the prior conversation when running the newly restored session.
 *
 * @param history The list of past messages for the selected model.
 * @param originalShortMessage The newly entered message to be added to the history.
 * @return A new [ChatMessageText] with history prepended, or null if there is no valid history.
 */
internal fun buildFirstMessageWithHistory(
  history: List<ChatMessage>,
  originalShortMessage: ChatMessageText,
): ChatMessageText? {
  val prefix =
    history
      .mapNotNull {
        when (it) {
          is ChatMessageText ->
            if (it.side == ChatSide.USER) "User:\n${it.content}" else "Model:\n${it.content}"
          else -> null
        }
      }
      .joinToString("\n\n")

  if (prefix.isEmpty()) {
    return null
  }

  return ChatMessageText(
    content = "$prefix\n\nUser:\n${originalShortMessage.content}",
    side = originalShortMessage.side,
    latencyMs = originalShortMessage.latencyMs,
    isMarkdown = originalShortMessage.isMarkdown,
    llmBenchmarkResult = originalShortMessage.llmBenchmarkResult,
    accelerator = originalShortMessage.accelerator,
    hideSenderLabel = originalShortMessage.hideSenderLabel,
  )
}

/**
 * Deserializes a list of [com.google.ai.edge.gallery.proto.ChatMessageProto] from persistent
 * storage into the corresponding [ChatMessage] UI models.
 *
 * @param protoMessages The list of saved protobuf messages.
 * @return The list of restored UI/domain message objects.
 */
internal fun deserializeProtoMessages(
  protoMessages: List<com.google.ai.edge.gallery.proto.ChatMessageProto>
): List<ChatMessage> {
  return protoMessages.mapNotNull { protoMsg ->
    val side =
      when (protoMsg.side) {
        com.google.ai.edge.gallery.proto.ChatSideProto.CHAT_SIDE_USER -> ChatSide.USER
        com.google.ai.edge.gallery.proto.ChatSideProto.CHAT_SIDE_MODEL -> ChatSide.AGENT
        com.google.ai.edge.gallery.proto.ChatSideProto.CHAT_SIDE_SYSTEM -> ChatSide.SYSTEM
        else -> ChatSide.SYSTEM
      }

    when (protoMsg.messageType) {
      "TEXT" ->
        ChatMessageText(
          content = protoMsg.content,
          side = side,
          latencyMs = protoMsg.latencyMs,
          isMarkdown = protoMsg.isMarkdown,
          accelerator = protoMsg.accelerator,
          hideSenderLabel = protoMsg.hideSenderLabel,
        )
      "THINKING" ->
        ChatMessageThinking(
          content = protoMsg.content,
          side = side,
          inProgress = protoMsg.inProgress,
          accelerator = protoMsg.accelerator,
          hideSenderLabel = protoMsg.hideSenderLabel,
        )
      "INFO" -> ChatMessageInfo(protoMsg.content)
      "WARNING" -> ChatMessageWarning(protoMsg.content)
      "ERROR" -> ChatMessageError(protoMsg.content)
      "IMAGE" -> {
        val bitmaps =
          protoMsg.imageFilePathsList.mapNotNull { path -> BitmapFactory.decodeFile(path) }
        if (bitmaps.isNotEmpty()) {
          ChatMessageImage(
            bitmaps = bitmaps,
            imageBitMaps = bitmaps.map { it.asImageBitmap() },
            side = side,
            latencyMs = protoMsg.latencyMs,
            accelerator = protoMsg.accelerator,
            hideSenderLabel = protoMsg.hideSenderLabel,
            persistedPaths = protoMsg.imageFilePathsList.toList(),
          )
        } else null
      }
      "AUDIO_CLIP" -> {
        val firstAudio = protoMsg.audioClipsList.firstOrNull()
        if (firstAudio != null) {
          runCatching {
            ChatMessageAudioClip(
              audioData = File(firstAudio.filePath).readBytes(),
              sampleRate = firstAudio.sampleRate,
              side = side,
              latencyMs = protoMsg.latencyMs,
              persistedPath = firstAudio.filePath,
            )
          }.getOrElse {
            null
          }
        } else null
      }
      else -> null
    }
  }
}
