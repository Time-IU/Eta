package io.github.mangi.eta.agent.voice.qwen

import org.junit.Assert.*
import org.junit.Test

class QwenPcmAccumulatorTest {
    private fun pcm(value: Int, samples: Int = 1600) = ByteArray(samples * 2) {
        if (it % 2 == 0) value.toByte() else (value shr 8).toByte()
    }
    @Test fun initialSilenceCannotEndpointAndSilenceMustBeConsecutive() {
        val buffer = QwenPcmAccumulator(300)
        repeat(20) { assertFalse(buffer.append(pcm(0))) }
        assertFalse(buffer.hasVoice)
        assertFalse(buffer.append(pcm(2000)))
        repeat(2) { assertFalse(buffer.append(pcm(0))) }
        assertFalse(buffer.append(pcm(2000))) // voice resets the silence clock
        repeat(2) { assertFalse(buffer.append(pcm(0))) }
        assertTrue(buffer.append(pcm(0)))
    }
    @Test fun manualHoldNeverAutomaticallyEndpointsAndLimitIsExplicit() {
        val buffer = QwenPcmAccumulator(0)
        assertFalse(buffer.append(pcm(2000)))
        repeat(299) { assertFalse(buffer.append(pcm(0))) }
        assertEquals(960_000, buffer.size)
        val failure = assertThrows(IllegalArgumentException::class.java) { buffer.append(pcm(0)) }
        assertTrue(failure.message.orEmpty().contains("30 秒"))
        assertEquals(960_000, buffer.size) // no clipping, overwriting or unbounded growth
    }
    @Test fun pcmNormalizationIncludesNegativeFullScale() {
        val buffer = QwenPcmAccumulator(0)
        buffer.append(byteArrayOf(0, 128.toByte(), 255.toByte(), 127, 0, 0))
        assertArrayEquals(floatArrayOf(-1f, 32767 / 32768f, 0f), buffer.samples(0, 3), 0f)
    }
    @Test fun malformedAndOversizedChunksAreRejectedBeforeMutation() {
        val buffer = QwenPcmAccumulator(0)
        for (chunk in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(3202))) {
            assertThrows(IllegalArgumentException::class.java) { buffer.append(chunk) }
            assertEquals(0, buffer.size)
        }
    }
    @Test fun timeoutsDoNotShareTheCaptureBudgetWithModelLoadOrDecode() {
        assertEquals(90_000L, QwenAsrProtocol.PREPARE_MS)
        assertEquals(60_000L, QwenAsrProtocol.CAPTURE_MS)
        assertEquals(120_000L, QwenAsrProtocol.DECODE_MS)
        assertEquals(3200, QwenAsrProtocol.FEED_CHUNK)
    }
}
