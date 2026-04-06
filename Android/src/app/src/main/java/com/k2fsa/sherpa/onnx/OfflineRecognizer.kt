// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
)

data class OfflineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
)

data class OfflineParaformerModelConfig(
    var model: String = "",
)

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "",
    var task: String = "transcribe",
    var tailPaddings: Int = -1,
)

data class OfflineNemoEncDecCtcModelConfig(
    var model: String = "",
)

data class OfflineTdnnModelConfig(
    var model: String = "",
)

data class OfflineZipformerCtcModelConfig(
    var model: String = "",
)

data class OfflineWenetCtcModelConfig(
    var model: String = "",
)

data class OfflineSenseVoiceModelConfig(
    var model: String = "",
    var language: String = "",
    var useInverseTextNormalization: Boolean = false,
)

data class OfflineMoonshineModelConfig(
    var preprocessor: String = "",
    var encoder: String = "",
    var uncachedDecoder: String = "",
    var cachedDecoder: String = "",
)

data class OfflineModelConfig(
    var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    var paraformer: OfflineParaformerModelConfig = OfflineParaformerModelConfig(),
    var nemoCtc: OfflineNemoEncDecCtcModelConfig = OfflineNemoEncDecCtcModelConfig(),
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var tdnn: OfflineTdnnModelConfig = OfflineTdnnModelConfig(),
    var zipformerCtc: OfflineZipformerCtcModelConfig = OfflineZipformerCtcModelConfig(),
    var wenetCtc: OfflineWenetCtcModelConfig = OfflineWenetCtcModelConfig(),
    var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    var moonshine: OfflineMoonshineModelConfig = OfflineMoonshineModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class OfflineLMConfig(
    var model: String = "",
    var scale: Float = 1.0f,
)

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var lmConfig: OfflineLMConfig = OfflineLMConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String,
)

class OfflineStream(var ptr: Long) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveformImpl(ptr, samples = samples, sampleRate = sampleRate)

    fun free() {
        if (ptr != 0L) {
            deleteStream(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        free()
    }

    private external fun acceptWaveformImpl(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun deleteStream(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

class OfflineRecognizer(
    assetManager: AssetManager? = null,
    var config: OfflineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    fun createStream(hotwords: String = ""): OfflineStream {
        val p = if (hotwords.isEmpty()) {
            createStream(ptr)
        } else {
            createStreamWithHotwords(ptr, hotwords)
        }
        return OfflineStream(p)
    }

    fun decode(stream: OfflineStream) = decode(ptr, stream.ptr)

    fun getResult(stream: OfflineStream): OfflineRecognizerResult =
        getResult(ptr, stream.ptr)

    fun free() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        free()
    }

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineRecognizerConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineRecognizerConfig,
    ): Long

    private external fun delete(ptr: Long)

    private external fun createStream(ptr: Long): Long

    private external fun createStreamWithHotwords(ptr: Long, hotwords: String): Long

    private external fun decode(ptr: Long, streamPtr: Long)

    private external fun getResult(ptr: Long, streamPtr: Long): OfflineRecognizerResult

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
