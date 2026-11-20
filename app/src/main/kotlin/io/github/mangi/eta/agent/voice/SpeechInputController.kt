package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.Locale

import io.github.mangi.eta.agent.voice.offline.OfflineAsrEngine
import io.github.mangi.eta.agent.voice.qwen.QwenAsrEngine
import io.github.mangi.eta.agent.voice.local.LocalSpeechEngine
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
    val systemIssue: EtaSpeechIssue? = null,
    val downloadAvailable: Boolean = false,
    val holdToTalk: Boolean = false,
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
    private val loadSettings: suspend () -> SpeechSettings = SpeechSettingsRepository::settings,
    private val loadCredentials: suspend () -> SpeechCredentials = { SpeechSettingsRepository.credentials(context) },
    private val onHoldResult: ((String) -> Unit)? = null,
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
    private var system: EtaSpeechInput? = null
    private var pendingSystemResult: String? = null
    private var autoSubmit = false
    private var systemRefine = false
    private var delivery: Job? = null

    /**
     * [holdToTalk] 表示用户按住说话：松手才结束，期间的停顿不会自动发送。
     * 停顿自动发送只对构造时声明 [automaticEndpoint] 的入口生效，聊天听写始终等待用户确认。
     */
    private var systemRecognition = false

    fun start(settings: SpeechSettings? = null, credentials: SpeechCredentials? = null, holdToTalk: Boolean = false) {
        cancel()
        val session = generation
        mutableState.value = SpeechInputState(phase = EtaSpeechPhase.STARTING, progress = "正在连接", holdToTalk = holdToTalk)
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                val rawConfig = (settings ?: loadSettings()).canonicalized()
                if (!rawConfig.recognitionEnabled) return@launch
                lease = SpeechAudioLease(context) { cancel() }.also { it.acquire(playback = false) }
                systemRecognition = rawConfig.asr == AsrProvider.SYSTEM
                val offline = rawConfig.asr.isLocal || systemRecognition
                // Strict local recognition never opens either speech or provider credentials.
                val (config, secrets) = if (offline) rawConfig to null else {
                    val rawSecrets = credentials ?: loadCredentials()
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
                if (systemRecognition) {
                    autoSubmit = endpointSilenceMs > 0
                    systemRefine = config.refineTranscript
                    startSystem(session, endpointSilenceMs)
                    kotlinx.coroutines.awaitCancellation()
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
                                        onReady = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = if (it.holdToTalk) "松开发送，移出取消" else if (endpointSilenceMs > 0) "正在等待停顿，点击完成结束" else if (config.asr.isLocal) "说完识别，非实时逐字；最长 30 秒" else "正在聆听，点击完成结束") } },
                                        onLevel = { level -> update { it.copy(level = level) } },
                                    )
                                } finally {
                                    if (generation == session) releaseCapture()
                                }
                                update {
                                    it.copy(
                                        phase = EtaSpeechPhase.RECOGNIZING,
                                        level = 0f,
                                        progress = if (it.holdToTalk) "正在识别并发送" else "正在识别",
                                    )
                                }
                            }
                        }
                        Unit
                    }
                    try {
                        when (config.asr) {
                            AsrProvider.SYSTEM -> error("System recognition uses its own recorder")
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
                    val holdMode = mutableState.value.holdToTalk
                    cancel()
                    if (holdMode && onHoldResult != null) {
                        onHoldResult(final)
                    } else {
                        onResult(final)
                    }
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

    private fun deliverSystem(session: Long, text: String) {
        if (generation != session || text.isBlank()) return
        val held = mutableState.value.holdToTalk
        val refine = systemRefine
        pendingSystemResult = null
        finishRequested = true
        delivery = scope.launch(Dispatchers.Main.immediate) {
            val final = refined(session, refine, text)
            coroutineContext.ensureActive()
            if (generation == session) {
                cancel()
                if (held && onHoldResult != null) onHoldResult.invoke(final) else onResult(final)
            }
        }
    }

    private fun startSystem(session: Long, endpointSilenceMs: Int) {
        fun update(transform: (SpeechInputState) -> SpeechInputState) {
            if (generation == session) mutableState.value = transform(mutableState.value)
        }
        system = EtaSpeechInput(context,
            onListening = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = "正在聆听") } },
            onRecognizing = { update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = "正在识别") } },
            onLevel = { level -> update { it.copy(level = level) } },
            onPartial = { text -> update { it.copy(preview = text) } },
            onResult = { text ->
                if (generation == session) {
                    if (autoSubmit || finishRequested) deliverSystem(session, text)
                    else {
                        pendingSystemResult = text
                        lease?.close(); lease = null
                        update { it.copy(phase = EtaSpeechPhase.LISTENING, preview = text,
                            progress = if (it.holdToTalk) "松开发送，移出取消" else "识别完成，点击完成插入", level = 0f) }
                    }
                }
            },
            onError = { issue ->
                if (generation == session) { lease?.close(); lease = null }
                update { SpeechInputState(error = context.getString(speechIssueMessage(issue)), systemIssue = issue,
                    configureAvailable = true, downloadAvailable = issue.kind == EtaSpeechIssueKind.DOWNLOAD_AVAILABLE) }
            },
            onDownloadStatus = { status -> update {
                SpeechInputState(progress = context.getString(when (status) {
                    EtaSpeechDownloadStatus.DOWNLOADING -> io.github.mangi.eta.R.string.voice_model_downloading
                    EtaSpeechDownloadStatus.SCHEDULED -> io.github.mangi.eta.R.string.voice_model_scheduled
                    EtaSpeechDownloadStatus.READY -> io.github.mangi.eta.R.string.voice_model_ready
                    EtaSpeechDownloadStatus.FAILED -> io.github.mangi.eta.R.string.voice_model_download_failed
                }), downloadAvailable = status == EtaSpeechDownloadStatus.FAILED)
            } },
            completeSilenceMs = endpointSilenceMs.takeIf { it > 0 },
        ).also { it.start() }
    }

    fun downloadModel() { system?.downloadModel() }

    fun finish() {
        if (!mutableState.value.active || finishRequested) return
        pendingSystemResult?.let { deliverSystem(generation, it); return }
        finishRequested = true
        if (mutableState.value.phase == EtaSpeechPhase.STARTING) { cancel(); return }
        recorder?.finish()
        system?.finish()
        val progressText = if (mutableState.value.holdToTalk) "正在识别并发送" else "正在识别"
        mutableState.value = mutableState.value.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = progressText, level = 0f)
    }

    fun cancel() {
        generation++
        finishRequested = false
        // 先取消识别协程，避免关闭录音通道被当作正常录音完成而提交请求。
        job?.cancel(); job = null
        autoSubmit = false
        systemRefine = false
        delivery?.cancel(); delivery = null
        systemRecognition = false
        pendingSystemResult = null
        system?.cancel(); system = null
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


internal enum class EtaSpeechIssueKind { RECOGNITION, NO_SERVICE, SERVICE_TIMEOUT, DOWNLOAD_AVAILABLE, DOWNLOAD_PENDING }

internal data class EtaSpeechIssue(
    val errorCode: Int,
    val kind: EtaSpeechIssueKind = EtaSpeechIssueKind.RECOGNITION,
)

internal enum class EtaSpeechDownloadStatus { DOWNLOADING, SCHEDULED, READY, FAILED }

internal enum class EtaSpeechSupportDecision { START, DOWNLOAD, PENDING }

internal fun speechSupportDecision(support: RecognitionSupport, languageTag: String): EtaSpeechSupportDecision {
    val requested = Locale.forLanguageTag(languageTag)
    fun List<String>.containsLanguage() = any { offered ->
        val available = Locale.forLanguageTag(offered)
        available.language.equals(requested.language, ignoreCase = true) &&
            (available.country.isEmpty() || requested.country.isEmpty() ||
                available.country.equals(requested.country, ignoreCase = true)) &&
            (available.script.isEmpty() || requested.script.isEmpty() ||
                available.script.equals(requested.script, ignoreCase = true))
    }
    if (support.installedOnDeviceLanguages.containsLanguage() || support.onlineLanguages.containsLanguage()) {
        return EtaSpeechSupportDecision.START
    }
    if (support.pendingOnDeviceLanguages.containsLanguage()) return EtaSpeechSupportDecision.PENDING
    if (support.supportedOnDeviceLanguages.containsLanguage()) return EtaSpeechSupportDecision.DOWNLOAD
    // 服务给出的语言列表可能不完整；最终能否识别仍由 startListening 的回调决定。
    return EtaSpeechSupportDecision.START
}

/** 单次识别由浮窗持有；关闭、切换输入方式和重新唤醒都会使旧回调失效。 */
internal class EtaSpeechInput(
    private val context: Context,
    private val onListening: () -> Unit,
    private val onRecognizing: () -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (EtaSpeechIssue) -> Unit,
    private val onDownloadStatus: (EtaSpeechDownloadStatus) -> Unit,
    /** 期望的说完判定静音时长；系统服务可以忽略该提示，结果仍以服务回调为准。 */
    private val completeSilenceMs: Int? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var downloadRecognizer: SpeechRecognizer? = null
    private var downloadSource: SystemSpeechRecognizer.Source? = null
    private var generation = 0
    private var preflightTimeout: Runnable? = null
    private var recognitionTimeout: Runnable? = null
    private var listeningStarted = false
    private var startedAt = 0L
    private var startupLatencyMs: Long? = null
    private val languageTag = Locale.getDefault().toLanguageTag()

    fun start() {
        cancel()
        startedAt = SystemClock.elapsedRealtime()
        val session = generation
        val selection = try {
            SystemSpeechRecognizer.select(context)
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Eta speech service selection failed: type=${error.javaClass.simpleName}")
            onError(EtaSpeechIssue(
                if (error is SecurityException) SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                else SpeechRecognizer.ERROR_CLIENT,
            ))
            return
        }
        if (selection == null) {
            AndroidAgentLogger.warn("Eta speech unavailable: reason=no_service")
            onError(EtaSpeechIssue(SpeechRecognizer.ERROR_CLIENT, EtaSpeechIssueKind.NO_SERVICE))
            return
        }
        val delegate = selection.recognizer
        val source = selection.source
        val intent = recognitionIntent()
        recognizer = delegate
        AndroidAgentLogger.info("Eta speech selected: source=${source.label}")

        fun current() = generation == session && recognizer === delegate
        fun fail(issue: EtaSpeechIssue) {
            if (!current()) return
            if (issue.kind == EtaSpeechIssueKind.DOWNLOAD_AVAILABLE) downloadSource = source
            AndroidAgentLogger.warn(
                "Eta speech failed: source=${source.label} code=${issue.errorCode} kind=${issue.kind} " +
                    "startupMs=${startupLatencyMs ?: SystemClock.elapsedRealtime() - startedAt}",
            )
            releaseRecognition()
            onError(issue)
        }
        fun begin() {
            if (!current() || listeningStarted) return
            preflightTimeout?.let(handler::removeCallbacks)
            preflightTimeout = null
            listeningStarted = true
            recognitionTimeout = Runnable {
                fail(EtaSpeechIssue(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, EtaSpeechIssueKind.SERVICE_TIMEOUT))
            }.also { handler.postDelayed(it, RECOGNITION_TIMEOUT_MS) }
            try {
                delegate.startListening(intent)
            } catch (error: RuntimeException) {
                AndroidAgentLogger.warn("Eta speech start failed: source=${source.label} type=${error.javaClass.simpleName}")
                fail(EtaSpeechIssue(
                    if (error is SecurityException) SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    else SpeechRecognizer.ERROR_CLIENT,
                ))
            }
        }

        var listenerSet = false
        try {
            delegate.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!current()) return
                    startupLatencyMs = SystemClock.elapsedRealtime() - startedAt
                    AndroidAgentLogger.info(
                        "Eta speech ready: source=${source.label} startupMs=$startupLatencyMs",
                    )
                    onListening()
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    if (current()) onLevel((rmsdB / 10f).coerceIn(0f, 1f))
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (current()) onRecognizing()
                }
                override fun onError(error: Int) = fail(EtaSpeechIssue(error))
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val text = results.text()
                    AndroidAgentLogger.info("Eta speech completed: source=${source.label}")
                    releaseRecognition()
                    if (text.isBlank()) onError(EtaSpeechIssue(SpeechRecognizer.ERROR_NO_MATCH)) else onResult(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (current()) partialResults.text().takeIf(String::isNotBlank)?.let(onPartial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            listenerSet = true
            preflightTimeout = Runnable {
                if (current() && !listeningStarted) {
                    AndroidAgentLogger.info("Eta speech support timed out: source=${source.label}")
                    begin()
                }
            }.also { handler.postDelayed(it, SUPPORT_TIMEOUT_MS) }
            delegate.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    if (!current() || listeningStarted) return
                    val decision = speechSupportDecision(recognitionSupport, languageTag)
                    AndroidAgentLogger.info("Eta speech support: source=${source.label} decision=$decision")
                    when (decision) {
                        EtaSpeechSupportDecision.START -> begin()
                        EtaSpeechSupportDecision.DOWNLOAD -> fail(
                            EtaSpeechIssue(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, EtaSpeechIssueKind.DOWNLOAD_AVAILABLE),
                        )
                        EtaSpeechSupportDecision.PENDING -> fail(
                            EtaSpeechIssue(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, EtaSpeechIssueKind.DOWNLOAD_PENDING),
                        )
                    }
                }
                override fun onError(error: Int) {
                    if (!current() || listeningStarted) return
                    AndroidAgentLogger.info("Eta speech support unavailable: source=${source.label} code=$error")
                    begin()
                }
            })
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn(
                "Eta speech setup failed: source=${source.label} phase=${if (listenerSet) "support" else "listener"} " +
                    "type=${error.javaClass.simpleName}",
            )
            if (listenerSet) begin() else fail(EtaSpeechIssue(SpeechRecognizer.ERROR_CLIENT))
        }
    }

    fun downloadModel() {
        val source = downloadSource ?: return
        releaseDownload()
        val delegate = try {
            SystemSpeechRecognizer.create(context, source)
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Eta speech model request failed: source=${source.label} type=${error.javaClass.simpleName}")
            onDownloadStatus(EtaSpeechDownloadStatus.FAILED)
            return
        }
        downloadRecognizer = delegate
        onDownloadStatus(EtaSpeechDownloadStatus.DOWNLOADING)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                delegate.triggerModelDownload(recognitionIntent())
                downloadSource = null
                // Android 13 没有下载回调，保留识别器到入口取消，避免服务连接前请求就被销毁。
                onDownloadStatus(EtaSpeechDownloadStatus.SCHEDULED)
                return
            }
            delegate.triggerModelDownload(recognitionIntent(), context.mainExecutor, object : ModelDownloadListener {
                private fun current() = downloadRecognizer === delegate
                override fun onProgress(completedPercent: Int) {
                    if (current()) onDownloadStatus(EtaSpeechDownloadStatus.DOWNLOADING)
                }
                override fun onSuccess() {
                    if (!current()) return
                    AndroidAgentLogger.info("Eta speech model ready: source=${source.label}")
                    downloadSource = null
                    releaseDownload()
                    onDownloadStatus(EtaSpeechDownloadStatus.READY)
                }
                override fun onScheduled() {
                    if (!current()) return
                    AndroidAgentLogger.info("Eta speech model scheduled: source=${source.label}")
                    downloadSource = null
                    releaseDownload()
                    onDownloadStatus(EtaSpeechDownloadStatus.SCHEDULED)
                }
                override fun onError(error: Int) {
                    if (!current()) return
                    AndroidAgentLogger.warn("Eta speech model request failed: source=${source.label} code=$error")
                    releaseDownload()
                    onDownloadStatus(EtaSpeechDownloadStatus.FAILED)
                }
            })
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Eta speech model request failed: source=${source.label} type=${error.javaClass.simpleName}")
            releaseDownload()
            onDownloadStatus(EtaSpeechDownloadStatus.FAILED)
        }
    }

    fun finish() {
        if (!listeningStarted) {
            cancel()
            onError(EtaSpeechIssue(SpeechRecognizer.ERROR_NO_MATCH))
            return
        }
        try {
            recognizer?.stopListening()
            onRecognizing()
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Eta speech stop failed: type=${error.javaClass.simpleName}")
            releaseRecognition()
            onError(EtaSpeechIssue(SpeechRecognizer.ERROR_CLIENT))
        }
    }

    fun cancel() {
        releaseRecognition()
        releaseDownload()
        downloadSource = null
    }

    private fun releaseRecognition() {
        generation++
        preflightTimeout?.let(handler::removeCallbacks)
        recognitionTimeout?.let(handler::removeCallbacks)
        preflightTimeout = null
        recognitionTimeout = null
        listeningStarted = false
        startedAt = 0L
        startupLatencyMs = null
        val previous = recognizer
        recognizer = null
        try {
            previous?.destroy()
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Eta speech release failed: type=${error.javaClass.simpleName}")
        }
    }

    private fun releaseDownload() {
        val previous = downloadRecognizer
        downloadRecognizer = null
        try {
            previous?.destroy()
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Eta speech model release failed: type=${error.javaClass.simpleName}")
        }
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .apply {
            completeSilenceMs?.let {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, it.toLong())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, it.toLong())
            }
        }

    private fun Bundle?.text(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()

    private companion object {
        const val SUPPORT_TIMEOUT_MS = 750L
        const val RECOGNITION_TIMEOUT_MS = 30_000L
    }
}

internal fun speechIssueMessage(issue: EtaSpeechIssue): Int = when (issue.kind) {
    EtaSpeechIssueKind.NO_SERVICE -> io.github.mangi.eta.R.string.voice_speech_no_service
    EtaSpeechIssueKind.SERVICE_TIMEOUT -> io.github.mangi.eta.R.string.voice_speech_service_timeout
    EtaSpeechIssueKind.DOWNLOAD_AVAILABLE -> io.github.mangi.eta.R.string.voice_model_needed
    EtaSpeechIssueKind.DOWNLOAD_PENDING -> io.github.mangi.eta.R.string.voice_model_pending
    EtaSpeechIssueKind.RECOGNITION -> when (issue.errorCode) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> io.github.mangi.eta.R.string.voice_audio_permission
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> io.github.mangi.eta.R.string.voice_no_speech
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> io.github.mangi.eta.R.string.voice_speech_network_error
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> io.github.mangi.eta.R.string.voice_speech_service_error
        SpeechRecognizer.ERROR_AUDIO -> io.github.mangi.eta.R.string.voice_speech_audio_error
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> io.github.mangi.eta.R.string.voice_speech_busy
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> io.github.mangi.eta.R.string.voice_speech_rate_limited
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> io.github.mangi.eta.R.string.voice_language_not_supported
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> io.github.mangi.eta.R.string.voice_language_unavailable
        else -> io.github.mangi.eta.R.string.voice_speech_unavailable
    }
}
