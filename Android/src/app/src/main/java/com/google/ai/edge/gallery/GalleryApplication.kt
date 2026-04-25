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
import android.content.Intent
import com.google.ai.edge.gallery.api.LocalApiServer
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.service.LocalApiService
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun onCreate() {
    super.onCreate()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    FirebaseApp.initializeApp(this)

    // ── Local API Server ──────────────────────────────────────────────────────
    // Initialize server with app context so it can access models and data store.
    LocalApiServer.initialize(this, dataStoreRepository)

    // Auto-start the API server as a foreground service when the app starts.
    // The notification will appear until the app is killed or shutdown is called.
    val prefs = getSharedPreferences("gallery_api_prefs", MODE_PRIVATE)
    val apiEnabled = prefs.getBoolean("api_enabled", true)
    if (apiEnabled) {
      LocalApiService.start(this)
    }
  }

  override fun onTerminate() {
    LocalApiServer.stop()
    super.onTerminate()
  }
}
