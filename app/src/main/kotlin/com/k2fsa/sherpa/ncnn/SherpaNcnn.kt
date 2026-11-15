// SPDX-License-Identifier: Apache-2.0
// Copyright 2025-2026 ZorvAI
// Adapted from ZorvAI 036e4cdc4e0d5c8aa3e8dd192aa4ddf441984e24. See offline_asr/NOTICE.md.
package com.k2fsa.sherpa.ncnn
import android.content.res.AssetManager

data class FeatureExtractorConfig(
    var sampleRate: Float = 16000f,
    var featureDim: Int = 80,
)

data class ModelConfig(
    var encoderParam: String = "",
    var encoderBin: String = "",
    var decoderParam: String = "",
    var decoderBin: String = "",
    var joinerParam: String = "",
    var joinerBin: String = "",
    var tokens: String = "",
    var numThreads: Int = 2,

    var useGPU: Boolean = false,
)

data class DecoderConfig(
    var method: String = "greedy_search",
    var numActivePaths: Int = 4,
)

data class RecognizerConfig(
    var featConfig: FeatureExtractorConfig = FeatureExtractorConfig(),
    var modelConfig: ModelConfig = ModelConfig(),
    var decoderConfig: DecoderConfig = DecoderConfig(),
    var enableEndpoint: Boolean = true,
    var rule1MinTrailingSilence: Float = 2.4f,
    var rule2MinTrailingSilence: Float = 1.0f,
    var rule3MinUtteranceLength: Float = 30.0f,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
)

class SherpaNcnn(
    assetManager: AssetManager? = null,
    val config: RecognizerConfig,
) {
    private var ptr: Long = 0L

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    val isValid: Boolean get() = ptr != 0L

    fun acceptWaveform(samples: FloatArray, sampleRate: Float) {
        if (ptr == 0L) return
        acceptWaveform(ptr, samples, sampleRate)
    }

    fun inputFinished() {
        if (ptr == 0L) return
        inputFinished(ptr)
    }

    fun isReady(): Boolean = if (ptr == 0L) false else isReady(ptr)

    fun decode() {
        if (ptr == 0L) return
        decode(ptr)
    }

    fun isEndpoint(): Boolean = if (ptr == 0L) false else isEndpoint(ptr)

    fun reset(recreate: Boolean = true) {
        if (ptr == 0L) return
        reset(ptr, recreate)
    }

    fun getText(): String = if (ptr == 0L) "" else getText(ptr)

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    private external fun delete(ptr: Long)
    private external fun newFromAsset(assetManager: AssetManager, config: RecognizerConfig): Long
    private external fun newFromFile(config: RecognizerConfig): Long
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Float)
    private external fun inputFinished(ptr: Long)
    private external fun isReady(ptr: Long): Boolean
    private external fun decode(ptr: Long)
    private external fun isEndpoint(ptr: Long): Boolean
    private external fun reset(ptr: Long, recreate: Boolean)
    private external fun getText(ptr: Long): String

    companion object {
        // Precompiled, extracted from the user-provided APK; not rebuilt by Eta.
        @Volatile var nativeLoaded: Boolean = false
            private set
        @Volatile var nativeLoadError: String = ""
            private set
        init {
            try {
                System.loadLibrary("ncnn")
                System.loadLibrary("sherpa-ncnn-jni")
                nativeLoaded = true
            } catch (error: Throwable) {
                nativeLoadError = "Native library load failed: ${error.javaClass.simpleName}"
            }
        }
    }
}
