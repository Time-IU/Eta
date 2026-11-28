package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHorizontalScrollLockTest {
    @Test fun tableLockIsReferenceCountedAndReleasesAfterPointerSequence() {
        val lock = ChatHorizontalScrollLock()
        val a = Any(); val b = Any()
        assertFalse(lock.active)
        lock.acquire(a); assertTrue(lock.active)
        lock.acquire(b); lock.release(a); assertTrue(lock.active)
        lock.release(b); assertFalse(lock.active)
    }
}
