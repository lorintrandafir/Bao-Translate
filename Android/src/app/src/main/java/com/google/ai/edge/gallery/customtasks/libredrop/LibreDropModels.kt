/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import com.google.ai.edge.gallery.customtasks.libredrop.discovery.DiscoveredService

/** One peer as rendered in the nearby-devices list. */
data class DiscoveredPeer(
  val name: String,
  val endpointId: String,
)

/** One user-chosen file staged for sending. */
data class SelectedFile(
  val uri: Uri,
  val name: String,
  val sizeBytes: Long,
)

/** One in-flight or finished outbound transfer, mirrored from `OutboundConnection.state`. */
data class TransferStatus(
  val peerName: String,
  val fileName: String,
  val progress: Float,
  val state: TransferState,
  val id: Long = 0L,
  val failureReason: String? = null,
)

enum class TransferState {
  CONNECTING,
  TRANSFERRING,
  COMPLETE,
  FAILED,
}

/** Characters of the raw mDNS instance name to show when a peer advertises no device name. */
private const val INSTANCE_NAME_PREVIEW_CHARS = 12

/**
 * Render a discovered service as a list row.
 *
 * Peers that advertise a device name show it; hidden peers (which omit the name on the wire by
 * design) fall back to a truncated mDNS instance name so the row is still selectable.
 */
internal fun DiscoveredService.toDisplayPeer(): DiscoveredPeer =
  DiscoveredPeer(
    name =
      endpointInfo?.deviceName?.takeIf { it.isNotBlank() }
        ?: instanceName.take(INSTANCE_NAME_PREVIEW_CHARS),
    endpointId = endpointId?.joinToString("") { "%02x".format(it) } ?: "",
  )

/**
 * Localized human-readable byte count.
 *
 * Delegates to the platform formatter rather than hand-rolling `"$bytes B"` / `"… KB"` strings:
 * those were hardcoded English with hardcoded 1024-based units, so every non-English locale saw
 * untranslated unit suffixes and a decimal separator that did not match the rest of the UI.
 * [Formatter.formatShortFileSize] resolves units, rounding and separators from the device locale.
 */
internal fun formatFileSize(context: Context, bytes: Long): String =
  Formatter.formatShortFileSize(context, bytes)

/**
 * Resolve a content URI's display name and size.
 *
 * Both columns are optional in the `OpenableColumns` contract — a provider may return neither —
 * so the name falls back to the URI's last path segment and the size to zero. A zero size is
 * honest here: the sender re-reads the real length from the stream when it builds the payload.
 */
internal fun resolveFileMetadata(context: Context, uri: Uri): Pair<String, Long> {
  var name: String? = null
  var size = 0L
  context.contentResolver
    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
    ?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (nameIdx >= 0) name = cursor.getString(nameIdx)
        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
      }
    }
  return Pair(name ?: uri.lastPathSegment ?: FALLBACK_FILE_NAME, size)
}

/** Shown when a provider supplies neither a display name nor a usable URI path segment. */
private const val FALLBACK_FILE_NAME = "unnamed"
