package io.github.mangi.eta.agent.voice

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

internal fun endpointPcm(amplitude: Int, ms: Int): ByteArray = ByteArray(ms * 32).also { pcm ->
    for (i in pcm.indices step 2) {
        val value = if (i % 4 == 0) amplitude else -amplitude
        pcm[i] = value.toByte(); pcm[i + 1] = (value shr 8).toByte()
    }
}

class SpeechEndpointDetectorTest {
    @Test fun silenceWithoutVoiceNeverEndpoints() {
        assertFalse(SpeechEndpointDetector(2000).accept(endpointPcm(0, 10_000)))
    }
    @Test fun pauseUsesSamplesAndRequiresTheFullConfiguredSilence() {
        val detector = SpeechEndpointDetector(2000)
        assertFalse(detector.accept(endpointPcm(0, 200)))
        assertFalse(detector.accept(endpointPcm(2000, 400)))
        assertFalse(detector.accept(endpointPcm(0, 1980)))
        assertTrue(detector.accept(endpointPcm(0, 20)))
        assertFalse(detector.accept(endpointPcm(0, 4000)))
    }
    @Test fun immediateSpeechSurvivesCalibrationAndDoesNotStopAfterTwoSecondsOfSpeech() {
        val detector = SpeechEndpointDetector(2000)
        assertFalse(detector.accept(endpointPcm(2000, 4000)))
        assertTrue(detector.accept(endpointPcm(0, 2000)))
    }
    @Test fun constantBackgroundAboveOld262ThresholdIsCalibrated() {
        val detector = SpeechEndpointDetector(2000)
        assertFalse(detector.accept(endpointPcm(500, 1000)))
        assertFalse(detector.accept(endpointPcm(2500, 400)))
        assertTrue(detector.accept(endpointPcm(500, 2000)))
    }
    @Test fun clickDoesNotArmVoice() {
        val detector = SpeechEndpointDetector(2000)
        assertFalse(detector.accept(endpointPcm(0, 200)))
        assertFalse(detector.accept(endpointPcm(8000, 40)))
        assertFalse(detector.accept(endpointPcm(0, 5000)))
    }
    @Test fun zeroDisablesEndpoint() {
        val detector = SpeechEndpointDetector(0)
        assertFalse(detector.accept(endpointPcm(2500, 400) + endpointPcm(0, 5000)))
    }
    @Test fun partialWindowPrefixRequiresTheNextCompleteAnalysisWindowNotMoreLooperDraining() {
        val detector = SpeechEndpointDetector(2000)
        // Lifecycle FakeCapture starts with these two samples; this shifts every 20ms window.
        val pcm = byteArrayOf(1, 2, 3, 4) + endpointPcm(0, 200) +
            endpointPcm(2500, 400) + endpointPcm(0, 2000)
        assertFalse(detector.accept(pcm))
        // No coroutine or wall clock is involved: only new samples complete the trailing window.
        assertFalse(detector.accept(byteArrayOf()))
        assertTrue(detector.accept(endpointPcm(0, 20)))
    }

    @Test fun arbitraryByteBoundariesStillHaveTheSameSampleClock() {
        val detector = SpeechEndpointDetector(2000)
        val data = endpointPcm(0, 200) + endpointPcm(2000, 400) + endpointPcm(0, 2000)
        var events = 0
        for (part in data.toList().chunked(317)) if (detector.accept(part.toByteArray())) events++
        assertEquals(1, events)
    }
    @Test fun resumedSpeechResetsTrailingSilence() {
        val detector = SpeechEndpointDetector(2000)
        assertFalse(detector.accept(endpointPcm(0, 200) + endpointPcm(2000, 400)))
        assertFalse(detector.accept(endpointPcm(0, 1800)))
        assertFalse(detector.accept(endpointPcm(2000, 200)))
        assertFalse(detector.accept(endpointPcm(0, 1980)))
        assertTrue(detector.accept(endpointPcm(0, 20)))
    }
    @Test fun forwardingIsExactAndCloseWaitsForCaptureRelease() = runBlocking {
        val data = listOf(endpointPcm(0, 200), endpointPcm(2000, 400), endpointPcm(0, 2000), byteArrayOf(3, 4))
        val stopped = CompletableDeferred<Unit>()
        var released = false
        var finished = 0
        val source = object : SpeechAudioCapture {
            override val frames = Channel<ByteArray>(Channel.UNLIMITED)
            override suspend fun record(onReady: () -> Unit, onLevel: (Float) -> Unit) {
                try { onReady(); data.forEach { frames.send(it) }; stopped.await() }
                finally { released = true; frames.close() }
            }
            override fun finish() { finished++; stopped.complete(Unit) }
            override fun cancel() { stopped.complete(Unit); frames.cancel() }
        }
        val capture = EndpointSpeechAudioCapture(source, 2000) { source.finish() }
        assertFalse(released)
        val recording = launch { capture.record({}, {}) }
        val out = ByteArrayOutputStream()
        for (frame in capture.frames) out.write(frame)
        assertTrue(released)
        recording.join()
        assertEquals(1, finished)
        assertArrayEquals(data.reduce { a, b -> a + b }, out.toByteArray())
    }
}
