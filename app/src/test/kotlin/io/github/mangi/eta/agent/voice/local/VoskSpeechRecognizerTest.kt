// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises production streaming/ownership logic using an injected native boundary, not JNI. */
class VoskSpeechRecognizerTest {
    private class Decoder : VoskDecoder {
        var boundary = false
        var partialText = ""
        var resultText = ""
        var finalText = ""
        var finalCalls = 0
        var closeCalls = 0
        val pcm = mutableListOf<ByteArray>()
        override fun accept(pcm: ByteArray): Boolean { this.pcm += pcm; return boundary }
        override fun result() = JSONObject().put("text", resultText).toString()
        override fun partial() = JSONObject().put("partial", partialText).toString()
        override fun finalResult(): String { finalCalls++; return JSONObject().put("text", finalText).toString() }
        override fun close() { closeCalls++ }
    }
    private val pcm = byteArrayOf(1, 2, 3, 4)
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected invalid/closed input to fail") } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) { }
    }
    @Test fun changingPartialsReplaceAndDuplicatesAreSuppressed() {
        val decoder = Decoder(); val stream = VoskSpeechSession(decoder)
        assertNull(stream.accept(pcm))
        decoder.partialText = "打开"; assertEquals("打开", stream.accept(pcm))
        assertNull(stream.accept(pcm))
        decoder.partialText = "打开设置"; assertEquals("打开设置", stream.accept(pcm))
        decoder.partialText = ""; assertEquals("", stream.accept(pcm))
        stream.close()
    }
    @Test fun nativeEndpointCommitsSegmentWithoutFlushingUserUtterance() {
        val decoder = Decoder(); val stream = VoskSpeechSession(decoder)
        decoder.boundary = true; decoder.resultText = "第一段"
        assertEquals("第一段", stream.accept(pcm)); assertEquals(0, decoder.finalCalls)
        decoder.boundary = false; decoder.partialText = "第二"
        assertEquals("第一段 第二", stream.accept(pcm))
        decoder.boundary = true; decoder.resultText = "第二段"
        assertEquals("第一段 第二段", stream.accept(pcm))
        decoder.finalText = "最后"; assertEquals("第一段 第二段 最后", stream.finish())
        assertEquals(1, decoder.finalCalls)
        stream.close()
    }
    @Test fun finalDoesNotRepeatTheLastPartialOrCommittedSegments() {
        val decoder = Decoder(); val stream = VoskSpeechSession(decoder)
        decoder.partialText = "你好"; assertEquals("你好", stream.accept(pcm))
        decoder.boundary = true; decoder.resultText = "你好"; assertNull(stream.accept(pcm))
        assertEquals("你好", stream.finish()); assertEquals(1, decoder.finalCalls)
        reject { stream.finish() }; reject { stream.accept(pcm) }
        assertEquals(1, decoder.finalCalls); stream.close()
    }
    @Test fun cancellingClosesWithoutFinalAndCloseIsIdempotent() {
        val decoder = Decoder(); val stream = VoskSpeechSession(decoder)
        stream.accept(pcm); stream.close(); stream.close()
        assertEquals(0, decoder.finalCalls); assertEquals(1, decoder.closeCalls)
        reject { stream.finish() }; reject { stream.accept(pcm) }
    }
    @Test fun warmModelGetsFreshUtteranceAndReleasesOnlyOnce() {
        val decoders = mutableListOf<Decoder>(); var modelCloses = 0
        val model = VoskSpeechRecognizer({ Decoder().also(decoders::add) }, { modelCloses++ })
        model.openStream().use { decoders.last().finalText = "上一轮"; assertEquals("上一轮", it.finish()) }
        model.openStream().use { assertEquals("", it.finish()) }
        assertEquals(2, decoders.size); assertTrue(decoders.all { it.closeCalls == 1 })
        assertEquals(0, modelCloses); model.release(); model.release()
        assertEquals(1, modelCloses); reject { model.openStream() }
    }
    @Test fun decoderCreationFailureStillLeavesModelAvailableForRelease() {
        var modelCloses = 0
        val model = VoskSpeechRecognizer({ throw IllegalStateException("fixture") }, { modelCloses++ })
        reject { model.openStream() }; model.release(); assertEquals(1, modelCloses)
    }
    @Test fun boundedPcm16IsPassedWithoutScalingOrModification() {
        val decoder = Decoder(); val stream = VoskSpeechSession(decoder)
        stream.accept(pcm); assertArrayEquals(pcm, decoder.pcm.single())
        reject { stream.accept(byteArrayOf()) }; reject { stream.accept(byteArrayOf(1)) }
        reject { stream.accept(ByteArray(LocalSpeechProtocol.FEED_CHUNK + 2)) }
        stream.close()
    }
    @Test fun normalizedFloatFallbackConvertsToSaturatedPcm16() {
        val decoder = Decoder(); val model = VoskSpeechRecognizer({ decoder }, {})
        model.recognize(floatArrayOf(-2f, -1f, 0f, 0.5f, 1f, 2f))
        assertArrayEquals(byteArrayOf(0, -128, 0, -128, 0, 0, 0, 64, -1, 127, -1, 127), decoder.pcm.single())
        assertEquals(1, decoder.closeCalls); model.release()
    }
    @Test fun oversizedPartialAndCombinedTranscriptAreRejected() {
        val decoder = Decoder(); val stream = VoskSpeechSession(decoder)
        decoder.partialText = "中".repeat(LocalSpeechProtocol.MAX_TEXT_BYTES)
        reject { stream.accept(pcm) }
        decoder.partialText = ""; decoder.boundary = true; decoder.resultText = "a".repeat(20_000)
        stream.accept(pcm); reject { stream.accept(pcm) }; stream.close()
    }
    @Test fun finishFailureStillClosesDecoderWithUse() {
        val decoder = Decoder().apply { finalText = "x".repeat(LocalSpeechProtocol.MAX_TEXT_BYTES) }
        reject { VoskSpeechSession(decoder).use { it.finish() } }
        assertEquals(1, decoder.closeCalls); assertEquals(1, decoder.finalCalls)
    }
}
