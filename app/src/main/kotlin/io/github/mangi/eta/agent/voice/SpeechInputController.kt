package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.agent.voice.offline.OfflineAsrEngine
import io.github.mangi.eta.agent.voice.qwen.QwenAsrEngine
import io.github.mangi.eta.agent.voice.local.LocalSpeechEngine
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class SpeechInputState(
    val phase: EtaSpeechPhase = EtaSpeechPhase.IDLE,
    val preview: String = "",
    val level: Float = 0f,
    val progress: String = "",
    val error: String? = null,
    val configureAvailable: Boolean = false,
) {
    val active: Boolean get() = phase != EtaSpeechPhase.IDLE
}

internal class SpeechInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val automaticEndpoint: Boolean = false,
    private val beforeCapture: () -> Unit = {},
    private val afterCapture: () -> Unit = {},
    private val refiner: SpeechTranscriptRefiner = SpeechTranscriptRefiner(),
    private val createRecorder: () -> SpeechAudioCapture = ::SpeechRecorder,
    private val recognizeStream: suspend (
        SpeechSettings,
        SpeechCredentials,
        ReceiveChannel<ByteArray>,
        () -> Unit,
        (String) -> Unit,
        () -> Unit,
        Int,
    ) -> String = ::recognizeSpeechStream,
    private val recognizeFile: suspend (
        Context,
        SpeechSettings,
        SpeechCredentials,
        ByteArray,
        (String) -> Unit,
    ) -> String = ::recognizeSpeechFile,
    private val recognizeOffline: suspend (
        Context,
        ReceiveChannel<ByteArray>,
        () -> Unit,
        (String) -> Unit,
        () -> Unit,
        Int,
    ) -> String = OfflineAsrEngine::recognize,
    private val recognizeQwen: suspend (
        Context, ReceiveChannel<ByteArray>, () -> Unit, (String) -> Unit, () -> Unit, Int,
    ) -> String = QwenAsrEngine::recognize,
    private val recognizeLocal: suspend (
        Context, ReceiveChannel<ByteArray>, () -> Unit, (String) -> Unit, () -> Unit, Int,
    ) -> String = LocalSpeechEngine::recognize,
    private val onResult: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(SpeechInputState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var finishRequested = false
    private var job: Job? = null
    private var recorder: SpeechAudioCapture? = null
    private var lease: SpeechAudioLease? = null
    private var capturing = false

    /**
     * [holdToTalk] 表示用户按住说话：松手才结束，期间的停顿不会自动发送。
     * 停顿自动发送只对构造时声明 [automaticEndpoint] 的入口生效，聊天听写始终等待用户确认。
     */
    fun start(settings: SpeechSettings? = null, credentials: SpeechCredentials? = null, holdToTalk: Boolean = false) {
        cancel()
        val session = generation
        mutableState.value = SpeechInputState(phase = EtaSpeechPhase.STARTING, progress = "正在连接")
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                val rawConfig = (settings ?: SpeechSettingsRepository.settings()).canonicalized()
                if (!rawConfig.recognitionEnabled) return@launch
                lease = SpeechAudioLease(context) { cancel() }.also { it.acquire(playback = false) }
                val offline = rawConfig.asr.isLocal
                // Strict local recognition never opens either speech or provider credentials.
                val (config, secrets) = if (offline) rawConfig to null else {
                    val rawSecrets = credentials ?: SpeechSettingsRepository.credentials(context)
                    SpeechSettingsRepository.resolveDeepSeekFallback(rawConfig, rawSecrets).also {
                        validateSpeechSettings(it.first, it.second, synthesis = false)
                    }
                }
                if (offline) mutableState.value = mutableState.value.copy(progress = "正在加载本机语音引擎")
                val endpointSilenceMs = if (automaticEndpoint && !holdToTalk) config.autoSendSilenceMs else 0
                // Both native/server and capture endpoints share the generation/finish gate.
                val endpoint: () -> Unit = {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (endpointSilenceMs > 0 && generation == session && !finishRequested) {
                            AndroidAgentLogger.info("Eta speech endpoint code=pause silenceMs=$endpointSilenceMs")
                            finish()
                        }
                    }
                }
                val source = createRecorder()
                val audio = if (endpointSilenceMs > 0) {
                    EndpointSpeechAudioCapture(source, endpointSilenceMs, endpoint)
                } else source
                if (automaticEndpoint) AndroidAgentLogger.info(
                    "Eta speech endpoint policy=${if (endpointSilenceMs > 0) "adaptive_pcm" else "manual"} " +
                        "silenceMs=$endpointSilenceMs hold=$holdToTalk",
                )
                recorder = audio
                fun update(transform: (SpeechInputState) -> SpeechInputState) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (generation == session) mutableState.value = transform(mutableState.value)
                    }
                }
                val text = coroutineScope {
                    var recording: Job? = null
                    val begin = begin@{
                        if (generation == session && !finishRequested && recording == null) {
                            ensureActive()
                            beforeCapture()
                            // The hook may cancel/replace this session synchronously.
                            if (generation != session || finishRequested) return@begin Unit
                            ensureActive()
                            capturing = true
                            recording = launch {
                                try {
                                    audio.record(
                                        onReady = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = if (endpointSilenceMs > 0) "正在等待停顿，点击完成结束" else if (config.asr.isLocal) "说完识别，非实时逐字；最长 30 秒" else "正在聆听，点击完成结束") } },
                                        onLevel = { level -> update { it.copy(level = level) } },
                                    )
                                } finally {
                                    if (generation == session) releaseCapture()
                                }
                                update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, level = 0f, progress = "正在识别") }
                            }
                        }
                        Unit
                    }
                    try {
                        when (config.asr) {
                            AsrProvider.LOCAL, AsrProvider.SHERPA_NCNN, AsrProvider.QWEN3_LOCAL -> recognizeLocal(
                                context, audio.frames, begin,
                                { preview -> update { it.copy(preview = preview) } },
                                // Capture owns local endpointing; the old fixed-threshold service VAD
                                // otherwise cuts PCM independently before the shared stream is drained.
                                endpoint, 0,
                            )
                            AsrProvider.QWEN_REALTIME, AsrProvider.DOUBAO -> recognizeStream(
                                config,
                                requireNotNull(secrets),
                                audio.frames,
                                begin,
                                { preview -> update { it.copy(preview = preview) } },
                                endpoint,
                                endpointSilenceMs,
                            )
                            AsrProvider.MIMO, AsrProvider.QWEN_FLASH, AsrProvider.QWEN_FILE, AsrProvider.DEEPSEEK -> {
                                begin()
                                val wav = withTimeout(65_000) { collectSpeechAudio(audio.frames) }
                                // 关闭通道可能同步唤醒收集器，旧会话不能在取消传播完成前启动请求。
                                coroutineContext.ensureActive()
                                if (generation != session) throw CancellationException("Speech session replaced")
                                // Drain capture cleanup/foreground release before file decoding starts.
                                recording?.join()
                                coroutineContext.ensureActive()
                                if (generation != session) throw CancellationException("Speech session replaced")
                                recognizeFile(context, config, requireNotNull(secrets), wav) { progress ->
                                    update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = progress) }
                                }
                            }
                        }
                    } finally {
                        audio.cancel()
                        recording?.cancel()
                    }
                }
                if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
                val final = refined(session, !offline && config.refineTranscript, text)
                coroutineContext.ensureActive()
                if (generation == session) {
                    cancel()
                    onResult(final)
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (generation == session) {
                    val failure = error.speechFailure()
                    AndroidAgentLogger.warn("Eta speech recognition failed: code=${failure.code} type=${error.javaClass.simpleName}")
                    cancel()
                    mutableState.value = SpeechInputState(
                        error = failure.userMessage,
                        configureAvailable = failure.code == SpeechErrorCode.CONFIGURATION ||
                            failure.code == SpeechErrorCode.CREDENTIALS ||
                            failure.code == SpeechErrorCode.AUTHENTICATION,
                    )
                }
            } finally {
                if (generation == session) cancel()
            }
        }
    }

    /** 纠错失败不影响交付；只有纠错仍属于当前会话时才展示进度。 */
    private suspend fun refined(session: Long, refineTranscript: Boolean, text: String): String {
        if (!refineTranscript || generation != session) return text
        mutableState.value = mutableState.value.copy(
            phase = EtaSpeechPhase.RECOGNIZING, preview = text, progress = "正在校对", level = 0f,
        )
        return try {
            refiner.refine(text)
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            val failure = error.speechFailure()
            AndroidAgentLogger.warn("Eta speech refine skipped: code=${failure.code} type=${error.javaClass.simpleName}")
            text
        }
    }

    fun finish() {
        if (!mutableState.value.active || finishRequested) return
        finishRequested = true
        if (mutableState.value.phase == EtaSpeechPhase.STARTING) { cancel(); return }
        recorder?.finish()
        mutableState.value = mutableState.value.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = "正在识别", level = 0f)
    }

    fun cancel() {
        generation++
        finishRequested = false
        // 先取消识别协程，避免关闭录音通道被当作正常录音完成而提交请求。
        job?.cancel(); job = null
        recorder?.cancel(); recorder = null
        releaseCapture()
        lease?.close(); lease = null
        mutableState.value = SpeechInputState()
    }

    private fun releaseCapture() {
        if (capturing) afterCapture()
        capturing = false
    }
}
