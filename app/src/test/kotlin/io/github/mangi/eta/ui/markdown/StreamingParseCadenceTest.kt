package io.github.mangi.eta.ui.markdown

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class StreamingParseCadenceTest {
    @Test fun cadenceIncreasesOnlyForLongerDocuments() {
        assertEquals(48L, streamingParseIntervalMillis(0))
        assertEquals(48L, streamingParseIntervalMillis(4095))
        assertEquals(80L, streamingParseIntervalMillis(4096))
        assertEquals(120L, streamingParseIntervalMillis(16384))
    }

    @Test fun closedBurstParsesLatestAndPreservesExactSource() = runBlocking {
        val c = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val seen = mutableListOf<String>()
        repeat(100) { c.trySend(StreamingMarkdownTarget("字".repeat(it + 1), true)) }
        c.close()
        consumeStreamingMarkdownTargets(c, { parser.parse(it.content, !it.isStreaming) },
            { seen += it.originalSource }, minimumIntervalMillis = { 10_000L })
        assertEquals(listOf("字".repeat(100)), seen)
    }

    @Test fun finalTargetWakesLongPendingCadenceWithoutLosingCompletion() = runBlocking {
        val c = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val first = CompletableDeferred<Unit>()
        val seen = mutableListOf<StreamingGfmSnapshot>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            consumeStreamingMarkdownTargets(c, { parser.parse(it.content, !it.isStreaming) }, {
                seen += it
                if (seen.size == 1) first.complete(Unit)
            }, minimumIntervalMillis = { 60_000L })
        }
        c.send(StreamingMarkdownTarget("hello", true)); first.await()
        c.send(StreamingMarkdownTarget("hello ", true))
        c.send(StreamingMarkdownTarget("hello **world**", false)); c.close()
        withTimeout(5_000) { job.join() }
        assertTrue(seen.last().isComplete)
        assertEquals("hello **world**", seen.last().originalSource)
        assertNotNull(seen.last().completedDocumentFor("hello **world**"))
        assertTrue(seen.size <= 3)
    }

    @Test fun channelClosureFlushesLastPartialWithoutWaitingForDeadline() = runBlocking {
        val c = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val first = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            consumeStreamingMarkdownTargets(c, { parser.parse(it.content, !it.isStreaming) }, {
                seen += it.originalSource; first.complete(Unit)
            }, minimumIntervalMillis = { 60_000L })
        }
        c.send(StreamingMarkdownTarget("a", true)); first.await()
        c.send(StreamingMarkdownTarget("abc", true)); c.close()
        withTimeout(5_000) { job.join() }
        assertEquals("abc", seen.last())
    }

    @Test fun correctedTextAndFinalAreNeverReplacedWithOldPrefix() = runBlocking {
        val c = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
        val parser = StreamingGfmParserSession()
        val seen = mutableListOf<String>()
        c.send(StreamingMarkdownTarget("old", true))
        consumeStreamingMarkdownTargets(c, {
            if (it.content == "old") {
                c.send(StreamingMarkdownTarget("corrected", false)); c.close()
            }
            parser.parse(it.content, !it.isStreaming)
        }, { seen += it.originalSource }, minimumIntervalMillis = { 60_000L })
        assertEquals(listOf("corrected"), seen)
    }
}
