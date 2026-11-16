// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.agent.voice.qwen.WarmCachePolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

/** Calls the exact client pump/finalization/ownership policies used by Engine and Service. No JNI. */
class LocalSpeechRuntimeSessionTest {
    private val quietPcm = ByteArray(320) { if (it % 2 == 0) 240.toByte() else 0 }
    private class Stream(private val finalText: String, private val log: MutableList<String>) : LocalSpeechStreamingSession {
        override fun accept(pcm: ByteArray): String { log += "partial"; return "低音量结果" }
        override fun finish(): String { log += "decoder.finish"; return finalText }
        override fun close() { log += "decoder.close" }
    }
    @Test fun quietStreamingAudioIsFinalizedEvenBelowLegacyRmsGate() {
        val log = mutableListOf<String>(); val audio = LocalSpeechPcmAccumulator(0)
        val quietSpeech = ByteArray(400 * 32) { if (it % 2 == 0) 240.toByte() else 0 }
        val capturePcm = ByteArray(200 * 32) + quietSpeech + ByteArray(2000 * 32)
        // Real capture-side VAD accepts this utterance, while Service's legacy RMS does not.
        assertTrue(io.github.mangi.eta.agent.voice.SpeechEndpointDetector(2000).accept(capturePcm))
        capturePcm.asList().chunked(LocalSpeechProtocol.FEED_CHUNK).forEach { audio.append(it.toByteArray()) }
        assertFalse(audio.hasVoice)
        val utterance = LocalSpeechUtterance().apply { stream = Stream("低音量结果", log) }
        assertEquals("低音量结果", utterance.finish(audio) { error("No batch fallback") })
        assertEquals(listOf("decoder.finish"), log)
    }
    @Test fun legacyBatchKeepsRmsGateButStreamingEmptyFinalMeansNoSpeech() {
        val audio = LocalSpeechPcmAccumulator(0).apply { append(quietPcm) }
        var batchCalled = false
        try { LocalSpeechUtterance().finish(audio) { batchCalled = true; "batch" }; fail() }
        catch (e: SpeechFailure) { assertEquals(SpeechErrorCode.NO_SPEECH, e.code) }
        assertFalse(batchCalled)
        val log = mutableListOf<String>()
        val utterance = LocalSpeechUtterance().apply { stream = Stream(" ", log) }
        try { utterance.finish(audio) { error("No fallback") }; fail() }
        catch (e: SpeechFailure) { assertEquals(SpeechErrorCode.NO_SPEECH, e.code) }
        assertEquals(listOf("decoder.finish"), log)
    }
    @Test fun actualClientPumpRunsPartialAckNextFrameEofFinishSingleFinal() = runBlocking {
        withTimeout(5_000) {
            val events = Channel<LocalSpeechEvent>(16); val frames = Channel<ByteArray>(2)
            val log = mutableListOf<String>(); val decoderLog = mutableListOf<String>()
            val utterance = LocalSpeechUtterance().apply { stream = Stream("最终结果", decoderLog) }
            val audio = LocalSpeechPcmAccumulator(0)
            val session = LocalSpeechClientSession(events, 0, { log += "PARTIAL:$it" }, { error("Manual bypass") })
            val firstSent = CompletableDeferred<Unit>(); val allowEof = CompletableDeferred<Unit>()
            frames.send(quietPcm); frames.send(quietPcm)
            val client = async {
                session.capture(frames) { frame, offset, count, sequence ->
                    log += "PCM:$sequence"; val bytes = frame.copyOfRange(offset, offset + count)
                    audio.append(bytes)
                    events.send(LocalSpeechEvent(LocalSpeechProtocol.PARTIAL, text = utterance.stream!!.accept(bytes)!!))
                    if (sequence == 1) firstSent.complete(Unit)
                    log += "ACK:$sequence"; events.send(LocalSpeechEvent(LocalSpeechProtocol.ACK, sequence))
                    if (sequence == 2) allowEof.complete(Unit)
                }
                log += "EOF"
                session.finish {
                    log += "FINISH"
                    val finalText = utterance.finish(audio) { error("No batch fallback") }
                    utterance.closeBeforeCache({ error("Close failure") }) { log += "cache.finish" }
                    log += "FINAL"; events.trySend(LocalSpeechEvent(LocalSpeechProtocol.FINAL, text = finalText)).getOrThrow()
                }
            }
            firstSent.await(); allowEof.await(); frames.close()
            assertEquals("最终结果", client.await())
            assertEquals(2, log.count { it.startsWith("PCM:") })
            assertEquals(2, log.count { it.startsWith("PARTIAL:") })
            assertTrue(log.indexOf("PARTIAL:低音量结果") < log.indexOf("PCM:2"))
            assertTrue(log.indexOf("PCM:2") < log.indexOf("EOF"))
            assertTrue(log.indexOf("EOF") < log.indexOf("FINISH"))
            assertEquals(1, log.count { it == "FINAL" })
            assertEquals(listOf("partial", "partial", "decoder.finish", "decoder.close"), decoderLog)
            assertTrue(events.tryReceive().isFailure)
        }
    }
    @Test fun standalonePartialWhileWaitingForNextFrameDoesNotBecomeEof() = runBlocking {
        withTimeout(5_000) {
            val events = Channel<LocalSpeechEvent>(16); val frames = Channel<ByteArray>()
            val seen = CompletableDeferred<Unit>(); var sent = 0
            val session = LocalSpeechClientSession(events, 0, { seen.complete(Unit) }, {})
            val client = launch { session.capture(frames) { _, _, _, sequence ->
                sent++; events.send(LocalSpeechEvent(LocalSpeechProtocol.ACK, sequence))
            } }
            events.send(LocalSpeechEvent(LocalSpeechProtocol.PARTIAL, text = "预览")); seen.await()
            assertTrue(client.isActive); frames.send(quietPcm); frames.close(); client.join()
            assertEquals(1, sent)
        }
    }
    @Test fun partialCannotReplaceAckOrUnblockNextPcm() = runBlocking {
        withTimeout(5_000) {
            val events = Channel<LocalSpeechEvent>(16); val frames = Channel<ByteArray>(2)
            frames.send(quietPcm); frames.send(quietPcm); frames.close()
            val partialSeen = CompletableDeferred<Unit>(); var sent = 0
            val session = LocalSpeechClientSession(events, 0, { partialSeen.complete(Unit) }, {})
            val client = launch {
                session.capture(frames) { _, _, _, sequence ->
                    sent++
                    if (sequence == 1) events.send(LocalSpeechEvent(LocalSpeechProtocol.PARTIAL, text = "预览"))
                    else events.send(LocalSpeechEvent(LocalSpeechProtocol.ACK, sequence))
                }
            }
            partialSeen.await(); assertEquals(1, sent); assertTrue(client.isActive)
            events.send(LocalSpeechEvent(LocalSpeechProtocol.ACK, sequence = 1))
            client.join(); assertEquals(2, sent)
        }
    }
    @Test fun manualEndpointBypassAndAutomaticEndpointRemainUnchanged() = runBlocking {
        for (silence in listOf(0, 2000)) {
            val events = Channel<LocalSpeechEvent>(16); val frames = Channel<ByteArray>(1)
            val partialSeen = CompletableDeferred<Unit>(); var endpoints = 0; var sent = 0
            val session = LocalSpeechClientSession(events, silence, { partialSeen.complete(Unit) }, { endpoints++ })
            val client = launch {
                session.capture(frames) { _, _, _, seq -> sent++; events.send(LocalSpeechEvent(LocalSpeechProtocol.ACK, seq)) }
            }
            events.send(LocalSpeechEvent(LocalSpeechProtocol.ENDPOINT))
            if (silence == 0) {
                events.send(LocalSpeechEvent(LocalSpeechProtocol.PARTIAL, text = "still recording"))
                partialSeen.await(); assertTrue(client.isActive)
                frames.send(quietPcm); frames.close()
            }
            client.join(); assertEquals(if (silence == 0) 0 else 1, endpoints)
            assertEquals(if (silence == 0) 1 else 0, sent)
        }
    }
    @Test fun voicedBatchStillCallsHistoricalDecoderExactlyOnce() {
        val audio = LocalSpeechPcmAccumulator(0).apply {
            append(ByteArray(320) { if (it % 2 == 0) 64 else 1 })
        }
        assertTrue(audio.hasVoice); var calls = 0
        assertEquals("batch", LocalSpeechUtterance().finish(audio) { calls++; " batch " })
        assertEquals(1, calls)
    }
    @Test fun cancellationWhileAwaitingAckNeverSendsNextFrameOrFinish() = runBlocking {
        withTimeout(5_000) {
            val events = Channel<LocalSpeechEvent>(16); val frames = Channel<ByteArray>(2)
            frames.send(quietPcm); frames.send(quietPcm); frames.close()
            val sent = CompletableDeferred<Unit>(); var pcmCount = 0; var finishCount = 0
            val session = LocalSpeechClientSession(events, 0, {}, {})
            val client = launch {
                session.capture(frames) { _, _, _, _ -> pcmCount++; sent.complete(Unit) }
                session.finish { finishCount++ }
            }
            sent.await(); client.cancelAndJoin()
            assertEquals(1, pcmCount); assertEquals(0, finishCount)
        }
    }
    @Test fun processDeathWhileAwaitingAckPropagatesServerFailureWithoutFinish() = runBlocking {
        withTimeout(5_000) {
            val events = Channel<LocalSpeechEvent>(16); val frames = Channel<ByteArray>(2)
            frames.send(quietPcm); frames.send(quietPcm); frames.close()
            val death = SpeechFailure(SpeechErrorCode.SERVER, "fixture process death")
            var pcmCount = 0; var finishCount = 0
            val session = LocalSpeechClientSession(events, 0, {}, {})
            try {
                session.capture(frames) { _, _, _, _ -> pcmCount++; events.close(death) }
                session.finish { finishCount++ }; fail()
            } catch (e: SpeechFailure) { assertSame(death, e) }
            assertEquals(1, pcmCount); assertEquals(0, finishCount)
        }
    }
    @Test fun wrongAckIsRejectedBeforeSecondFrame() = runBlocking {
        val events = Channel<LocalSpeechEvent>(16); val frames = Channel<ByteArray>(2)
        frames.send(quietPcm); frames.send(quietPcm); frames.close(); var sent = 0
        try {
            LocalSpeechClientSession(events, 0, {}, {}).capture(frames) { _, _, _, _ ->
                sent++; events.send(LocalSpeechEvent(LocalSpeechProtocol.ACK, sequence = 99))
            }; fail()
        } catch (e: SpeechFailure) { assertEquals(SpeechErrorCode.PROTOCOL, e.code) }
        assertEquals(1, sent)
    }
    @Test fun decoderClosePrecedesTrimmedModelReleaseAndWarmLeaseReturn() {
        for (trim in listOf(false, true)) {
            val log = mutableListOf<String>(); val owner = Any()
            val model = VoskSpeechRecognizer({ object : VoskDecoder {
                override fun accept(pcm: ByteArray) = false
                override fun result() = "{\"text\":\"\"}"
                override fun partial() = "{\"partial\":\"\"}"
                override fun finalResult() = "{\"text\":\"\"}"
                override fun close() { log += "decoder.close" }
            } }, { log += "model.close" })
            val cache = WarmCachePolicy<String, VoskSpeechRecognizer>({ it.release() })
            val (borrowed, _) = cache.borrow(owner, "identity") { "identity" to model }
            val utterance = LocalSpeechUtterance().apply { stream = borrowed.openStream() }
            if (trim) cache.trim()
            utterance.closeBeforeCache({ cache.trim() }) { log += "cache.finish"; cache.finish(owner, 0) }
            if (!trim) { assertEquals(listOf("decoder.close", "cache.finish"), log); cache.evict() }
            assertEquals(listOf("decoder.close", "cache.finish", "model.close"), log)
            utterance.closeBeforeCache({ fail() }) { }; assertEquals(1, log.count { it == "decoder.close" })
        }
    }
    @Test fun decoderCloseFailureStillReturnsLeaseAndEvictsModelAfterCloseAttempt() {
        val log = mutableListOf<String>(); val owner = Any()
        val cache = WarmCachePolicy<String, String>({ log += "model.close" })
        cache.borrow(owner, "identity") { "identity" to "model" }
        val utterance = LocalSpeechUtterance().apply { stream = object : LocalSpeechStreamingSession {
            override fun accept(pcm: ByteArray): String? = null
            override fun finish() = ""
            override fun close() { log += "decoder.close"; throw IllegalStateException("fixture") }
        } }
        utterance.closeBeforeCache({ cache.trim() }) { log += "cache.finish"; cache.finish(owner, 0) }
        assertEquals(listOf("decoder.close", "cache.finish", "model.close"), log)
        assertTrue(cache.idle); assertFalse(cache.loaded)
    }
}
