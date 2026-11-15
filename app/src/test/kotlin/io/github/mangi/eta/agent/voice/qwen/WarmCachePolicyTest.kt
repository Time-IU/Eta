package io.github.mangi.eta.agent.voice.qwen

import org.junit.Assert.*
import org.junit.Test

class WarmCachePolicyTest {
    @Test fun coldHotTimeoutReload() {
        var loads = 0; var releases = 0
        val cache = WarmCachePolicy<String, Any>({ releases++ })
        fun load() = "v1" to Any().also { loads++ }
        val a = Any(); val b = Any(); val c = Any()
        val (first, hit) = cache.borrow(a, "v1", ::load); assertFalse(hit)
        assertTrue(cache.finish(a, 10)); val stale = cache.generation
        val (second, hot) = cache.borrow(b, "v1", ::load)
        assertTrue(hot); assertSame(first, second)
        assertFalse(cache.expire(stale, 999_999))
        cache.finish(b, 20)
        assertFalse(cache.expire(cache.generation, 120_019))
        assertTrue(cache.expire(cache.generation, 120_020))
        assertEquals(1, releases)
        assertFalse(cache.borrow(c, "v1", ::load).second)
        cache.finish(c, 30); cache.evict()
        assertEquals(2, loads); assertEquals(2, releases)
    }
    @Test fun trimDefersUntilOwnerFinishedAndStaleCancelIsHarmless() {
        var releases = 0
        val cache = WarmCachePolicy<String, Any>({ releases++ })
        val a = Any(); val b = Any()
        cache.borrow(a, "v1") { "v1" to Any() }
        cache.trim(); assertEquals(0, releases)
        assertTrue(runCatching { cache.borrow(b, "v1") { error("must not load") } }.isFailure)
        assertFalse(cache.finish(b, 0)); assertFalse(cache.idle)
        cache.finish(a, 1); assertEquals(1, releases)
        cache.borrow(b, "v1") { "v1" to Any() }
        assertFalse(cache.finish(a, 2)); assertFalse(cache.idle)
        cache.finish(b, 3); cache.trim(); assertEquals(2, releases)
    }
    @Test fun changedIdentityReleasesBeforeColdAndInvalidModelIsNotCached() {
        val events = mutableListOf<String>()
        val cache = WarmCachePolicy<String, String>({ events += "release:$it" })
        val a = Any(); val b = Any(); val c = Any()
        cache.borrow(a, "inode1") { "inode1" to "first" }; cache.finish(a, 0)
        assertFalse(cache.borrow(b, "inode2") { events += "load2"; "inode2" to "second" }.second)
        assertEquals(listOf("release:first", "load2"), events); cache.finish(b, 1)
        assertTrue(runCatching { cache.borrow(c, null) { error("hash mismatch") } }.isFailure)
        assertFalse(cache.loaded); assertFalse(cache.idle)
        assertTrue(cache.finish(c, 2)); assertTrue(cache.expire(cache.generation, 120_002))
    }
    @Test fun ordinaryCancelRetainsModelButNotLease() {
        var loads = 0
        val cache = WarmCachePolicy<String, Any>({})
        val cancelled = Any(); val next = Any()
        cache.borrow(cancelled, "v1") { loads++; "v1" to Any() }
        cache.finish(cancelled, 0)
        assertTrue(cache.borrow(next, "v1") { error("unexpected reload") }.second)
        assertFalse(cache.finish(cancelled, 1)); cache.finish(next, 2)
        assertEquals(1, loads)
    }
    @Test fun otherThreadCannotDisposeOrBorrow() {
        var releases = 0
        val cache = WarmCachePolicy<String, Any>({ releases++ })
        val owner = Any(); cache.borrow(owner, "v1") { "v1" to Any() }; cache.finish(owner, 0)
        var rejected = false
        val thread = Thread { rejected = runCatching { cache.trim() }.isFailure }
        thread.start(); thread.join()
        assertTrue(rejected); assertEquals(0, releases)
        cache.evict(); assertEquals(1, releases)
    }
    @Test fun cancelBeforeBorrowStillHasBoundedEmptyIdle() {
        val cache = WarmCachePolicy<String, Any>({ error("nothing to release") })
        cache.finishUnborrowed(10)
        assertFalse(cache.loaded)
        assertFalse(cache.expire(cache.generation, 120_009))
        assertTrue(cache.expire(cache.generation, 120_010))
    }
}
