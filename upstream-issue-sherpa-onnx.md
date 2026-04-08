# Upstream Issue für k2-fsa/sherpa-onnx

> Erstelle dieses Issue hier: https://github.com/k2-fsa/sherpa-onnx/issues/new
>
> **Title:** `Bug: generateWithCallback() crashes with SIGABRT due to JNI threading issue in offline-tts.cc`
> **Label:** `bug`
>
> --- Alles unterhalb kopieren ---

### Important Instructions ⚠️

Before submitting this issue, please **read carefully** and confirm the following:

- [x] I am using the **latest version** of `sherpa-onnx`. If not, I will upgrade and check if the issue persists.
- [x] I understand that **issues not reproducible on the latest version** may be closed without response.
- [x] I have verified that the error is reproducible using **only `sherpa-onnx`**, not code from my own project.
- [x] I will provide **detailed steps** to reproduce the issue.
- [x] I will provide **complete logs** and error messages.
- [x] I understand that **issues not following these instructions** may be closed or receive no response.

---

### Describe the issue

`OfflineTts.generateWithCallback()` crashes with SIGABRT (Fatal Signal 6) on Android when using Kokoro TTS. The `generate()` method with identical parameters works perfectly.

The root cause is a JNI threading bug in `sherpa-onnx/jni/offline-tts.cc`: the C++ lambda captures a thread-local `JNIEnv*` and a local `jobject` reference, both of which become invalid if the callback is invoked on a different thread.

The entire sherpa-onnx JNI codebase has **zero** instances of `JavaVM*`, `JNI_OnLoad`, `AttachCurrentThread`, or `NewGlobalRef` — meaning no JNI callback in the project is thread-safe.

### Steps to reproduce

1. Build sherpa-onnx v1.12.35 for Android arm64-v8a (or use pre-built `.so` files)
2. Use the Kotlin API with Kokoro TTS model (`kokoro-en-v0_19`)
3. Initialize `OfflineTts` — succeeds (sampleRate=24000, numSpeakers=11)
4. Call `generateWithCallback()` with any text:

```kotlin
// This CRASHES with SIGABRT on every text, even single words:
val audio = offlineTts.generateWithCallback(
    text = "Test",
    sid = 0,
    speed = 1.0f
) { samples -> 1 }

// This WORKS perfectly with identical parameters:
val audio = offlineTts.generate(
    text = "Test",
    sid = 0,
    speed = 1.0f
)
```

5. App crashes with `Fatal signal 6 (SIGABRT)` during the native C++ → JVM callback transition

This is **100% reproducible** — every text input crashes, including single words. 8/8 smoke tests pass when using `generate()` instead.

### Expected behavior

`generateWithCallback()` should invoke the Kotlin callback with audio samples during generation without crashing, identical to how `generate()` works but with streaming output.

### Environment

- `sherpa-onnx` version: v1.12.35 (also checked v1.12.36 changelog — not fixed)
- OS: Android (minSdk 31, targetSdk 35, arm64-v8a)
- Kotlin wrappers: Copied from `sherpa-onnx/kotlin-api/` — verified to match v1.12.35 exactly
- TTS model: Kokoro (`kokoro-en-v0_19`)
- Device RAM: 5 GB free, `lowMemory=false`
- onnxruntime: bundled `libonnxruntime.so` from sherpa-onnx v1.12.35

### Logs

```
Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE)
```

The crash occurs inside the JNI callback lambda in `generateWithCallbackImpl()`. It cannot be caught by try/catch because SIGABRT terminates the process immediately.

We added a "canary" pattern (SharedPreferences flag set before JNI call, cleared after) to confirm the crash location: it always happens during `generateWithCallback()`, never during init or `generate()`.

### Root Cause Analysis

The bug is in `sherpa-onnx/jni/offline-tts.cc`, lines 546-553 (`generateWithCallbackImpl`) and lines 577-584 (`generateWithConfigImpl`).

**The buggy code:**

```cpp
// sherpa-onnx/jni/offline-tts.cc, line 546-553
std::function<int32_t(const float *, int32_t, float)> callback_wrapper =
    [env, callback](const float *samples, int32_t n, float) -> int32_t {
  jfloatArray samples_arr = env->NewFloatArray(n);
  env->SetFloatArrayRegion(samples_arr, 0, n, samples);
  int32_t ret = CallCallback(env, callback, samples_arr);
  env->DeleteLocalRef(samples_arr);
  return ret;
};
audio = tts->Generate(p_text, config, callback_wrapper);
```

**Three JNI violations:**

| Problem | Why it causes SIGABRT |
|---------|----------------------|
| **`JNIEnv*` is thread-local** | If `tts->Generate()` invokes the callback on a different thread (e.g., ONNX Runtime thread pool), the captured `env` pointer is invalid → dangling pointer → SIGABRT |
| **No `AttachCurrentThread()`** | The callback thread has no valid JVM environment → JNI calls on an unattached thread |
| **`jobject callback` is a local reference** | Local refs are only valid within the calling JNI frame. Using them in a lambda that may outlive the frame or run on a different thread is undefined behavior per the JNI spec |

Reference: [Android NDK JNI Tips — Threads](https://developer.android.com/training/articles/perf-jni#threads)

**Ruled out:**

| Potential Cause | Ruled Out |
|----------------|-----------|
| Model file corruption | All files pass size validation |
| Incomplete espeak-ng files | All required files present (including `lang/gmw/en`) |
| Memory constraints | 5 GB free |
| Threading config | Crashes with `numThreads=1` |
| Text-specific issues | Crashes on ALL text |
| Kotlin wrapper mismatch | Verified: wrappers match v1.12.35 exactly |
| JNI callback signature | `([F)Ljava/lang/Integer;` is correct |

### Proposed Fix

A complete patch (4 files, +106/-20 lines) is available here:
[fix-generateWithCallback-sigabrt.patch](https://github.com/McMarius11/gallery/blob/claude/fix-gallery-issue-1-ErQlp/fix-generateWithCallback-sigabrt.patch)

**Summary of changes:**

**1. `sherpa-onnx/jni/jni.cc` — Cache `JavaVM*` in `JNI_OnLoad`**
```cpp
static JavaVM *g_jvm = nullptr;

SHERPA_ONNX_EXTERN_C
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
  g_jvm = vm;
  return JNI_VERSION_1_6;
}

JavaVM *GetJavaVM() { return g_jvm; }
```

**2. `sherpa-onnx/jni/common.h` — Declare `GetJavaVM()`**
```cpp
JavaVM *GetJavaVM();
```

**3. `sherpa-onnx/jni/offline-tts.cc` — Fix both lambdas with thread-safe JNI**
- Use `NewGlobalRef` for the callback object (survives across threads/JNI frames)
- In the lambda: `GetEnv`/`AttachCurrentThread` to obtain a valid `JNIEnv*` for the current thread
- `DetachCurrentThread` only if we attached (does not disturb already-attached threads)
- `DeleteGlobalRef` after `Generate()` returns
- Null-check on `GetJavaVM()` with graceful fallback to `generate()` without callback

**4. `sherpa-onnx/jni/sherpa-onnx-symbols.lds` — Export `JNI_OnLoad`**
```
{
  global:
    JNI_OnLoad;
    Java_com_k2fsa_sherpa_onnx*;
  local:
    *;
};
```
Without this, the linker's `local: *` hides `JNI_OnLoad` and the JVM never calls it → `g_jvm` stays null.

### Note

This same pattern (capturing `JNIEnv*` in lambdas without thread-safe handling) may affect other JNI callbacks in the codebase (e.g., in `online-recognizer.cc` or `voice-activity-detector.cc`). A codebase-wide audit for similar patterns would be beneficial.

### Related Issues

- #823 — Android TTS SIGABRT
- #943 — TTS crash on repeated generation
- #2223 — TTS crash on words not in lexicon
- #2347 — Crash in OfflineTts destructor after Generate
