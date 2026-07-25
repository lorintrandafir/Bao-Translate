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

package com.google.ai.edge.gallery

import android.app.Application
import com.google.ai.edge.gallery.customtasks.libredrop.LibreDropConsentActivity
import com.google.ai.edge.gallery.customtasks.libredrop.service.receiver.ReceiverForegroundService
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.notifications.NotificationScheduleManager
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Owns process-level initialization before the Compose UI starts. */
@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var notificationScheduleManager: NotificationScheduleManager

  /** Loads persisted app settings and services configured for this build. */
  override fun onCreate() {
    super.onCreate()
    notificationScheduleManager.initialize()
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    // The LibreDrop receiver service lives in a package that must not statically depend on
    // MainActivity, so it takes the tap target as a process-wide field. Without this the
    // persistent receiver notification is untappable.
    //
    ReceiverForegroundService.openAppTarget = MainActivity::class.java
    // The trampoline renders the pending ConsentRegistry entry and submits the decision through
    // the same sink the notification actions use, so the two surfaces cannot disagree.
    ReceiverForegroundService.consentTrampolineTarget = LibreDropConsentActivity::class.java

    if (BuildConfig.FIREBASE_CONFIGURED) {
      FirebaseApp.initializeApp(this)
    }
  }
}
