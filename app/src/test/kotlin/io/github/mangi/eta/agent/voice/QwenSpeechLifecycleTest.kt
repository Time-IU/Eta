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
class QwenSpeechLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val captures = mutableListOf<FakeCapture>()
    private val requests = mutableListOf<Request>()
    private val results = mutableListOf<String>()
    private val controllers = mutableListOf<SpeechInputController>()
    private var before = 0
    private var after = 0
    private var refineCalls = 0

    private fun controller(automatic: Boolean = false) = SpeechInputController(
        context = RuntimeEnvironment.getApplication(), scope = scope, automaticEndpoint = automatic,
        beforeCapture = { before++ }, afterCapture = { after++ },
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
        controller.start(SpeechSettings(asr = AsrProvider.QWEN3_LOCAL, refineTranscript = true,
            qwenAsr = QwenSpeechConfig(baseUrl = "invalid-unused-cloud")), holdToTalk = held)
        idle()
    }

    @After fun close() {
        controllers.forEach { it.cancel() }; scope.cancel(); idle()
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
        for ((automatic, held) in listOf(false to false, true to true, true to false)) {
            val c = controller(automatic); start(c, held)
            val r = requests.last()
            val source = captures.last()
            val adaptiveCapture = automatic && !held
            // All legacy local aliases use the shared capture VAD; the backend VAD is disabled.
            assertEquals(0, r.silence)
            if (adaptiveCapture) assertNotSame(source.frames, r.frames)
            else assertSame(source.frames, r.frames)
            assertFalse(source.started)
            val resultsBefore = results.size
            val forwarded = java.io.ByteArrayOutputStream()
            val reader = scope.launch { for (frame in r.frames) forwarded.write(frame) }
            r.begin(); idle()
            assertTrue(source.started)

            // The fixture's two seed samples shift the 20 ms detector windows. Check just
            // below the pause first, then add enough real PCM to complete the final window.
            val beforePause = lifecyclePcm(0, 200) + lifecyclePcm(2500, 400) + lifecyclePcm(0, 1980)
            fun feed(pcm: ByteArray) {
                for (frame in pcm.asList().chunked(SpeechRecorder.FRAME_BYTES)) {
                    source.frames.trySend(frame.toByteArray()).getOrThrow()
                    idle()
                }
            }
            feed(beforePause)
            assertFalse("Capture must not end before the full pause", source.finished)
            assertTrue(reader.isActive)
            val endPause = lifecyclePcm(0, 40)
            feed(endPause)
            assertEquals("Only automatic non-hold capture owns pause endpointing", adaptiveCapture, source.finished)
            assertEquals(adaptiveCapture, reader.isCompleted)
            assertEquals(resultsBefore, results.size)

            if (!adaptiveCapture) {
                // Manual/hold capture must bypass both PCM pause and backend endpoint callbacks.
                r.endpoint(); idle()
                assertFalse(source.finished)
                assertTrue(reader.isActive)
                c.finish(); idle()
            }
            assertTrue(source.finished)
            assertTrue("PCM EOF follows capture release", reader.isCompleted)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4) + beforePause + endPause, forwarded.toByteArray())
            assertEquals(before, after)
            val delivered = results.size
            r.result.complete("本机结果"); idle()
            assertEquals(delivered + 1, results.size)
            assertEquals("本机结果", results.last())
            assertFalse(c.state.value.active)
            assertTrue(r.ended)
            c.cancel(); idle()
            assertEquals(delivered + 1, results.size)
        }
        assertEquals(3, results.size)
        assertEquals(0, refineCalls)
        assertEquals(before, after)
    }

    private fun lifecyclePcm(amplitude: Int, ms: Int): ByteArray = ByteArray(ms * 32).also { pcm ->
        for (i in pcm.indices step 2) {
            val value = if (i % 4 == 0) amplitude else -amplitude
            pcm[i] = value.toByte(); pcm[i + 1] = (value shr 8).toByte()
        }
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
