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
import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.util.LocalCrashReportSenderFactory
import com.google.ai.edge.gallery.util.NativeCrashHandler
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.plugins.SimplePluginLoader
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)

    // ACRA: catches Java/Kotlin exceptions
    ACRA.init(this, CoreConfigurationBuilder()
      .setPluginLoader(SimplePluginLoader(LocalCrashReportSenderFactory::class.java))
      .setReportContent(
        ReportField.STACK_TRACE,
        ReportField.APP_VERSION_NAME,
        ReportField.APP_VERSION_CODE,
        ReportField.TOTAL_MEM_SIZE,
        ReportField.AVAILABLE_MEM_SIZE,
        ReportField.THREAD_DETAILS,
        ReportField.LOGCAT,
        ReportField.ANDROID_VERSION,
        ReportField.PHONE_MODEL,
        ReportField.BRAND,
      )
    )

    // xCrash: catches native SIGABRT/SIGSEGV crashes
    NativeCrashHandler.init(this)
  }

  override fun onCreate() {
    super.onCreate()

    // Check for native crash tombstones from previous run
    NativeCrashHandler.checkPendingCrash(this)

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    FirebaseApp.initializeApp(this)
  }
}
