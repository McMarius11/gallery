package com.google.ai.edge.gallery.util

import android.content.Context
import java.io.File

private const val CRASH_FILE = "crash_log.txt"

/**
 * Utilities for reading and clearing persisted crash logs.
 * Crash reports are written by xCrash via [NativeCrashHandler].
 */
object CrashLogReader {

  fun readCrashLog(context: Context): String? {
    val file = File(context.filesDir, CRASH_FILE)
    return if (file.exists()) file.readText() else null
  }

  fun clearCrashLog(context: Context) {
    File(context.filesDir, CRASH_FILE).delete()
  }

  /**
   * Read the TTS crash trace file. This file is written by KokoroTtsEngine
   * before each dangerous native call and survives process death.
   */
  fun readTtsCrashTrace(context: Context): String? {
    val file = File(context.filesDir, "tts_crash_trace.txt")
    return if (file.exists()) file.readText() else null
  }

  fun clearTtsCrashTrace(context: Context) {
    File(context.filesDir, "tts_crash_trace.txt").delete()
  }
}
