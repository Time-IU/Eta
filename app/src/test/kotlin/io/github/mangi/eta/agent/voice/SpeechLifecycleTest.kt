package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Bundle
import android.speech.SpeechRecognizer
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration
import android.os.Looper
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val results = mutableListOf<String>()
    private val holdResults = mutableListOf<String>()
    private val sessions = mutableListOf<FakeStreamSession>()
    private lateinit var controller: SpeechInputController

    @Before fun setup() {
        controller = createController()
    }

    @After fun close() {
        controller.cancel()
        scope.cancel()
    }

    private fun createController(
        automaticEndpoint: Boolean = false,
        refiner: SpeechTranscriptRefiner = SpeechTranscriptRefiner(),
        onHoldResult: ((String) -> Unit)? = null,
    ): SpeechInputController {
        var pending: FakeStreamSession? = null
        return SpeechInputController(
            context = RuntimeEnvironment.getApplication(),
            scope = scope,
            automaticEndpoint = automaticEndpoint,
            refiner = refiner,
            createRecorder = { FakeStreamSession().also { pending = it; sessions += it } },
            recognizeStream = { _, _, _, onReady, onPreview, onEndpoint, endpointSilenceMs ->
                val session = checkNotNull(pending)
                pending = null
                session.attach(onReady, onPreview, onEndpoint, endpointSilenceMs)
                session.awaitTranscript()
            },
            onHoldResult = onHoldResult,
            onResult = results::add,
        )
    }

    private fun start(
        target: SpeechInputController = controller,
        settings: SpeechSettings = SpeechSettings(asr = AsrProvider.QWEN_REALTIME),
        holdToTalk: Boolean = false,
    ): FakeStreamSession {
        target.start(
            settings = settings,
            credentials = SpeechCredentials(qwenAsr = "test-key", doubaoAsr = "test-key"),
            holdToTalk = holdToTalk,
        )
        shadowOf(Looper.getMainLooper()).idle()
        return sessions.last()
    }

    @Test fun dictationWaitsForUserConfirmationBeforeSubmitting() {
        val session = start()
        session.emitPreview("可编辑的草稿")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(results.isEmpty())
        assertEquals("可编辑的草稿", controller.state.value.preview)
        controller.finish()
        controller.finish()
        session.complete("可编辑的草稿")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("可编辑的草稿"), results)
        assertFalse(controller.state.value.active)
    }

    @Test fun canceledSessionCannotSubmitOrReplaceNewPreview() {
        val old = start()
        controller.cancel()
        val current = start()
        old.emitPreview("旧草稿")
        old.complete("旧结果")
        current.emitPreview("新草稿")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("新草稿", controller.state.value.preview)
        controller.finish()
        current.complete("新结果")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("新结果"), results)
    }

    @Test fun staleEndpointCannotFinishNewGenerationAndDuplicateEndpointDeliversOnce() {
        val overlay = createController(automaticEndpoint = true)
        try {
            val old = start(overlay)
            val lateEndpoint = old.endpointCallback
            overlay.cancel()
            val current = start(overlay)
            lateEndpoint()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(current.finished)
            current.endpointCallback()
            current.endpointCallback()
            overlay.finish()
            current.complete("唯一结果")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("唯一结果"), results)
        } finally { overlay.cancel() }
    }

    @Test fun closingOwnerReleasesRecognition() {
        val session = start()
        controller.cancel()
        session.complete("迟到")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.canceled)
        assertTrue(results.isEmpty())
        assertNull(controller.state.value.error)
    }

    @Test fun anotherAudioOwnerInterruptsRecording() {
        val session = start()
        val lease = SpeechAudioLease(RuntimeEnvironment.getApplication()) {}
        try {
            lease.acquire(playback = false)
            assertTrue(session.canceled)
            assertFalse(controller.state.value.active)
        } finally {
            lease.close()
        }
    }

    @Test fun ownerCancellationReleasesRecorder() {
        val session = start()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.canceled)
    }

    @Test fun disabledRecognitionDoesNotAcquireAudioOrRequireCloudCredentials() {
        var interrupted = false
        val lease = SpeechAudioLease(RuntimeEnvironment.getApplication()) { interrupted = true }
        try {
            lease.acquire(playback = false)
            for (provider in AsrProvider.entries) {
                controller.start(SpeechSettings(asr = provider, recognitionEnabled = false))
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(controller.state.value.active)
                assertNull(controller.state.value.error)
                assertTrue(results.isEmpty())
                assertFalse(interrupted)
            }
        } finally {
            lease.close()
        }
    }

    @Test fun disablingAnActiveSessionDiscardsLateRecognitionResults() {
        val session = start()
        controller.start(SpeechSettings(recognitionEnabled = false))
        shadowOf(Looper.getMainLooper()).idle()
        session.complete("迟到的旧识别")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session.canceled)
        assertFalse(controller.state.value.active)
        assertTrue(results.isEmpty())
    }

    @Test fun overlaySubmitsAfterPauseByDefault() {
        val overlay = createController(automaticEndpoint = true)
        try {
            val session = start(overlay, SpeechSettings(asr = AsrProvider.QWEN_REALTIME))
            assertEquals(SpeechSettings.DEFAULT_AUTO_SEND_SILENCE_MS, session.endpointSilenceMs)
            session.serverEndpoint("打开设置")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(session.finished)
            assertEquals(listOf("打开设置"), results)
        } finally {
            overlay.cancel()
        }
    }

    @Test fun capturePauseFinishesStreamWithoutAnyServerEndpointCallback() {
        val overlay = createController(automaticEndpoint = true)
        try {
            val session = start(overlay)
            session.frames.trySend(endpointPcm(0, 200) + endpointPcm(2500, 400) + endpointPcm(0, 2000)).getOrThrow()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(session.finished)
            assertTrue(results.isEmpty())
            session.complete("流式结果")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("流式结果"), results)
        } finally { overlay.cancel() }
    }

    @Test fun overlayWaitsWhenAutoSendIsOffOrHoldingToTalk() {
        listOf(
            SpeechSettings(asr = AsrProvider.QWEN_REALTIME, autoSendSilenceMs = 0) to false,
            SpeechSettings(asr = AsrProvider.QWEN_REALTIME) to true,
        ).forEach { (settings, hold) ->
            val overlay = createController(automaticEndpoint = true)
            try {
                val session = start(overlay, settings, holdToTalk = hold)
                assertEquals(0, session.endpointSilenceMs)
                session.endpointCallback() // Even a late/misbehaving provider cannot end a held session.
                session.emitPreview("还没说完")
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(session.finished)
                assertTrue(results.isEmpty())
                overlay.finish()
                session.complete("还没说完")
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(session.finished)
                assertEquals(listOf("还没说完"), results)
            } finally {
                overlay.cancel()
                results.clear()
            }
        }
    }

    @Test fun refinedTextReplacesTranscriptAndFailureKeepsOriginal() {
        fun refiner(reply: () -> String) = SpeechTranscriptRefiner(
            loadConfig = {
                io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(
                    baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture", systemPrompt = "",
                )
            },
            providerFor = {
                object : io.github.mangi.eta.agent.model.AgentProviderClient {
                    override val id = "fixture"
                    override val capabilities = io.github.mangi.eta.agent.model.OpenAiChatCompletionsProvider.capabilities
                    override fun complete(
                        request: io.github.mangi.eta.agent.model.ProviderRequest,
                        runController: io.github.mangi.eta.agent.runtime.AgentRunController,
                        onEvent: (io.github.mangi.eta.agent.model.ProviderEvent) -> Unit,
                    ) = io.github.mangi.eta.agent.model.ProviderResponse(org.json.JSONObject()
                        .put("role", "assistant").put("content", reply()).put("finish_reason", "stop"))
                }
            },
        )
        listOf(
            refiner { "用 Python 写脚本" } to "用 Python 写脚本",
            refiner { throw java.io.IOException("offline") } to "用配森写脚本",
        ).forEach { (refiner, expected) ->
            val overlay = createController(automaticEndpoint = true, refiner = refiner)
            try {
                val session = start(overlay, SpeechSettings(asr = AsrProvider.QWEN_REALTIME, refineTranscript = true))
                session.serverEndpoint("用配森写脚本")
                val deadline = System.nanoTime() + 3_000_000_000
                while (results.isEmpty() && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(1)
                }
                assertEquals(listOf(expected), results)
                assertFalse(overlay.state.value.active)
            } finally {
                overlay.cancel()
                results.clear()
            }
        }
    }

    @Test fun holdToTalkRoutesResultToHoldCallbackAndResetsCleanly() {
        val dictation = createController(onHoldResult = holdResults::add)
        try {
            val holdSession = start(dictation, holdToTalk = true)
            assertTrue(dictation.state.value.holdToTalk)
            assertEquals(EtaSpeechPhase.LISTENING, dictation.state.value.phase)
            dictation.finish()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dictation.state.value.holdToTalk)
            assertEquals(EtaSpeechPhase.RECOGNIZING, dictation.state.value.phase)
            holdSession.complete("按住直接发送")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("按住直接发送"), holdResults)
            assertTrue(results.isEmpty())
            assertFalse(dictation.state.value.active)
            assertFalse(dictation.state.value.holdToTalk)

            // 失败（空语音）或取消后再次轻点听写，不能残留 holdToTalk 状态。
            val failedHold = start(dictation, holdToTalk = true)
            dictation.finish()
            failedHold.complete("   ")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dictation.state.value.active)
            assertFalse(dictation.state.value.holdToTalk)
            assertNotNull(dictation.state.value.error)

            val tapSession = start(dictation, holdToTalk = false)
            assertFalse(dictation.state.value.holdToTalk)
            dictation.finish()
            tapSession.complete("轻点插入草稿")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("轻点插入草稿"), results)
            assertEquals(1, holdResults.size)
        } finally {
            dictation.cancel()
        }
    }

    private fun startSystem(target: SpeechInputController = controller, automatic: Boolean = false, hold: Boolean = false): ShadowSpeechRecognizer {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        target.start(SpeechSettings(asr = AsrProvider.SYSTEM, autoSendSilenceMs = if (automatic) 2000 else 0),
            SpeechCredentials(), holdToTalk = hold)
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).also {
            it.triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            shadowOf(Looper.getMainLooper()).idle()
            it.triggerOnReadyForSpeech(Bundle())
        }
    }

    private fun systemText(value: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value))
    }

    @Test fun dictationWaitsForUserEvenWhenSystemReturnsEarly() {
        startSystem().triggerOnResults(systemText("可编辑的草稿"))
        assertTrue(results.isEmpty())
        assertEquals("可编辑的草稿", controller.state.value.preview)
        controller.finish()
        controller.finish()
        assertEquals(listOf("可编辑的草稿"), results)
        assertFalse(controller.state.value.active)
    }

    @Test fun closingOwnerReleasesRecognitionAndTimeouts() {
        val recognizer = startSystem()
        controller.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        recognizer.triggerOnResults(systemText("迟到"))
        assertTrue(recognizer.isDestroyed)
        assertTrue(results.isEmpty())
        assertNull(controller.state.value.error)
    }

    @Test fun ownerCancellationReleasesSystemRecognizer() {
        val recognizer = startSystem()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(recognizer.isDestroyed)
        assertFalse(controller.state.value.active)
    }

    @Test fun systemCanceledSessionCannotSubmitOrReplaceNewPreview() {
        val old = startSystem()
        controller.cancel()
        val current = startSystem()
        old.triggerOnResults(systemText("旧结果"))
        current.triggerOnPartialResults(systemText("新草稿"))
        assertEquals("新草稿", controller.state.value.preview)
        controller.finish()
        current.triggerOnResults(systemText("新结果"))
        assertEquals(listOf("新结果"), results)
    }

    @Test fun systemDisablingAnActiveSessionDiscardsLateRecognitionResults() {
        val recognizer = startSystem()
        controller.start(SpeechSettings(asr = AsrProvider.SYSTEM, recognitionEnabled = false))
        shadowOf(Looper.getMainLooper()).idle()
        recognizer.triggerOnResults(systemText("迟到的旧识别"))
        assertTrue(recognizer.isDestroyed)
        assertFalse(controller.state.value.active)
        assertTrue(results.isEmpty())
    }

    @Test fun anotherAudioOwnerInterruptsSystemRecognition() {
        val recognizer = startSystem()
        val lease = SpeechAudioLease(RuntimeEnvironment.getApplication()) {}
        try {
            lease.acquire(playback = false)
            assertTrue(recognizer.isDestroyed)
            assertFalse(controller.state.value.active)
        } finally { lease.close() }
    }

    @Test fun systemOverlayHonorsAutomaticEndpointAndHoldToTalkBypass() {
        for ((automatic, hold) in listOf(true to false, false to false, true to true)) {
            results.clear()
            val overlay = createController(automaticEndpoint = true)
            try {
                startSystem(overlay, automatic, hold).triggerOnResults(systemText("系统识别"))
                assertEquals(if (automatic && !hold) listOf("系统识别") else emptyList<String>(), results)
                if (!automatic || hold) {
                    assertEquals("系统识别", overlay.state.value.preview)
                    overlay.finish()
                    assertEquals(listOf("系统识别"), results)
                }
                assertFalse(overlay.state.value.active)
            } finally { overlay.cancel() }
        }
    }

    private class FakeStreamSession : SpeechAudioCapture {
        override val frames = Channel<ByteArray>(Channel.UNLIMITED)
        private val stopSignal = CompletableDeferred<Unit>()
        private val transcript = CompletableDeferred<String>()
        private var previewCallback: (String) -> Unit = {}
        var endpointCallback: () -> Unit = {}
        var endpointSilenceMs = -1
            private set
        var finished = false
            private set
        var canceled = false
            private set

        fun attach(
            onReady: () -> Unit,
            onPreview: (String) -> Unit,
            onEndpoint: () -> Unit,
            endpointSilenceMs: Int,
        ) {
            previewCallback = onPreview
            endpointCallback = onEndpoint
            this.endpointSilenceMs = endpointSilenceMs
            onReady()
        }

        override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) {
            onReady()
            try {
                stopSignal.await()
            } finally {
                frames.close()
            }
        }

        fun emitPreview(text: String) {
            if (!canceled) previewCallback(text)
        }

        fun serverEndpoint(text: String) {
            if (canceled) return
            previewCallback(text)
            if (endpointSilenceMs > 0) {
                endpointCallback()
                transcript.complete(text)
            }
        }

        fun complete(text: String) {
            if (!canceled) transcript.complete(text)
        }

        suspend fun awaitTranscript(): String = transcript.await()

        override fun finish() {
            finished = true
            stopSignal.complete(Unit)
        }

        override fun cancel() {
            canceled = true
            finish()
            frames.cancel()
        }
    }
}
