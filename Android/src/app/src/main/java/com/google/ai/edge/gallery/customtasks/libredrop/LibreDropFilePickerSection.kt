/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.customtasks.libredrop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.Dimensions

/**
 * "Files to share" card: the affordance that opens the system document picker, plus the list of
 * chosen files with a per-row remove control.
 *
 * Both controls were previously absent. `FilePickerSection` accepted `onFileSelected` and
 * `onFileRemoved` callbacks and rendered neither, so `selectedFiles` could never become non-empty
 * and the Send button could never enable — the whole outbound path was unreachable from the UI
 * even though the string resource already read "No files selected. Tap to pick files."
 *
 * The card body itself is the tap target (matching that string) rather than a separate button, so
 * the empty state has an obvious hit area instead of a lone control floating under the header.
 */
@Composable
internal fun FilePickerSection(
  selectedFiles: List<SelectedFile>,
  onPickFiles: () -> Unit,
  onFileRemoved: (SelectedFile) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
      ),
  ) {
    Column(modifier = Modifier.padding(Dimensions.Spacing.medium)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.libre_drop_files_to_share),
          style = MaterialTheme.typography.titleSmall,
        )
        Row(
          modifier =
            Modifier
              .semantics { role = Role.Button }
              .clickable { onPickFiles() }
              .padding(Dimensions.Spacing.xs),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(Dimensions.Icon.small),
            tint = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.width(Dimensions.Spacing.xs))
          Text(
            text = stringResource(R.string.libre_drop_add_files),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
      Spacer(modifier = Modifier.height(Dimensions.Spacing.small))
      if (selectedFiles.isEmpty()) {
        Text(
          text = stringResource(R.string.libre_drop_no_files_selected),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier =
            Modifier
              .fillMaxWidth()
              .semantics { role = Role.Button }
              .clickable { onPickFiles() }
              .padding(vertical = Dimensions.Spacing.small),
        )
      } else {
        selectedFiles.forEach { file ->
          SelectedFileRow(file = file, onFileRemoved = onFileRemoved)
        }
      }
    }
  }
}

@Composable
private fun SelectedFileRow(
  file: SelectedFile,
  onFileRemoved: (SelectedFile) -> Unit,
) {
  val removeLabel = stringResource(R.string.libre_drop_remove_file, file.name)
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(vertical = Dimensions.Spacing.xs),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = file.name,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = formatFileSize(androidx.compose.ui.platform.LocalContext.current, file.sizeBytes),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(Dimensions.Spacing.small))
    Icon(
      Icons.Filled.Close,
      contentDescription = removeLabel,
      modifier =
        Modifier
          .size(Dimensions.Icon.small)
          .semantics { role = Role.Button }
          .clickable { onFileRemoved(file) },
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
