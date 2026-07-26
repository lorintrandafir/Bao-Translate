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
import android.os.Bundle
import com.google.ai.edge.gallery.common.BaoLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.proto.Skill
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGSkillManagerVM"

@HiltViewModel
class SkillManagerViewModel
@Inject
constructor(
  val dataStoreRepository: DataStoreRepository,
  @ApplicationContext private val context: Context,
) : ViewModel() {
  private val _uiState = MutableStateFlow(SkillManagerUiState())
  val uiState = _uiState.asStateFlow()
  var skillLoaded = false

  init {
    if (SKILL_ALLOWLIST_URL.isNotEmpty()) {
      loadSkillAllowlist()
    }
  }

  fun setLoading(loading: Boolean) {
    _uiState.update { it.copy(loading = loading) }
  }

  fun setValidating(validating: Boolean) {
    _uiState.update { it.copy(validating = validating) }
  }

  fun setValidationError(error: String?) {
    _uiState.update { it.copy(validationError = error) }
  }

  fun setImportDirectoryUri(uri: Uri?) {
    _uiState.update { it.copy(importDirectoryUri = uri) }
  }

  private fun addSkill(skill: Skill, addToDataStore: Boolean) {
    _uiState.update { currentState ->
      currentState.copy(skills = currentState.skills + SkillState(skill = skill))
    }
    if (addToDataStore) {
      viewModelScope.launch { dataStoreRepository.addSkill(skill) }
    }
  }

  /** Persists a per-skill secret (fire-and-forget). */
  fun saveSecret(key: String, value: String) {
    viewModelScope.launch { dataStoreRepository.saveSecret(key = key, value = value) }
  }

  suspend fun loadSkills() {
    if (!skillLoaded) {
      setLoading(true)
      withContext(Dispatchers.IO) {
        BaoLog.d(TAG, "Loading skills index...")

        // 1. Load all skills from DataStore.
        val allDataStoreSkills = dataStoreRepository.getAllSkills()
        val dataStoreBuiltInSkills = allDataStoreSkills.filter { it.builtIn }
        val dataStoreCustomSkills = allDataStoreSkills.filter { !it.builtIn }
        BaoLog.d(
          TAG,
          "data store built-in skills:\n${dataStoreBuiltInSkills.joinToString(separator = "\n") { it.name }}",
        )
        BaoLog.d(
          TAG,
          "data store custom skills:\n${dataStoreCustomSkills.joinToString(separator = "\n") { it.name }}",
        )

        // 2. Keep track of the selection state of existing built-in skills.
        val builtInSelectionMap = dataStoreBuiltInSkills.associate {
          it.name to Pair(it.selected, it.userModifiedSelection)
        }
        BaoLog.d(TAG, "data store built-in skills selection map: $builtInSelectionMap")

        // 3. Mirror the bundled _shared runtime modules (log/result/storage/i18n)
        // into filesDir so imported/featured skills — served via
        // InternalStoragePathHandler from filesDir/skills/<name>/ — can resolve
        // the same ../../_shared/*.js module URLs as asset-backed skills.
        // Re-copied on every load so the files always match the installed APK.
        runCatching {
          val sharedDest = context.filesDir.resolve("skills/_shared")
          sharedDest.mkdirs()
          for (fileName in context.assets.list("skills/_shared").orEmpty()) {
            context.assets.open("skills/_shared/$fileName").use { input ->
              sharedDest.resolve(fileName).outputStream().use { output -> input.copyTo(output) }
            }
          }
        }.onFailure { e ->
          BaoLog.e(TAG, "Error installing skills/_shared runtime modules", e)
        }

        // 4. Read and parse SKILL.md files from assets/skills directories.
        val builtInSkills = mutableListOf<Skill>()
        runCatching {
          val skillAssetDirs = context.assets.list("skills") ?: emptyArray()
          for (dirName in skillAssetDirs) {
            // Infrastructure directories (_shared) are not skills.
            if (dirName.startsWith("_")) continue
            val skillMdPath = "skills/$dirName/SKILL.md"
            runCatching {
              context.assets.open(skillMdPath).use { inputStream ->
                val mdContent = inputStream.bufferedReader().use { it.readText() }
                val (skillProto, errors) =
                  SkillParser.convertSkillMdToProto(
                    mdContent,
                    builtIn = true,
                    // Selection state will be reconciled with DataStore later
                    selected = true,
                    importDir = "assets/skills/$dirName",
                  )
                if (errors.isNotEmpty()) {
                  BaoLog.w(TAG, "Error parsing asset skill $dirName: ${errors.joinToString(", ")}")
                } else {
                  skillProto?.let {
                    // Apply the previous selection state if the user explicitly modified it,
                    // otherwise use the default selection state.
                    val defaultSelected = it.name !in DEFAULT_DISABLED_SKILLS
                    val (persistedSelected, userModified) =
                      builtInSelectionMap[it.name] ?: Pair(defaultSelected, false)
                    val selectedState = if (userModified) persistedSelected else defaultSelected
                    builtInSkills.add(
                      it
                        .toBuilder()
                        .setSelected(selectedState)
                        .setUserModifiedSelection(userModified)
                        .build()
                    )
                    BaoLog.d(TAG, "Added built-in skill: ${it.name}")
                  }
                }
              }
            }.onFailure { e ->
              BaoLog.w(TAG, "SKILL.md not found or error reading for asset skill $dirName", e)
            }
          }
        }.onFailure { e ->
          BaoLog.e(TAG, "Error listing assets/skills", e)
        }
        BaoLog.d(
          TAG,
          "Final built-in skills:\n${builtInSkills.joinToString(separator = "\n") { "${it.name}(${it.selected})" }}",
        )

        // 5. Combine the updated built-in skills with the existing custom skills.
        val finalSkills = builtInSkills.toMutableList()
        for (customSkill in dataStoreCustomSkills) {
          if (!finalSkills.any { it.name == customSkill.name }) {
            finalSkills.add(customSkill)
          }
        }

        // 6. Update the DataStore with the combined list of skills.
        dataStoreRepository.setSkills(finalSkills)

        // 7. Update UI State with the final set of skills.
        _uiState.update { currentState ->
          currentState.copy(skills = finalSkills.map { SkillState(skill = it) })
        }

        setLoading(false)
        skillLoaded = true
      }
    }
  }

  private fun loadSkillAllowlist() {
    if (SKILL_ALLOWLIST_URL.isEmpty()) {
      BaoLog.d(TAG, "Skill allowlist URL is empty; remote featured-skills list disabled.")
      _uiState.update {
        it.copy(loadingSkillAllowlist = false, featuredSkills = emptyList())
      }
      return
    }
    _uiState.update { it.copy(loadingSkillAllowlist = true, skillAllowlistError = null) }
    viewModelScope.launch(Dispatchers.IO) {
      val result = AllowlistService.loadAllowlist(
        allowlistUrl = SKILL_ALLOWLIST_URL,
        cacheTtlMs = ALLOWLIST_CACHE_TTL_MS,
        cacheFilename = ALLOWLIST_CACHE_FILENAME,
        filesDir = context.filesDir,
      )
      _uiState.update {
        it.copy(
          loadingSkillAllowlist = false,
          featuredSkills = result.featuredSkills,
          skillAllowlistError = result.error,
        )
      }
    }
  }

  fun validateAndAddSkillFromUrl(
    url: String,
    onSuccess: () -> Unit,
    onValidationError: (error: String) -> Unit,
  ) {
    setValidating(true)
    setValidationError(null)

    viewModelScope.launch(Dispatchers.IO) {
      val result = SkillImportManager.validateFromUrl(
        url = url,
        context = context,
        currentSkills = _uiState.value.skills.map { it.skill },
      )
      result.onSuccess { skill ->
        addSkill(skill = skill, addToDataStore = true)
        BaoLog.d(TAG, "Successfully added skill from URL: ${skill.name}")
        firebaseAnalytics?.logEvent(
          GalleryEvent.SKILL_MANAGEMENT.id,
          getSkillLoggingParams(skill).apply { putString("action", SkillAction.ADD.value) },
        )
        setValidating(false)
        onSuccess()
      }.onFailure { e ->
        BaoLog.e(TAG, "Error validating skill from URL", e)
        val error = e.message ?: "Failed to validate skill"
        setValidationError(error)
        setValidating(false)
        onValidationError(error)
      }
    }
  }

  /**
   * Checks if a local skill with the given [directoryUri] already exists in the app's internal
   * storage.
   */
  fun checkLocalSkillExisted(directoryUri: Uri): Boolean {
    return SkillImportManager.checkLocalSkillExisted(context, directoryUri)
  }

  /**
   * Checks if a built-in skill with the same name as the skill defined in the provided
   * [directoryUri]'s SKILL.md file already exists.
   */
  fun checkBuiltInSkillExistedForImportedSkill(directoryUri: Uri): Boolean {
    return SkillImportManager.checkBuiltInSkillExistedForImportedSkill(
      context = context,
      directoryUri = directoryUri,
      currentSkills = _uiState.value.skills.map { it.skill },
    )
  }

  fun validateAndAddSkillFromLocalImport(
    onSuccess: () -> Unit,
    onValidationError: (error: String) -> Unit,
  ) {
    setValidating(true)
    setValidationError(null)

    val directoryUri = _uiState.value.importDirectoryUri
    if (directoryUri == null) {
      setValidating(false)
      val error = "No directory URI set."
      setValidationError(error)
      onValidationError(error)
      return
    }

    viewModelScope.launch(Dispatchers.IO) {
      val result = SkillImportManager.validateFromLocalImport(
        directoryUri = directoryUri,
        context = context,
        currentSkills = _uiState.value.skills.map { it.skill },
      )
      result.onSuccess { skillWithDir ->
        addSkill(skill = skillWithDir, addToDataStore = true)
        BaoLog.d(TAG, "Successfully added skill from local import: ${skillWithDir.name}")
        firebaseAnalytics?.logEvent(
          GalleryEvent.SKILL_MANAGEMENT.id,
          getSkillLoggingParams(skillWithDir).apply { putString("action", SkillAction.ADD.value) },
        )
        setValidating(false)
        onSuccess()
      }.onFailure { e ->
        BaoLog.e(TAG, "Error validating local skill import", e)
        val error = e.message ?: "Failed to import skill"
        setValidationError(error)
        setValidating(false)
        onValidationError(error)
      }
    }
  }

  fun deleteSkill(name: String) {
    deleteSkills(setOf(name))
  }

  fun deleteSkills(names: Set<String>) {
    val skillsToDelete =
      _uiState.value.skills.filter { names.contains(it.skill.name) }.map { it.skill }
    if (skillsToDelete.isEmpty()) {
      return
    }

    for (skill in skillsToDelete) {
      val loggingParams = getSkillLoggingParams(skill)
      BaoLog.d(
        TAG,
        "Analytics: skill_management, action=${SkillAction.DELETE.value}, params=$loggingParams",
      )
      firebaseAnalytics?.logEvent(
        GalleryEvent.SKILL_MANAGEMENT.id,
        loggingParams.apply { putString("action", SkillAction.DELETE.value) },
      )
    }

    // Update state.
    _uiState.update { currentState ->
      currentState.copy(skills = currentState.skills.filter { !names.contains(it.skill.name) })
    }

    viewModelScope.launch(Dispatchers.IO) {
      // Delete all imported files from file system.
      for (skill in skillsToDelete) {
        if (skill.importDirName.isNotEmpty()) {
          runCatching {
            val skillDir = context.filesDir.resolve(skill.importDirName)
            skillDir.deleteRecursively()
          }.onFailure { e ->
            BaoLog.w(TAG, "Failed to delete skill directory: ${skill.importDirName}", e)
          }
        }
      }

      // Delete skills from data store.
      dataStoreRepository.deleteSkills(names)
    }
  }

  fun setSkillSelected(skill: SkillState, selected: Boolean) {
    // Update state.
    val updatedSkill = skill.skill.toBuilder().setSelected(selected).build()

    firebaseAnalytics?.logEvent(
      GalleryEvent.SKILL_MANAGEMENT.id,
      getSkillLoggingParams(skill.skill).apply {
        putString("action", if (selected) SkillAction.ENABLE.value else SkillAction.DISABLE.value)
      },
    )
    val updatedSkills =
      _uiState.value.skills.map { curSkill ->
        if (curSkill.skill.name == skill.skill.name) {
          SkillState(skill = updatedSkill)
        } else {
          curSkill
        }
      }
    _uiState.update { currentState -> currentState.copy(skills = updatedSkills) }

    // Update data store.
    viewModelScope.launch(Dispatchers.IO) {
      dataStoreRepository.setSkillSelected(skill.skill, selected)
    }
  }

  fun setAllSkillsSelected(selected: Boolean) {
    // Update state.
    _uiState.update { currentState ->
      val updatedSkills =
        currentState.skills.map { skillState ->
          SkillState(skill = skillState.skill.toBuilder().setSelected(selected).build())
        }
      currentState.copy(skills = updatedSkills)
    }

    BaoLog.d(
      TAG,
      "Analytics: skill_management, action=${if (selected) SkillAction.ENABLE_ALL.value else SkillAction.DISABLE_ALL.value}",
    )
    firebaseAnalytics?.logEvent(
      GalleryEvent.SKILL_MANAGEMENT.id,
      Bundle().apply {
        putString(
          "action",
          if (selected) SkillAction.ENABLE_ALL.value else SkillAction.DISABLE_ALL.value,
        )
      },
    )

    // Update data store.
    viewModelScope.launch(Dispatchers.IO) { dataStoreRepository.setAllSkillsSelected(selected) }
  }

  fun getSelectedSkills(): List<Skill> {
    return _uiState.value.skills.filter { it.skill.selected }.map { it.skill }
  }

  fun getSkill(name: String): Skill? {
    return _uiState.value.skills.firstOrNull { it.skill.name == name }?.skill
  }

  fun getJsSkillUrl(skillName: String, scriptName: String): String? {
    val skill = getSkill(name = skillName) ?: return null
    var baseUrl = ""
    // Construct a local URL for imported skill and built-in skills.
    if (skill.importDirName.isNotEmpty()) {
      baseUrl = "$LOCAL_URL_BASE/${skill.importDirName}"
    }
    // Use skill.skillUrl if set.
    else if (skill.skillUrl.isNotEmpty()) {
      baseUrl = skill.skillUrl
    }
    if (baseUrl.isEmpty()) {
      return null
    }
    return "$baseUrl/scripts/$scriptName"
  }

  fun getJsSkillWebviewUrl(skillName: String, url: String): String {
    val skill = getSkill(name = skillName) ?: return url

    // Return the url if it is an absolute url.
    if (url.startsWith("http")) {
      return url
    }

    var baseUrl = ""
    // Construct a local URL for imported skill.
    if (skill.importDirName.isNotEmpty()) {
      baseUrl = "$LOCAL_URL_BASE/${skill.importDirName}"
    }
    // Use skill.skillUrl if set.
    else if (skill.skillUrl.isNotEmpty()) {
      baseUrl = skill.skillUrl
    }
    if (baseUrl.isEmpty()) {
      return url
    }
    return "$baseUrl/assets/$url"
  }

  fun getSelectedSkillsNamesAndDescriptions(): String {
    return this.getSelectedSkills().joinToString("\n") { skill ->
      "- ${skill.name}: ${skill.description}"
    }
  }

  /** Saves or updates a custom skill. */
  fun saveSkillEdit(
    index: Int,
    name: String,
    description: String,
    instructions: String,
    scriptsContent: Map<String, String>,
    onSuccess: () -> Unit,
    onError: (error: String) -> Unit,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {

        BaoLog.d(TAG, "saveSkillEdit: $name")

        val isNewSkill = index < 0 || index >= _uiState.value.skills.size

        if (isNewSkill) {
          BaoLog.d(TAG, "Saving new skill: $name")

          // Check for name conflict
          if (_uiState.value.skills.any { it.skill.name == name }) {
            val error = "A skill with the name '${name}' already exists."
            BaoLog.w(TAG, error)
            onError(error)
            return@launch
          }

          val normalizedName = name.replace("\\s+".toRegex(), "-")
          val skillDestDir = context.filesDir.resolve("skills/${normalizedName}")
          val scriptDestDir = File(skillDestDir, "scripts")
          // If the directory exists from a previous failed attempt, clear it.
          if (skillDestDir.exists()) {
            BaoLog.w(
              TAG,
              "Skill destination directory already exists for new skill: ${skillDestDir.path}, deleting.",
            )
            skillDestDir.deleteRecursively()
          }

          // Create directories
          skillDestDir.mkdirs()
          scriptDestDir.mkdirs()
          val skillMdFile = File(skillDestDir, "SKILL.md")

          // Write SKILL.md
          SkillParser.writeSkillMd(skillMdFile, normalizedName, description, instructions)

          // Save scripts
          SkillParser.saveScripts(scriptDestDir, scriptsContent)

          // Create and add new skill proto
          val newSkill =
            Skill.newBuilder()
              .setName(normalizedName)
              .setDescription(description)
              .setInstructions(instructions)
              .setBuiltIn(false)
              .setSelected(true)
              .setSkillUrl("")
              .setImportDirName(skillDestDir.relativeTo(context.filesDir).path)
              .build()
          addSkill(newSkill, addToDataStore = true)
          onSuccess()
        } else {
          BaoLog.d(TAG, "Saving skill edit: $name")

          // Editing existing skill
          val existingSkillState = _uiState.value.skills[index]
          val existingSkill = existingSkillState.skill

          val oldName = existingSkill.name
          val normalizedNewName = name.replace("\\s+".toRegex(), "-")
          val newSkillDestDir = context.filesDir.resolve("skills/${normalizedNewName}")
          val newScriptDestDir = File(newSkillDestDir, "scripts")
          val newSkillMdFile = File(newSkillDestDir, "SKILL.md")

          if (existingSkill.builtIn) {
            onError("Cannot edit built-in skills.")
            return@launch
          }

          var updatedImportDirName = existingSkill.importDirName

          if (oldName != normalizedNewName) {
            BaoLog.d(TAG, "Renaming skill from $oldName to $normalizedNewName")

            // Check for name conflict with the new name
            if (_uiState.value.skills.any { it.skill.name == normalizedNewName }) {
              val error = "A skill with the name '${normalizedNewName}' already exists."
              BaoLog.w(TAG, error)
              onError(error)
              return@launch
            }

            val oldSkillDestDir = context.filesDir.resolve(existingSkill.importDirName)
            if (oldSkillDestDir.exists()) {
              BaoLog.d(
                TAG,
                "Renaming directory from ${oldSkillDestDir.path} to ${newSkillDestDir.path}",
              )
              if (!oldSkillDestDir.renameTo(newSkillDestDir)) {
                val error =
                  "Failed to rename skill directory from ${oldSkillDestDir.name} to ${newSkillDestDir.name}."
                BaoLog.e(TAG, error)
                onError(error)
                return@launch
              }
              updatedImportDirName = newSkillDestDir.relativeTo(context.filesDir).path
            } else {
              BaoLog.w(TAG, "Old skill directory not found: ${oldSkillDestDir.path}")
              // If the old directory doesn't exist, create the new one.
              newSkillDestDir.mkdirs()
            }
          }

          // Update SKILL.md
          SkillParser.writeSkillMd(newSkillMdFile, normalizedNewName, description, instructions)

          // Update scripts: Clear existing scripts and save new ones.
          newScriptDestDir.deleteRecursively()
          newScriptDestDir.mkdirs()
          SkillParser.saveScripts(newScriptDestDir, scriptsContent)

          // Update skill proto in state and data store
          val updatedSkill =
            existingSkill
              .toBuilder()
              .setName(normalizedNewName)
              .setDescription(description)
              .setInstructions(instructions)
              .setImportDirName(updatedImportDirName)
              .build()

          // Update state
          _uiState.update { currentState ->
            val updatedSkillsList =
              currentState.skills.mapIndexed { i, skillState ->
                if (i == index) SkillState(skill = updatedSkill) else skillState
              }
            currentState.copy(skills = updatedSkillsList)
          }

          // Update data store
          updateSkillInDataStore(oldName, updatedSkill)
          onSuccess()
        }
      }.onFailure { e ->
        BaoLog.e(TAG, "Error saving skill edit", e)
        onError("Failed to save skill: ${e.message}")
      }
    }
  }

  /** Loads the content of skill scripts from the local file system. */
  fun loadSkillScriptsContent(skill: Skill, onDone: (Map<String, String>) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      if (skill.importDirName.isEmpty()) {
        BaoLog.d(TAG, "Skill ${skill.name} has no import directory, returning empty scripts.")
        withContext(Dispatchers.Default) { onDone(emptyMap()) }
        return@launch
      }

      val skillDir = context.filesDir.resolve(skill.importDirName)
      val scriptDir = File(skillDir, "scripts")

      if (!scriptDir.exists() || !scriptDir.isDirectory) {
        BaoLog.w(TAG, "Script directory not found for skill ${skill.name}: ${scriptDir.path}")
        withContext(Dispatchers.Default) { onDone(emptyMap()) }
        return@launch
      }

      val scriptsContent = mutableMapOf<String, String>()
      for (file in scriptDir.listFiles() ?: emptyArray()) {
        if (file.isFile && (file.name.endsWith(".html") || file.name.endsWith(".js"))) {
          runCatching {
            val content = file.readText()
            scriptsContent[file.name] = content
            BaoLog.d(TAG, "Loaded script ${file.name} for skill ${skill.name}")
          }.onFailure { e ->
            BaoLog.e(TAG, "Error reading script file ${file.name} for skill ${skill.name}", e)
            scriptsContent[file.name] = "" // Use empty string on error
          }
        }
      }
      withContext(Dispatchers.Default) { onDone(scriptsContent) }
    }
  }

  /** Deletes a specific script file associated with a locally imported skill. */
  fun deleteSkillScript(skill: Skill, scriptName: String) {
    if (skill.importDirName.isEmpty()) {
      BaoLog.d(TAG, "Skill ${skill.name} is not locally imported, cannot delete script.")
      return
    }

    viewModelScope.launch(Dispatchers.IO) {
      val skillDir = context.filesDir.resolve(skill.importDirName)
      val scriptDir = File(skillDir, "scripts")
      val scriptFile = File(scriptDir, scriptName)

      if (scriptFile.exists()) {
        runCatching {

          if (scriptFile.delete()) {
            BaoLog.d(TAG, "Successfully deleted script: ${scriptFile.path}")
          } else {
            BaoLog.w(TAG, "Failed to delete script: ${scriptFile.path}")
          }

}.onFailure { e ->

          BaoLog.e(TAG, "Error deleting script ${scriptFile.path}", e)

}
      } else {
        BaoLog.d(TAG, "Script file not found, ignoring delete: ${scriptFile.path}")
      }
    }
  }

  /** Checks if a skill with the given [skillName] is currently selected. */
  fun isSkillSelected(skillName: String): Boolean {
    return _uiState.value.skills.firstOrNull { it.skill.name == skillName }?.skill?.selected == true
  }

  private fun updateSkillInDataStore(oldName: String, updatedSkill: Skill) {
    viewModelScope.launch(Dispatchers.IO) {
      val allSkills = dataStoreRepository.getAllSkills()
      val updatedList = allSkills.map { if (it.name == oldName) updatedSkill else it }
      dataStoreRepository.setSkills(updatedList)
    }
  }

  private fun getSkillSource(skill: Skill): SkillSource {
    val isFeatured =
      skill.skillUrl.isNotEmpty() &&
        _uiState.value.featuredSkills.any { it.skillUrl == skill.skillUrl }
    return when {
      skill.builtIn -> SkillSource.BUILTIN
      isFeatured -> SkillSource.FEATURED
      skill.skillUrl.isNotEmpty() -> SkillSource.REMOTE_URL
      skill.importDirName.isNotEmpty() -> SkillSource.LOCAL_IMPORT
      else -> SkillSource.UNKNOWN
    }
  }

  /**
   * Generates a short 4-character hash to act as a stable ID. This solves the 100-character limit
   * for list logging in GA4 AND allows us to distinguish between different custom skills in
   * reports. Note: When we migrate to Cleancut or a similar service that doesn't have severe
   * character limits, we can drop the human-readable skill_name from setup events and rely purely
   * on this hash ID.
   */
  fun getSkillShortId(skill: Skill): String {
    val source = getSkillSource(skill)
    val identifier =
      when (source) {
        SkillSource.BUILTIN,
        SkillSource.FEATURED -> skill.name
        SkillSource.LOCAL_IMPORT -> skill.importDirName
        else -> skill.skillUrl
      }
    if (identifier.isEmpty()) return "xxxx"

    val prefix =
      when (source) {
        SkillSource.BUILTIN -> "b_"
        SkillSource.FEATURED -> "f_"
        SkillSource.LOCAL_IMPORT -> "l_"
        else -> "c_"
      }

    return runCatching {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      val hashBytes = digest.digest(identifier.toByteArray())
      val hexString = hashBytes.joinToString("") { "%02x".format(it) }
      prefix + hexString.take(4)
    }.getOrElse {
      prefix + "fail"
    }
  }

  private fun getSkillLoggingParams(skill: Skill): Bundle {
    val source = getSkillSource(skill)
    val skillName =
      if (source == SkillSource.BUILTIN || source == SkillSource.FEATURED) skill.name
      else "custom_skill"
    val bundle =
      Bundle().apply {
        putString("source", source.sourceName)
        putString("skill_name", skillName)
        putString("skill_id", getSkillShortId(skill))
      }
    return bundle
  }
}
