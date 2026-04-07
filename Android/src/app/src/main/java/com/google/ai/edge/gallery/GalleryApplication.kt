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
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.util.LocalCrashReportSenderFactory
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import org.acra.config.CoreConfigurationBuilder
import org.acra.ACRA
import org.acra.ReportField
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)

    ACRA.init(this, CoreConfigurationBuilder()
      .withReportSenderFactoryClasses(LocalCrashReportSenderFactory::class.java)
      .withReportContent(
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
      .withParallel(false)
      .withStopServicesOnCrash(false)
    )
  }

  override fun onCreate() {
    super.onCreate()

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    FirebaseApp.initializeApp(this)
  }
}
