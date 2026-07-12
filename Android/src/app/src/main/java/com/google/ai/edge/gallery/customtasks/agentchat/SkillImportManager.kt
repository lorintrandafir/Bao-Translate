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
import android.net.Uri
import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.proto.Skill
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.URL

private const val TAG = "AGSkillImportManager"

object SkillImportManager {

  fun getSkillDestinationDir(context: Context, originalImportDirName: String): File {
    val normalizedDirName = originalImportDirName.replace("\\s+".toRegex(), "-")
    val newImportDirName = "skills/${normalizedDirName}"
    return context.filesDir.resolve(newImportDirName)
  }

  fun checkLocalSkillExisted(context: Context, directoryUri: Uri): Boolean {
    val originalImportDirName = getDisplayName(context, directoryUri)
    if (originalImportDirName.isEmpty()) {
      return false
    }
    val destDir = getSkillDestinationDir(context, originalImportDirName)
    return destDir.exists()
  }

  fun checkBuiltInSkillExistedForImportedSkill(
    context: Context,
    directoryUri: Uri,
    currentSkills: List<Skill>,
  ): Boolean {
    BaoLog.d(TAG, "Checking built-in skill existed for imported skill: $directoryUri")

    val rootFile = DocumentFile.fromTreeUri(context, directoryUri)
    val skillMdFile = rootFile?.findFile("SKILL.md")

    if (skillMdFile == null || !skillMdFile.exists()) {
      BaoLog.w(TAG, "SKILL.md not found in the selected directory for built-in check.")
      return false
    }

    val mdContent =
      runCatching {
          context.contentResolver.openInputStream(skillMdFile.uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
          }
        }
        .getOrElse { e ->
          BaoLog.e(TAG, "Error reading SKILL.md for built-in check", e)
          return false
        } ?: ""

    if (mdContent.isEmpty()) {
      BaoLog.w(TAG, "SKILL.md is empty for built-in check.")
      return false
    }

    val (skillProto, errors) =
      SkillParser.convertSkillMdToProto(mdContent, builtIn = false, selected = false)

    if (errors.isNotEmpty() || skillProto == null) {
      BaoLog.w(TAG, "Error parsing SKILL.md for built-in check: ${errors.joinToString(", ")}")
      return false
    }

    val importedSkillName = skillProto.name
    return currentSkills.any { it.builtIn && it.name == importedSkillName }
  }

  /**
   * Validates a skill from a remote URL. Returns the parsed [Skill] on success.
   */
  suspend fun validateFromUrl(
    url: String,
    context: Context,
    currentSkills: List<Skill>,
  ): Result<Skill> {
    return runCatching {
      BaoLog.d(TAG, "Validating skill from URL: $url")

      // 1. Normalize the URL: remove trailing "/SKILL.md" or "/".
      var normalizedUrl = url
      if (normalizedUrl.endsWith("/SKILL.md")) {
        normalizedUrl = normalizedUrl.dropLast("/SKILL.md".length)
      }
      if (normalizedUrl.endsWith("/")) {
        normalizedUrl = normalizedUrl.dropLast(1)
      }

      // Validate scheme is https.
      val parsedUri = URI(normalizedUrl)
      if (parsedUri.scheme?.lowercase() != "https") {
        BaoLog.w(TAG, "Rejected non-https URL scheme: ${parsedUri.scheme}")
        throw IllegalArgumentException(
          "Only HTTPS URLs are allowed. Got: ${parsedUri.scheme ?: "none"}"
        )
      }

      val skillMdUrl = "$normalizedUrl/SKILL.md"
      BaoLog.d(TAG, "Fetching SKILL.md from: $skillMdUrl")

      // 2. Read url/SKILL.md.
      val mdContent =
        runCatching {
            val connection = com.google.ai.edge.gallery.common.network.HttpClient.openConnection(
              url = URL(skillMdUrl),
            )
            InputStreamReader(connection.getInputStream()).use { reader -> reader.readText() }
          }.getOrElse { e ->
            BaoLog.e(TAG, "Error fetching SKILL.md from $skillMdUrl", e)
            throw Exception("Failed to fetch SKILL.md: ${e.message}")
          }

      if (mdContent.isEmpty()) {
        throw Exception("SKILL.md is empty at $skillMdUrl")
      }

      // 3. If it exists, read and convert it to proto.
      val (skillProto, errors) =
        SkillParser.convertSkillMdToProto(
          mdContent,
          builtIn = false,
          selected = true,
          skillUrl = normalizedUrl,
        )

      // 4. If conversion failed, report error.
      if (errors.isNotEmpty()) {
        throw Exception("Error parsing SKILL.md from $skillMdUrl: ${errors.joinToString(", ")}")
      }

      skillProto?.let { skill ->
        // 5. Check if the name already exists.
        if (currentSkills.any { curSkill -> curSkill.name == skill.name }) {
          throw Exception("A skill with the name '${skill.name}' already exists.")
        }
        skill
      } ?: throw Exception("Unknown error during SKILL.md conversion.")
    }
  }

  /**
   * Validates a skill imported from a local directory URI. Returns the parsed [Skill] with
   * importDirName set on success. Strips the old skill directory if [onDeleteSkill] needs it.
   */
  suspend fun validateFromLocalImport(
    directoryUri: Uri,
    context: Context,
    currentSkills: List<Skill>,
  ): Result<Skill> {
    return runCatching {
      BaoLog.d(TAG, "Validating skill from directory URI: $directoryUri")

      // Get the DocumentFile representing the selected directory
      val rootFile = DocumentFile.fromTreeUri(context, directoryUri)

      // Find the SKILL.md file within that directory
      val skillMdFile = rootFile?.findFile("SKILL.md")

      if (skillMdFile == null || !skillMdFile.exists()) {
        throw Exception("SKILL.md not found in the selected directory.")
      }

      // Read the content using the correctly resolved URI
      val mdContent =
        runCatching {
            context.contentResolver.openInputStream(skillMdFile.uri)?.use { inputStream ->
              inputStream.bufferedReader().use { it.readText() }
            }
          }
          .getOrElse { e ->
            BaoLog.e(TAG, "Error reading SKILL.md", e)
            throw Exception("Failed to read SKILL.md: ${e.message}")
          } ?: ""

      val (skillProto, errors) =
        SkillParser.convertSkillMdToProto(mdContent, builtIn = false, selected = true)

      if (errors.isNotEmpty()) {
        throw Exception("Error parsing SKILL.md: ${errors.joinToString(", ")}")
      }

      skillProto?.let {
        // Successfully parsed the skill. Add the directory name.
        val originalImportDirName = getDisplayName(context, directoryUri)
        val destDir = getSkillDestinationDir(context, originalImportDirName)
        val newImportDirName = destDir.relativeTo(context.filesDir).path

        // Create the destination directory.
        if (destDir.exists()) {
          BaoLog.d(TAG, "Destination directory already exists, deleting: ${destDir.path}")
          destDir.deleteRecursively()
        }
        if (!destDir.exists()) {
          destDir.mkdirs()
        }

        // Check if the skill already exists.
        if (currentSkills.any { curSkill -> curSkill.name == it.name }) {
          throw Exception("A skill with the name '${it.name}' already exists.")
        }

        val sourceDocumentFile = DocumentFile.fromTreeUri(context, directoryUri)
        if (sourceDocumentFile == null) {
          BaoLog.e(TAG, "Failed to get DocumentFile from URI: $directoryUri")
          throw Exception("Failed to access the selected directory.")
        }

        // Records the first copy failure so the import is aborted instead of falsely succeeding.
        var copyFailure: Throwable? = null
        // Recursive function to copy a DocumentFile to a File
        fun copyDocumentFile(source: DocumentFile, dest: File) {
          if (source.isDirectory) {
            dest.mkdirs()
            for (child in source.listFiles()) {
              val childName = child.name ?: continue
              val childDest = File(dest, childName)
              copyDocumentFile(child, childDest)
            }
          } else if (source.isFile) {
            runCatching {
                BaoLog.d(TAG, "Copying file ${source.name} to ${dest.path}")
                context.contentResolver.openInputStream(source.uri)?.use { inputStream ->
                  dest.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }
                }
              }
              .onFailure { e ->
                BaoLog.e(TAG, "Error copying file ${source.name} to ${dest.path}", e)
                if (copyFailure == null) copyFailure = e
              }
          }
        }

        // Start copying from the root of the selected directory
        copyDocumentFile(sourceDocumentFile, destDir)

        // Abort the import if any file failed to copy — do not report false success.
        copyFailure?.let { e ->
          destDir.deleteRecursively()
          throw Exception("Failed to copy skill files: ${e.message}")
        }

        // Update the skill proto with the new import directory name.
        it.toBuilder().setImportDirName(newImportDirName).build()
      } ?: throw Exception("Unknown error during SKILL.md conversion.")
    }
  }
}
