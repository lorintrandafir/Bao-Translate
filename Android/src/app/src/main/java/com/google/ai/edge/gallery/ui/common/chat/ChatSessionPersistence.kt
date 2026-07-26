package com.google.ai.edge.gallery.ui.common.chat

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.core.DataStore
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.AudioMessageProto
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.proto.UserData
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun ChatViewModel.saveSession(
    sessionId: String,
    messages: List<ChatMessage>,
    originalModel: String,
    taskId: String,
    context: Context? = null,
  ) {
    val messagesSnapshot = messages.toList()
    viewModelScope.launch(Dispatchers.IO) {
      val firstTextMessage =
        messagesSnapshot.filterIsInstance<ChatMessageText>().firstOrNull()?.content
      val title =
        firstTextMessage?.take(30)?.let { if (it.length == 30) "$it..." else it }
          ?: "New Chat Session"

      val protoMessages = messagesSnapshot.mapNotNull { msg ->
        val builder = ChatMessageProto.newBuilder()
        when (msg) {
          is ChatMessageText -> {
            builder
              .setMessageType("TEXT")
              .setContent(msg.content)
              .setSide(mapChatSide(msg.side))
              .setLatencyMs(msg.latencyMs)
              .setAccelerator(msg.accelerator)
              .setHideSenderLabel(msg.hideSenderLabel)
              .setIsMarkdown(msg.isMarkdown)
          }
          is ChatMessageThinking -> {
            builder
              .setMessageType("THINKING")
              .setContent(msg.content)
              .setSide(mapChatSide(msg.side))
              .setInProgress(msg.inProgress)
              .setAccelerator(msg.accelerator)
              .setHideSenderLabel(msg.hideSenderLabel)
          }
          is ChatMessageInfo -> {
            builder.setMessageType("INFO").setContent(msg.content).setSide(mapChatSide(msg.side))
          }
          is ChatMessageWarning -> {
            builder.setMessageType("WARNING").setContent(msg.content).setSide(mapChatSide(msg.side))
          }
          is ChatMessageError -> {
            builder.setMessageType("ERROR").setContent(msg.content).setSide(mapChatSide(msg.side))
          }
          is ChatMessageImage -> {
            builder
              .setMessageType("IMAGE")
              .setSide(mapChatSide(msg.side))
              .setLatencyMs(msg.latencyMs)
            synchronized(msg) {
              val cachedPaths = msg.persistedPaths
              if (cachedPaths != null) {
                builder.addAllImageFilePaths(cachedPaths)
              } else if (context != null) {
                msg.persistedPaths = buildList {
                  msg.bitmaps.forEachIndexed { index, bitmap ->
                    val fileName = "img_${sessionId}_${System.currentTimeMillis()}_$index.png"
                    val file = File(context.cacheDir, fileName)
                    FileOutputStream(file).use { fos ->
                      bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    add(file.absolutePath)
                    builder.addImageFilePaths(file.absolutePath)
                  }
                }
              }
            }
          }
          is ChatMessageAudioClip -> {
            builder
              .setMessageType("AUDIO_CLIP")
              .setSide(mapChatSide(msg.side))
              .setLatencyMs(msg.latencyMs)
            synchronized(msg) {
              val cachedPath = msg.persistedPath
              if (cachedPath != null) {
                val audioProto =
                  AudioMessageProto.newBuilder()
                    .setFilePath(cachedPath)
                    .setSampleRate(msg.sampleRate)
                    .build()
                builder.addAudioClips(audioProto)
              } else if (context != null) {
                val fileName = "audio_${sessionId}_${System.currentTimeMillis()}.pcm"
                val file = File(context.cacheDir, fileName)
                FileOutputStream(file).use { fos -> fos.write(msg.audioData) }
                msg.persistedPath = file.absolutePath
                val audioProto =
                  AudioMessageProto.newBuilder()
                    .setFilePath(file.absolutePath)
                    .setSampleRate(msg.sampleRate)
                    .build()
                builder.addAudioClips(audioProto)
              }
            }
          }
          else -> return@mapNotNull null
        }
        builder.build()
      }

      val sessionProto =
        ChatSessionProto.newBuilder()
          .setSessionId(sessionId)
          .setTitle(title)
          .setTimestampMs(System.currentTimeMillis())
          .setOriginalModel(originalModel)
          .setTaskId(taskId)
          .addAllMessages(protoMessages)
          .build()

      userDataDataStore?.updateData { userData ->
        val currentSessions = userData.chatSessionsList.toMutableList()
        currentSessions.removeAll { it.sessionId == sessionId }
        currentSessions.add(sessionProto)
        userData.toBuilder().clearChatSessions().addAllChatSessions(currentSessions).build()
      }
    }
  }

  /**
   * Deletes a chat session from persistent storage by its ID.
   *
   * @param sessionId The ID of the session to delete.
   */
fun ChatViewModel.deleteSession(sessionId: String, context: Context? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      if (context != null) {
        val files = context.cacheDir.listFiles()
        files?.forEach { file ->
          if (
            file.name.startsWith("img_${sessionId}_") || file.name.startsWith("audio_${sessionId}_")
          ) {
            file.delete()
          }
        }
      }
      userDataDataStore?.updateData { userData ->
        val currentSessions = userData.chatSessionsList.filter { it.sessionId != sessionId }
        userData.toBuilder().clearChatSessions().addAllChatSessions(currentSessions).build()
      }
    }
  }

  /** Clears all saved chat sessions from persistent storage. */
fun ChatViewModel.clearAllSessions(context: Context? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      if (context != null) {
        val files = context.cacheDir.listFiles()
        files?.forEach { file ->
          if (file.name.startsWith("img_") || file.name.startsWith("audio_")) {
            file.delete()
          }
        }
      }
      userDataDataStore?.updateData { userData -> userData.toBuilder().clearChatSessions().build() }
    }
  }

  /** Maps the domain [ChatSide] enum to its corresponding proto representation. */
internal fun ChatViewModel.mapChatSide(side: ChatSide): ChatSideProto {
    return when (side) {
      ChatSide.USER -> ChatSideProto.CHAT_SIDE_USER
      ChatSide.AGENT -> ChatSideProto.CHAT_SIDE_MODEL
      ChatSide.SYSTEM -> ChatSideProto.CHAT_SIDE_SYSTEM
    }
  }
