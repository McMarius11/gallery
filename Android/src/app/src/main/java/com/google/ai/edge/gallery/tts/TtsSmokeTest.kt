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
    val totalTests = TEST_CASES.size + 2 // +1 engine check, +1 generate-without-callback

    crashLog(appCtx, "=== SMOKE TEST STARTED ===")
    crashLog(appCtx, "Engine: isReady=${TtsManager.isReady()}, blocked=${KokoroTtsEngine.isBlockedByCrashHistory(appCtx)}")

    // Test 0: Engine ready check (no audio)
    onProgress(0, totalTests, "Engine ready")
    val engineResult = runEngineReadyCheck(appCtx)
    results.add(engineResult)
    crashLog(appCtx, "Test 0 [Engine ready]: ${if (engineResult.passed) "PASS" else "FAIL: ${engineResult.error}"}")

    if (!engineResult.passed) {
      crashLog(appCtx, "=== SMOKE TEST ABORTED: engine not ready ===")
      return results
    }

    // Test 1: generate() WITHOUT callback — isolates phonemization/inference from callback
    // If this crashes: problem is in espeak-ng phonemization or ONNX inference
    // If this works but later tests crash: problem is the JNI callback mechanism
    onProgress(1, totalTests, "generate() no callback")
    crashLog(appCtx, "Test 1 [generate() no callback]: starting")
    val genResult = runGenerateWithoutCallbackTest(appCtx)
    results.add(genResult)
    val genStatus = if (genResult.passed) "PASS (${genResult.durationMs}ms)" else "FAIL: ${genResult.error}"
    crashLog(appCtx, "Test 1 [generate() no callback]: $genStatus")
    Log.w(TAG, "Test 1 [generate() no callback]: $genStatus")

    // Tests 2-N: Speak each test phrase via TtsManager.speak() → generateWithCallback()
    for ((index, testCase) in TEST_CASES.withIndex()) {
      val (testName, text) = testCase
      val step = index + 2
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

  /**
   * Test generate() WITHOUT callback on a background thread.
   * This isolates whether the crash is in phonemization/ONNX inference vs the callback.
   */
  private suspend fun runGenerateWithoutCallbackTest(context: Context): TestResult {
    val start = System.currentTimeMillis()
    val text = "Test"

    val prefs = context.getSharedPreferences("kokoro_tts_prefs", Context.MODE_PRIVATE)
    prefs.edit()
      .putBoolean("tts_speak_in_progress", true)
      .putLong("tts_canary_set_at", System.currentTimeMillis())
      .putString("tts_last_sentence", "SMOKE_TEST[1] generate() no callback: $text")
      .commit()

    return try {
      val numSamples = withContext(Dispatchers.Default) {
        // Access the engine directly — TtsManager doesn't expose generate()
        val engine = TtsManager.getEngine()
        if (engine is KokoroTtsEngine) {
          engine.testGenerateWithoutCallback(text)
        } else {
          -2 // Not a KokoroTtsEngine
        }
      }
      val duration = System.currentTimeMillis() - start
      prefs.edit().putBoolean("tts_speak_in_progress", false).apply()

      when {
        numSamples > 0 -> TestResult(
          testName = "generate() no callback",
          text = text,
          passed = true,
          durationMs = duration,
          error = null,
        )
        numSamples == -2 -> TestResult(
          testName = "generate() no callback",
          text = text,
          passed = false,
          durationMs = duration,
          error = "Engine is not KokoroTtsEngine",
        )
        else -> TestResult(
          testName = "generate() no callback",
          text = text,
          passed = false,
          durationMs = duration,
          error = "generate() returned $numSamples samples",
        )
      }
    } catch (e: Exception) {
      val duration = System.currentTimeMillis() - start
      prefs.edit().putBoolean("tts_speak_in_progress", false).apply()
      TestResult(
        testName = "generate() no callback",
        text = text,
        passed = false,
        durationMs = duration,
        error = "${e.javaClass.simpleName}: ${e.message}",
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
