package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.QwenSpeechConfig
import io.github.mangi.eta.data.model.SpeechSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalSpeechLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captures = mutableListOf<FakeCapture>()
    private val requests = mutableListOf<Request>()
    private val results = mutableListOf<String>()
    private val controllers = mutableListOf<SpeechInputController>()
    private var beforeHook: () -> Unit = {}
    private var before = 0
    private var after = 0
    private var refineCalls = 0

    private fun controller(automatic: Boolean = false) = SpeechInputController(
        context = RuntimeEnvironment.getApplication(), scope = scope, automaticEndpoint = automatic,
        beforeCapture = { before++; beforeHook() }, afterCapture = { after++ },
        refiner = SpeechTranscriptRefiner(loadConfig = { refineCalls++; error("Offline must not refine") }),
        createRecorder = { FakeCapture().also(captures::add) },
        recognizeStream = { _, _, _, _, _, _, _ -> error("Offline must not use cloud stream") },
        recognizeFile = { _, _, _, _, _ -> error("Offline must not upload a file") },
        recognizeOffline = { _, _, _, _, _, _ -> error("Qwen must not silently use NCNN") },
        recognizeLocal = { _, frames, begin, partial, endpoint, silence ->
            if (!automatic) assertSame(captures.last().frames, frames)
            val request = Request(frames, begin, partial, endpoint, silence)
            requests += request
            try { request.result.await() } finally { request.ended = true }
        }, onResult = results::add,
    ).also(controllers::add)

    private fun start(controller: SpeechInputController, held: Boolean = false) {
        // No credentials argument: a repository read is forbidden, even with refine enabled.
        controller.start(SpeechSettings(asr = AsrProvider.LOCAL, refineTranscript = true,
            qwenAsr = QwenSpeechConfig(baseUrl = "invalid-unused-cloud")), holdToTalk = held)
        idle()
    }

    @After fun close() {
        controllers.forEach { it.cancel() }; scope.cancel(); idle()
    }

    @Test fun beforeCaptureCancellationRejectsStaleBeginWithoutMicOrDelivery() {
        val c = controller()
        beforeHook = { c.cancel() }
        start(c)
        requests.single().begin(); idle()
        assertFalse(captures.single().started)
        assertTrue(results.isEmpty())
        assertFalse(c.state.value.active)
    }

    @Test fun readinessGatesCapturePartialReplacesAndRealLevelSurvivesUntilFinish() {
        val c = controller(); start(c)
        assertFalse(captures.single().started)
        assertEquals(0, before)
        val request = requests.single()
        request.begin(); idle()
        assertTrue(captures.single().started)
        assertEquals(1, before)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), request.frames.tryReceive().getOrThrow())
        assertEquals(0.42f, c.state.value.level)
        request.partial("打开"); request.partial("打开设置"); idle()
        assertEquals("打开设置", c.state.value.preview)
        assertTrue(results.isEmpty())
        c.finish(); c.finish(); idle()
        request.result.complete("打开设置"); idle()
        assertEquals(listOf("打开设置"), results)
        assertEquals(0, refineCalls)
        assertEquals(1, after)
        assertFalse(c.state.value.active)
    }

    @Test fun onlyAutomaticNonHoldEnablesEndpointAndEndpointFinishesCapture() {
        for ((automatic, held, expected) in listOf(Triple(false, false, 0), Triple(true, true, 0), Triple(true, false, 2000))) {
            val c = controller(automatic); start(c, held)
            val r = requests.last(); assertEquals(0, r.silence) // Shared capture is the sole local VAD owner.
            r.begin(); idle()
            if (expected > 0) { r.endpoint(); idle(); assertTrue(captures.last().finished) }
            c.cancel(); idle()
        }
        assertTrue(results.isEmpty())
    }

    @Test fun capturePauseFinishesLocalWithoutNativeEndpointAndKeepsTranscriptLocal() {
        val c = controller(automatic = true)
        start(c)
        val request = requests.single()
        assertFalse(captures.single().started)
        // Act like the real local engine: continuously consume the forwarded PCM rather than
        // leaving its bounded channel blocked while awaiting the synthetic final transcript.
        val forwarded = java.io.ByteArrayOutputStream()
        val reader = scope.launch { for (frame in request.frames) forwarded.write(frame) }
        request.begin(); idle()
        // Two seed samples in FakeCapture offset the detector's 20ms analysis windows.
        val pcm = endpointPcm(0, 200) + endpointPcm(2500, 400) + endpointPcm(0, 2020)
        for (frame in pcm.asList().chunked(SpeechRecorder.FRAME_BYTES)) {
            captures.single().frames.trySend(frame.toByteArray()).getOrThrow()
            idle()
        }
        assertTrue(captures.single().finished)
        assertTrue("All forwarded PCM must drain before decode", reader.isCompleted)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4) + pcm, forwarded.toByteArray())
        assertTrue(results.isEmpty())
        request.result.complete("本机结果"); idle()
        assertEquals(listOf("本机结果"), results)
        assertFalse(c.state.value.active)
        assertEquals(0, refineCalls)
        assertEquals(before, after)
    }

    @Test fun cancelLoadingRejectsLateBeginPreviewAndResult() {
        val c = controller(); start(c)
        val old = requests.single(); c.cancel(); idle()
        assertTrue(old.ended)
        old.begin(); old.partial("过时预览"); old.result.complete("过时结果"); idle()
        assertFalse(captures.single().started)
        assertTrue(results.isEmpty()); assertEquals("", c.state.value.preview)
        start(c)
        val current = requests.last(); current.begin(); idle()
        old.partial("旧轮次"); idle()
        assertNotEquals("旧轮次", c.state.value.preview)
        c.cancel(); idle()
        assertEquals(before, after)
    }

    @Test fun failureNeverFallsBackOrSubmits() {
        val c = controller(); start(c)
        requests.single().result.completeExceptionally(IllegalStateException("fixture-worker-death")); idle()
        assertTrue(results.isEmpty()); assertFalse(c.state.value.active)
        assertNotNull(c.state.value.error)
        assertEquals(0, before); assertEquals(0, refineCalls)
    }

    @Test fun finishBeforeReadyCancelsWithoutOpeningMicrophone() {
        val c = controller(); start(c); c.finish(); idle()
        requests.single().begin(); idle()
        assertFalse(captures.single().started); assertTrue(requests.single().ended)
        assertTrue(results.isEmpty())
    }

    @Test fun cancelActiveCaptureAndOwnerCancellationReleaseOnceWithoutSending() {
        val c = controller(); start(c)
        requests.single().begin(); idle()
        c.cancel(); c.cancel(); idle()
        assertEquals(1, before); assertEquals(1, after)
        assertTrue(captures.single().finished); assertTrue(results.isEmpty())
        start(c); requests.last().begin(); idle()
        scope.cancel(); idle()
        assertEquals(2, before); assertEquals(2, after)
        assertTrue(requests.last().ended); assertTrue(results.isEmpty())
    }

    @Test fun emptyOfflineFinalReportsNoSpeechRatherThanCloudFallback() {
        val c = controller(); start(c)
        requests.single().begin(); idle()
        c.finish(); idle()
        requests.single().result.complete("  "); idle()
        assertTrue(results.isEmpty()); assertEquals("没有识别到语音，请重试", c.state.value.error)
        assertEquals(before, after); assertEquals(0, refineCalls)
    }

    @Test fun typedLocalFailureKeepsChineseMessageAndConfigureAction() {
        val c = controller(); start(c)
        requests.single().result.completeExceptionally(
            SpeechFailure(SpeechErrorCode.CONFIGURATION, "Qwen3 离线模型缺失，请先下载或导入"),
        ); idle()
        assertEquals("Qwen3 离线模型缺失，请先下载或导入", c.state.value.error)
        assertTrue(c.state.value.configureAvailable)
        assertTrue(results.isEmpty()); assertEquals(0, before); assertEquals(0, refineCalls)
    }

    @Test fun onlineFailureNeverInvokesQwenOrNcnnFallback() {
        var cloudCalls = 0
        var localCalls = 0
        val c = SpeechInputController(
            context = RuntimeEnvironment.getApplication(), scope = scope,
            createRecorder = { FakeCapture().also(captures::add) },
            recognizeStream = { _, _, _, _, _, _, _ -> cloudCalls++; throw SpeechFailure(SpeechErrorCode.NETWORK, "测试云端失败") },
            recognizeLocal = { _, _, _, _, _, _ -> localCalls++; error("No LOCAL fallback") },
            recognizeOffline = { _, _, _, _, _, _ -> localCalls++; error("No NCNN fallback") },
            recognizeQwen = { _, _, _, _, _, _ -> localCalls++; error("No Qwen fallback") },
            onResult = results::add,
        ).also(controllers::add)
        c.start(SpeechSettings(asr = AsrProvider.QWEN_REALTIME),
            io.github.mangi.eta.data.model.SpeechCredentials(qwenAsr = "test-no-request"))
        idle()
        assertEquals(1, cloudCalls); assertEquals(0, localCalls)
        assertEquals("测试云端失败", c.state.value.error)
        assertTrue(results.isEmpty())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private class Request(val frames: kotlinx.coroutines.channels.ReceiveChannel<ByteArray>, val begin: () -> Unit, val partial: (String) -> Unit,
        val endpoint: () -> Unit, val silence: Int) {
        val result = CompletableDeferred<String>()
        var ended = false
    }
    private class FakeCapture : SpeechAudioCapture {
        override val frames = Channel<ByteArray>(10)
        private val stopped = CompletableDeferred<Unit>()
        var started = false
        var finished = false
        override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) {
            started = true
            try {
                onReady(); onLevel(0.42f)
                frames.send(byteArrayOf(1, 2, 3, 4))
                stopped.await()
            } finally { frames.close() }
        }
        override fun finish() { finished = true; stopped.complete(Unit) }
        override fun cancel() { finish(); frames.cancel() }
    }
}
