package com.google.ai.edge.gallery.tts

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.ui.common.chat.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "TtsSmokeTest"
private const val TEST_TIMEOUT_MS = 15_000L

object TtsSmokeTest {

  data class TestResult(
    val testName: String,
    val text: String,
    val passed: Boolean,
    val durationMs: Long,
    val error: String? = null,
  )

  private val TEST_CASES = listOf(
    "Single word" to "Test",
    "Simple sentence" to "Hello, how are you?",
    "Numbers" to "1 2 3 4 5",
    "Longer text" to "This is a longer sentence to test the TTS engine. It has multiple parts and should take a few seconds.",
    "Special chars" to "It's a test — with dashes & symbols!",
    "Question" to "Can you hear me now?",
  )

  /** Write to tts_crash_trace.txt for forensic analysis. */
  private fun crashLog(context: Context, msg: String) {
    try {
      val file = File(context.filesDir, "tts_crash_trace.txt")
      val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        .format(java.util.Date())
      file.appendText("$ts [SmokeTest] $msg\n")
      if (file.length() > 50_000) {
        val lines = file.readLines().takeLast(100)
        file.writeText(lines.joinToString("\n") + "\n")
      }
    } catch (_: Exception) {}
  }

  /**
   * Run all TTS smoke tests sequentially.
   * Each test speaks a phrase and waits for completion with a timeout.
   * Results are logged to tts_crash_trace.txt for crash forensics.
   *
   * @param onProgress Called before each test with (stepIndex, totalSteps, testName)
   * @return List of test results
   */
  suspend fun runAll(
    context: Context,
    onProgress: (step: Int, total: Int, name: String) -> Unit,
  ): List<TestResult> {
    val appCtx = context.applicationContext
    val results = mutableListOf<TestResult>()
    val totalTests = TEST_CASES.size + 1 // +1 for engine-ready check

    crashLog(appCtx, "=== SMOKE TEST STARTED ===")
    crashLog(appCtx, "Engine: isReady=${TtsManager.isReady()}, blocked=${KokoroTtsEngine.isBlockedByCrashHistory(appCtx)}")

    // Test 0: Engine ready check (no audio)
    onProgress(0, totalTests, "Engine ready")
    val engineResult = runEngineReadyCheck(appCtx)
    results.add(engineResult)
    crashLog(appCtx, "Test 0 [Engine ready]: ${if (engineResult.passed) "PASS" else "FAIL: ${engineResult.error}"}")

    if (!engineResult.passed) {
      // No point running speak tests if engine is not ready
      crashLog(appCtx, "=== SMOKE TEST ABORTED: engine not ready ===")
      return results
    }

    // Tests 1-N: Speak each test phrase
    for ((index, testCase) in TEST_CASES.withIndex()) {
      val (testName, text) = testCase
      val step = index + 1
      onProgress(step, totalTests, testName)

      crashLog(appCtx, "Test $step [$testName]: starting, text=\"${text.take(80)}\"")
      val result = runSpeakTest(appCtx, testName, text, step)
      results.add(result)

      val status = if (result.passed) "PASS (${result.durationMs}ms)" else "FAIL: ${result.error}"
      crashLog(appCtx, "Test $step [$testName]: $status")
      Log.w(TAG, "Test $step [$testName]: $status")
    }

    val passed = results.count { it.passed }
    val failed = results.count { !it.passed }
    crashLog(appCtx, "=== SMOKE TEST COMPLETE: $passed passed, $failed failed ===")
    Log.w(TAG, "Smoke test complete: $passed passed, $failed failed")

    return results
  }

  private fun runEngineReadyCheck(context: Context): TestResult {
    val start = System.currentTimeMillis()
    val isReady = TtsManager.isReady()
    val isBlocked = KokoroTtsEngine.isBlockedByCrashHistory(context)
    val duration = System.currentTimeMillis() - start

    return when {
      isBlocked -> TestResult(
        testName = "Engine ready",
        text = "(no audio)",
        passed = false,
        durationMs = duration,
        error = "TTS blocked by previous native crashes. Re-download model to reset.",
      )
      !isReady -> TestResult(
        testName = "Engine ready",
        text = "(no audio)",
        passed = false,
        durationMs = duration,
        error = "TTS engine not ready. Ensure Kokoro model is downloaded.",
      )
      else -> TestResult(
        testName = "Engine ready",
        text = "(no audio)",
        passed = true,
        durationMs = duration,
      )
    }
  }

  private suspend fun runSpeakTest(
    context: Context,
    testName: String,
    text: String,
    stepIndex: Int,
  ): TestResult {
    val start = System.currentTimeMillis()

    // Set the speak canary with SMOKE_TEST prefix so we know which test crashed
    val prefs = context.getSharedPreferences("kokoro_tts_prefs", Context.MODE_PRIVATE)
    prefs.edit()
      .putBoolean("tts_speak_in_progress", true)
      .putLong("tts_canary_set_at", System.currentTimeMillis())
      .putString("tts_last_sentence", "SMOKE_TEST[$stepIndex] $testName: ${text.take(200)}")
      .commit()

    return try {
      val completed = withTimeoutOrNull(TEST_TIMEOUT_MS) {
        // Switch to Main for TtsManager.speak (it uses AudioTrack which may need main thread for callback)
        withContext(Dispatchers.Main) {
          suspendCancellableCoroutine { cont ->
            TtsManager.speak(text) {
              if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation { TtsManager.stop() }
          }
        }
      }

      val duration = System.currentTimeMillis() - start

      // Clear the speak canary after successful completion
      prefs.edit().putBoolean("tts_speak_in_progress", false).apply()

      if (completed != null) {
        TestResult(testName = testName, text = text, passed = true, durationMs = duration)
      } else {
        TtsManager.stop()
        TestResult(
          testName = testName,
          text = text,
          passed = false,
          durationMs = duration,
          error = "TIMEOUT after ${TEST_TIMEOUT_MS}ms",
        )
      }
    } catch (e: Exception) {
      val duration = System.currentTimeMillis() - start
      prefs.edit().putBoolean("tts_speak_in_progress", false).apply()
      crashLog(context, "Test $stepIndex [$testName] EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
      TestResult(
        testName = testName,
        text = text,
        passed = false,
        durationMs = duration,
        error = "${e.javaClass.simpleName}: ${e.message}",
      )
    }
  }
}
