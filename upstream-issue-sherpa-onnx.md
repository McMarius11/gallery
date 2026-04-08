# Upstream Issue für k2-fsa/sherpa-onnx

> Erstelle dieses Issue hier: https://github.com/k2-fsa/sherpa-onnx/issues/new
>
> **Title:** `Bug: generateWithCallback() crashes with SIGABRT due to JNI threading issue`
> **Label:** `bug`

---

## Summary

`OfflineTts.generateWithCallback()` crashes with SIGABRT (Fatal Signal 6) on Android when using Kokoro TTS. The `generate()` method with identical parameters works perfectly. The root cause is a JNI threading bug in `sherpa-onnx/jni/offline-tts.cc`.

## Environment

- **sherpa-onnx version:** v1.12.35 (pre-built `.so` files in `jniLibs/arm64-v8a/`)
- **Kotlin wrappers:** Copied from `sherpa-onnx/kotlin-api/` (verified to match v1.12.35 exactly)
- **TTS model:** Kokoro (`kokoro-en-v0_19`)
- **Android:** arm64-v8a, minSdk 31, targetSdk 35
- **Device RAM:** 5 GB free, `lowMemory=false`

## Reproduction

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

**Symptoms:**
- Crash occurs during native C++ → JVM callback transition
- Affects every text input, including single words like "Test"
- Initialization succeeds (sampleRate=24000, numSpeakers=11)
- 8/8 smoke tests pass when using `generate()` instead

## Root Cause Analysis

The bug is in `sherpa-onnx/jni/offline-tts.cc`, in both `generateWithCallbackImpl` (line 546) and `generateWithConfigImpl` (line 577).

### The Problem

The C++ lambda captures `JNIEnv *env` and `jobject callback` by value:

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

**Missing infrastructure:** The entire sherpa-onnx JNI codebase has **zero** instances of `JavaVM*`, `JNI_OnLoad`, `AttachCurrentThread`, or `NewGlobalRef`.

### Confirmed by Elimination

| Potential Cause | Ruled Out |
|----------------|-----------|
| Model file corruption | All files pass size validation |
| Incomplete espeak-ng files | All required files present (including `lang/gmw/en`) |
| Memory constraints | 5 GB free |
| Threading config | Crashes with `numThreads=1` |
| Text-specific issues | Crashes on ALL text |
| Kotlin wrapper mismatch | Verified: wrappers match v1.12.35 exactly |
| JNI callback signature | `([F)Ljava/lang/Integer;` is correct |

## Proposed Fix

A patch is available at [McMarius11/gallery@claude/fix-gallery-issue-1-ErQlp](https://github.com/McMarius11/gallery/blob/claude/fix-gallery-issue-1-ErQlp/fix-generateWithCallback-sigabrt.patch) — 4 files, +106/-20 lines.

### Changes:

**1. `sherpa-onnx/jni/jni.cc` — Cache `JavaVM*` in `JNI_OnLoad`**
```cpp
static JavaVM *g_jvm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
  g_jvm = vm;
  return JNI_VERSION_1_6;
}

JavaVM *GetJavaVM() { return g_jvm; }
```

**2. `sherpa-onnx/jni/common.h` — Declare `GetJavaVM()`**
```cpp
JavaVM *GetJavaVM();
```

**3. `sherpa-onnx/jni/offline-tts.cc` — Fix both lambdas**
```cpp
if (callback) {
    JavaVM *jvm = GetJavaVM();
    if (!jvm) {
      SHERPA_ONNX_LOGE("GetJavaVM() returned null");
      audio = tts->Generate(p_text, config, nullptr);  // fallback
    } else {
      jobject global_callback = env->NewGlobalRef(callback);

      auto callback_wrapper =
          [jvm, global_callback](const float *samples, int32_t n, float) -> int32_t {
        JNIEnv *cb_env = nullptr;
        bool did_attach = false;
        jint rc = jvm->GetEnv((void **)&cb_env, JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
          if (jvm->AttachCurrentThread(&cb_env, nullptr) != JNI_OK)
            return 0;
          did_attach = true;
        } else if (rc != JNI_OK) {
          return 0;
        }

        jfloatArray samples_arr = cb_env->NewFloatArray(n);
        cb_env->SetFloatArrayRegion(samples_arr, 0, n, samples);
        int32_t ret = CallCallback(cb_env, global_callback, samples_arr);
        cb_env->DeleteLocalRef(samples_arr);

        if (did_attach) jvm->DetachCurrentThread();
        return ret;
      };

      audio = tts->Generate(p_text, config, callback_wrapper);
      env->DeleteGlobalRef(global_callback);
    }
}
```

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

Without this, the linker's `local: *` hides `JNI_OnLoad` and the JVM never calls it.

### Design Decisions

- **`GetEnv` before `AttachCurrentThread`:** No-op when callback runs on the original JVM thread (common case with `numThreads=1`), only attaches when on a non-JVM thread
- **`DetachCurrentThread` only if we attached:** Does not disturb threads we didn't attach
- **Null-check on `GetJavaVM()`:** Graceful fallback to `generate()` without callback if `JNI_OnLoad` was not called
- **`NewGlobalRef` before lambda, `DeleteGlobalRef` after `Generate()`:** Lifetime exactly matches the period where the callback can be invoked

## Current Workaround

We use `generate()` instead of `generateWithCallback()` in our app. Trade-off: ~1-3s latency per sentence before audio starts playing (acceptable since text is split into sentences). All 8/8 smoke tests pass with this workaround.

## Note

This same pattern (capturing `JNIEnv*` in lambdas without thread-safe handling) may affect other JNI callbacks in the codebase. A codebase-wide audit for similar patterns would be beneficial.

## Related Issues

- #823 — Android TTS SIGABRT
- #943 — TTS crash on repeated generation
- #2223 — TTS crash on words not in lexicon
- #2347 — Crash in OfflineTts destructor after Generate
