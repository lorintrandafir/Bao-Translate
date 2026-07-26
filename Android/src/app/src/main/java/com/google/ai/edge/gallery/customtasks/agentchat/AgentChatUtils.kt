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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.google.ai.edge.gallery.common.BaoLog
import kotlin.io.encoding.Base64

private const val TAG = "AGAgentChatUtils"

fun getDisplayName(context: Context, uri: Uri): String {
  var name = ""
  runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (nameIndex != -1 && cursor.moveToFirst()) {
        name = cursor.getString(nameIndex)
      }
    }
  }.onFailure {
    // Keep the URI fallback below when display-name lookup is unavailable.
  }
  return name.ifEmpty { uri.path?.substringAfterLast('/') ?: "Unknown" }
}

fun decodeBase64ToBitmap(base64String: String): Bitmap? {
  return runCatching {
    // 1. Clean the string (remove headers if present)
    val pureBase64 = base64String.substringAfter(",")

    // 2. Decode the Base64 string into a byte array
    val imageBytes = Base64.decode(pureBase64)

    // 3. Convert the byte array into a Bitmap
    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
  }.getOrElse { e ->
    BaoLog.e(TAG, "Failed to decode image bytes", e)
    null
  }
}
