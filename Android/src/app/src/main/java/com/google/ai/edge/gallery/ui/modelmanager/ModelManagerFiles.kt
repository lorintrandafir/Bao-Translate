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

package com.google.ai.edge.gallery.ui.modelmanager

import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import java.io.File

private const val TAG = "AGModelManagerFiles"

internal fun ModelManagerViewModel.isFileInExternalFilesDir(fileName: String): Boolean {
  if (externalFilesDir != null) {
    val file = File(externalFilesDir, fileName)
    return file.exists()
  } else {
    return false
  }
}

internal fun ModelManagerViewModel.isFileInDataLocalTmpDir(fileName: String): Boolean {
  val file = File("/data/local/tmp", fileName)
  return file.exists()
}

internal fun ModelManagerViewModel.deleteFileFromExternalFilesDir(fileName: String) {
  if (isFileInExternalFilesDir(fileName)) {
    val file = File(externalFilesDir, fileName)
    file.delete()
  }
}

/**
 * Deletes files from the the model imports directory whose absolute paths start with a given
 * prefix.
 */
internal fun ModelManagerViewModel.deleteFilesFromImportDir(fileName: String) {
  val dir = context.getExternalFilesDir(null) ?: return

  val prefixAbsolutePath = "${context.getExternalFilesDir(null)}${File.separator}$fileName"
  val filesToDelete =
    File(dir, IMPORTS_DIR).listFiles { dirFile, name ->
      File(dirFile, name).absolutePath.startsWith(prefixAbsolutePath)
    } ?: arrayOf()
  for (file in filesToDelete) {
    BaoLog.d(TAG, "Deleting file: ${file.name}")
    file.delete()
  }
}

internal fun ModelManagerViewModel.deleteDirFromExternalFilesDir(dir: String) {
  if (isFileInExternalFilesDir(dir)) {
    val file = File(externalFilesDir, dir)
    file.deleteRecursively()
  }
}

@androidx.annotation.VisibleForTesting
internal fun ModelManagerViewModel.isModelDownloaded(model: Model): Boolean {
  model.updatable = false
  // First, check if the model with the current (latest) version has been downloaded.
  if (checkIfModelDownloaded(model, model.version)) return true

  // If not, check if any updatable model file (previous version) has been downloaded.
  for (updatableFile in model.updatableModelFiles) {
    if (updatableFile.commitHash.isEmpty()) continue
    if (checkIfModelDownloaded(model, updatableFile.commitHash, updatableFile.fileName)) {
      // If an updatable version is found on the device, update the model's version and file name
      // to match the downloaded one, and mark it as updatable.
      model.version = updatableFile.commitHash
      model.downloadFileName = updatableFile.fileName
      model.updatable = true
      return true
    }
  }

  return false
}

internal fun ModelManagerViewModel.checkIfModelDownloaded(
  model: Model,
  version: String,
  fileName: String = model.downloadFileName,
): Boolean {
  val modelRelativePath =
    listOf(model.normalizedName, version, fileName).joinToString(File.separator)
  val downloadedFileExists =
    fileName.isNotEmpty() &&
      ((model.localModelFilePathOverride.isEmpty() &&
        isFileInExternalFilesDir(modelRelativePath)) ||
        (model.localModelFilePathOverride.isNotEmpty() &&
          File(model.localModelFilePathOverride).exists()))

  val unzippedDirectoryExists =
    model.isZip &&
      model.unzipDir.isNotEmpty() &&
      isFileInExternalFilesDir(
        listOf(model.normalizedName, version, model.unzipDir).joinToString(File.separator)
      )

  return downloadedFileExists || unzippedDirectoryExists
}
