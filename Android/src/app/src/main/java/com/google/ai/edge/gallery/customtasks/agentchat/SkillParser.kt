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

import com.google.ai.edge.gallery.common.BaoLog
import com.google.ai.edge.gallery.proto.Skill
import java.io.File

private const val TAG = "AGSkillParser"

object SkillParser {

  /**
   * Converts the content of a skill.md file to a [Skill] proto.
   *
   * The expected format is:
   * ```
   * ---
   * name: name-of-the-skill
   * description: description of the skill
   * metadata:
   *   key: value
   * ---
   *
   * other instructions text
   * ```
   *
   * @return A [Pair] containing the parsed [Skill] proto (or null if errors occurred) and a list of
   *   error messages.
   */
  fun convertSkillMdToProto(
    mdContent: String,
    builtIn: Boolean,
    selected: Boolean,
    skillUrl: String = "",
    importDir: String = "",
  ): Pair<Skill?, List<String>> {
    val parts = mdContent.split("---")
    val errors = mutableListOf<String>()

    if (parts.size < 3) {
      errors.add("Invalid format: Expected at least two '---' sections.")
      return Pair(null, errors)
    }

    // Part 1: Header (index 1)
    val header = parts[1].trim()
    var name: String? = null
    var description: String? = null
    var requireSecret = false
    var requireSecretDescription = ""
    var homepage: String? = null

    var startMetadata = false
    for (line in header.lines()) {
      val trimmedLine = line.trim()
      if (trimmedLine == "metadata:") {
        startMetadata = true
        continue
      }
      if (!startMetadata) {
        when {
          trimmedLine.startsWith("name:") -> name = trimmedLine.substringAfter("name:").trim()
          trimmedLine.startsWith("description:") ->
            description = trimmedLine.substringAfter("description:").trim()
        }
      } else {
        when {
          trimmedLine.startsWith("require-secret:") ->
            requireSecret = trimmedLine.substringAfter("require-secret:").trim().toBoolean()
          trimmedLine.startsWith("require-secret-description:") ->
            requireSecretDescription =
              trimmedLine.substringAfter("require-secret-description:").trim()
          trimmedLine.startsWith("homepage:") ->
            homepage = trimmedLine.substringAfter("homepage:").trim()
        }
      }
    }

    if (name.isNullOrEmpty()) {
      errors.add("Missing or empty 'name' in the header.")
    }
    if (description.isNullOrEmpty()) {
      errors.add("Missing or empty 'description' in the header.")
    }

    // Part 2: Instructions (index 2 onwards)
    val instructions = parts.drop(2).joinToString("---").trim()

    if (errors.isNotEmpty()) {
      return Pair(null, errors)
    }

    val safeName = name ?: return Pair(null, listOf("Name is required"))
    val safeDescription = description ?: return Pair(null, listOf("Description is required"))

    val skill =
      Skill.newBuilder()
        .setName(safeName)
        .setDescription(safeDescription)
        .setInstructions(instructions)
        .setBuiltIn(builtIn)
        .setSelected(selected)
        .setSkillUrl(skillUrl)
        .setRequireSecret(requireSecret)
        .setRequireSecretDescription(requireSecretDescription)
        .setHomepage(homepage ?: "")
        .setImportDirName(importDir)
        .build()

    return Pair(skill, emptyList())
  }

  fun writeSkillMd(
    skillMdFile: File,
    name: String,
    description: String,
    instructions: String,
  ) {
    BaoLog.d(TAG, "Writing skill.md: ${skillMdFile.path}")
    val mdContent =
      """
    ---
    name: $name
    description: $description
    ---

    $instructions
    """
        .trimIndent()
    skillMdFile.writeText(mdContent)
  }

  fun saveScripts(scriptDestDir: File, scriptsContent: Map<String, String>) {
    scriptDestDir.mkdirs() // Ensure directory exists

    // Clear existing files in the script directory
    scriptDestDir.listFiles()?.forEach { it.delete() }

    for ((scriptName, content) in scriptsContent) {
      val scriptFile = File(scriptDestDir, scriptName)
      BaoLog.d(TAG, "Saving script: ${scriptFile.path}")
      runCatching {
        scriptFile.writeText(content)
        BaoLog.d(TAG, "Saved script: ${scriptFile.path}")
      }.onFailure { e ->
        BaoLog.e(TAG, "Error saving script ${scriptName} to ${scriptFile.path}", e)
      }
    }
  }
}
