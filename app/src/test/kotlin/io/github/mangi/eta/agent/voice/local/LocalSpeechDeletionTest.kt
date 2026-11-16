// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import io.github.mangi.eta.agent.voice.qwen.WarmCachePolicy
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.TimeUnit

class LocalSpeechDeletionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun root(): File = temp.newFolder().resolve("local_speech_models").apply { mkdir() }
    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)
    private fun fails(block: () -> Unit) {
        try { block(); fail("Expected refusal") } catch (_: Exception) { }
    }
    @Test fun allEightCatalogModelsAreRemovableWithoutValidMetadata() {
        val catalog = listOfNotNull(System.getProperty("eta.localSpeechCatalog")?.let(::File),
            File("app/src/main/assets/local_speech/catalog.json"), File("src/main/assets/local_speech/catalog.json"))
            .first { it.isFile }
        val models = JSONObject(catalog.readText()).getJSONArray("models")
        assertEquals(8, models.length())
        for (index in 0 until models.length()) {
            val root = root(); val id = models.getJSONObject(index).getString("id")
            val broken = File(root, id).apply { mkdir() }
            File(broken, "installation.json").writeText("corrupt")
            File(broken, "partial/deep/payload").apply { requireNotNull(parentFile).mkdirs(); writeText("partial") }
            LocalSpeechSafeDelete.delete(root, id)
            assertFalse(exists(broken))
        }
    }
    @Test fun missingInstallationAndMissingRootAreIdempotent() {
        val root = root()
        repeat(2) { LocalSpeechSafeDelete.delete(root, "absent") }
        assertTrue(root.delete())
        LocalSpeechSafeDelete.delete(root, "absent")
    }
    @Test fun corruptInstallationMayBeAnOrdinaryFile() {
        val root = root(); val target = File(root, "broken").apply { writeText("bad") }
        LocalSpeechSafeDelete.delete(root, "broken")
        assertFalse(exists(target))
    }
    @Test fun directoryAndFileSymlinksInsideModelAreUnlinkedWithoutFollowing() {
        val root = root(); val outside = temp.newFolder(); val keep = File(outside, "keep").apply { writeText("unchanged") }
        val target = File(root, "broken").apply { mkdir() }
        Files.createSymbolicLink(File(target, "outside").toPath(), outside.toPath())
        Files.createSymbolicLink(File(target, "outside_file").toPath(), keep.toPath())
        Files.createSymbolicLink(File(target, "loop").toPath(), target.toPath())
        Files.createSymbolicLink(File(target, "dangling").toPath(), File(outside, "missing").toPath())
        LocalSpeechSafeDelete.delete(root, "broken")
        assertFalse(exists(target)); assertEquals("unchanged", keep.readText())
    }
    @Test fun symlinkAsModelRootIncludingDanglingIsRemovable() {
        val root = root(); val outside = temp.newFolder(); val keep = File(outside, "keep").apply { writeText("unchanged") }
        val target = File(root, "broken")
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        LocalSpeechSafeDelete.delete(root, "broken")
        assertEquals("unchanged", keep.readText()); assertFalse(exists(target))
        Files.createSymbolicLink(target.toPath(), File(outside, "missing").toPath())
        LocalSpeechSafeDelete.delete(root, "broken")
        assertFalse(exists(target))
    }
    @Test fun rootSymlinkAndEscapingIdsAreRefused() {
        val parent = temp.newFolder(); val outside = temp.newFolder()
        val keep = File(outside, "broken").apply { mkdir(); resolve("keep").writeText("safe") }
        val link = File(parent, "local_speech_models")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        fails { LocalSpeechSafeDelete.delete(link, "broken") }
        assertTrue(keep.isDirectory)
        val root = root()
        for (id in listOf("../broken", "/broken", ".", "..", ".locks", "bad/id", "", "UPPER")) {
            fails { LocalSpeechSafeDelete.delete(root, id) }
        }
    }
    @Test fun otherModelsLocksEngineAndOriginalDownloadsRemainUnchanged() {
        val root = root()
        val original = File(root.parentFile, "Download/original.gguf").apply { requireNotNull(parentFile).mkdirs(); writeText("original") }
        val zip = File(root.parentFile, "Download/model.zip").apply { writeText("zip") }
        val engine = File(root.parentFile, "engine/native.so").apply { requireNotNull(parentFile).mkdirs(); writeText("engine") }
        val other = File(root, "other/model").apply { requireNotNull(parentFile).mkdirs(); writeText("other") }
        val lock = File(root, ".locks/target.lock").apply { requireNotNull(parentFile).mkdirs(); writeText("lock") }
        File(root, "target").mkdir()
        LocalSpeechSafeDelete.delete(root, "target")
        assertEquals("original", original.readText()); assertEquals("zip", zip.readText())
        assertEquals("engine", engine.readText()); assertEquals("other", other.readText()); assertEquals("lock", lock.readText())
    }
    @Test fun staleQueuedDeletionCannotStartAfterTimeoutOrReimport() {
        val now = 10_000L; val deadline = now + LocalSpeechProtocol.DELETE_MS
        assertTrue(localSpeechDeletionIsLive(deadline, now))
        assertTrue(localSpeechDeletionIsLive(deadline, deadline - 1))
        assertFalse(localSpeechDeletionIsLive(deadline, deadline))
        assertFalse(localSpeechDeletionIsLive(deadline, deadline + 1))
        assertFalse(localSpeechDeletionIsLive(0, now))
        assertFalse(localSpeechDeletionIsLive(deadline + 1, now))
        assertFalse(localSpeechDeletionIsLive(Long.MAX_VALUE, now))
    }
    @Test fun expiredRequestPausedBeforeLockCannotForgetProofOrDeleteNewInstallation() = runBlocking {
        val root = root(); val id = "vosk_small_cn_022"
        val target = File(root, id).apply { mkdir() }
        File(target, "payload").writeText("old")
        val lockFile = File(root, "test.lock")
        val storeLock = LocalSpeechModelLock(); val installerLock = LocalSpeechModelLock()
        val proof = LocalSpeechVerificationCache<String>()
        val outsideChecked = CompletableDeferred<Unit>(); val resumeRequest = CompletableDeferred<Unit>()
        var now = 10_000L; val deadline = now + LocalSpeechProtocol.DELETE_MS
        var checks = 0; var mutationRan = false; var ack = false
        fun checkDeadline() {
            checks++
            if (!localSpeechDeletionIsLive(deadline, now)) throw java.util.concurrent.TimeoutException("expired")
        }
        val deletion = async {
            try {
                checkDeadline() // existing Service check succeeds before the pause
                outsideChecked.complete(Unit)
                resumeRequest.await() // deterministic scheduling gap, no real sleeps
                storeLock.withLock({ lockFile }, wait = false) {
                    localSpeechDeletionAfterLock(::checkDeadline) {
                        mutationRan = true
                        proof.forget(id)
                        LocalSpeechSafeDelete.delete(root, id)
                    }
                }
                ack = true
                false
            } catch (_: java.util.concurrent.TimeoutException) { true }
        }
        outsideChecked.await()
        installerLock.withLock({ lockFile }) {
            File(target, "payload").writeText("new installation")
            proof.record(id, "new proof", "new proof")
            now = deadline + 1
        }
        resumeRequest.complete(Unit)
        assertTrue("Expired request must be rejected inside acquired lock", deletion.await())
        assertEquals(2, checks)
        assertFalse(mutationRan); assertFalse(ack)
        assertEquals("new installation", File(target, "payload").readText())
        assertFalse("Expired deletion must not invalidate the new proof", proof.needsFullVerification(id, "new proof"))
        assertEquals("released", storeLock.withLock({ lockFile }, wait = false) { "released" })
    }
    @Test fun activeOrClosingSessionAndConcurrentDeletionAreRejectedAtAdmission() {
        val gate = LocalSpeechDeletionGate()
        assertFalse(gate.begin(active = true)); assertFalse(gate.inProgress)
        assertTrue(gate.begin(active = false)); assertTrue(gate.inProgress)
        assertFalse(gate.begin(active = false))
        gate.finish(); assertFalse(gate.inProgress)
        assertTrue(gate.begin(active = false))
    }
    @Test fun borrowedCacheRefusesFilesystemMutation() = runBlocking {
        val owner = Any(); val cache = WarmCachePolicy<String, String>({ error("Cannot release active") })
        cache.borrow(owner, "identity") { "identity" to "native" }
        var deleted = false
        try { deleteIdleLocalSpeechModel(cache) { deleted = true }; fail() } catch (_: IllegalStateException) { }
        assertFalse(deleted); assertTrue(cache.loaded); assertFalse(cache.idle)
        cache.finish(owner, 0)
        Unit
    }
    @Test fun idleCacheIsActuallyReleasedBeforeDeleteAndCannotBeReborrowed() = runBlocking {
        val log = mutableListOf<String>(); val cache = WarmCachePolicy<String, String>({ log += "release:$it" })
        val owner = Any()
        cache.borrow(owner, "identity") { "identity" to "native" }; cache.finish(owner, 0)
        val ack = deleteIdleLocalSpeechModel(cache) {
            assertFalse(cache.loaded); log += "delete"; "ACK"
        }
        assertEquals("ACK", ack); assertEquals(listOf("release:native", "delete"), log)
        var loadCalled = false
        try {
            cache.borrow(Any(), "identity") { loadCalled = true; error("No longer installed") }; fail()
        } catch (_: IllegalStateException) { }
        assertTrue(loadCalled); assertFalse(cache.loaded)
    }
    @Test fun nativeReleaseFailureDoesNotDeleteOrProduceAck() = runBlocking {
        val cache = WarmCachePolicy<String, String>({ error("release failed") }); val owner = Any()
        cache.borrow(owner, "identity") { "identity" to "native" }; cache.finish(owner, 0)
        var removed = false
        try { deleteIdleLocalSpeechModel(cache) { removed = true }; fail() } catch (_: IllegalStateException) { }
        assertFalse(removed); assertFalse(cache.loaded)
    }
    @Test fun partialFilesystemFailurePropagatesAndCacheStaysInvalidated() = runBlocking {
        val cache = WarmCachePolicy<String, String>({ }); val owner = Any()
        cache.borrow(owner, "identity") { "identity" to "native" }; cache.finish(owner, 0)
        val root = root(); val dir = File(root, "target").apply { mkdir() }
        val first = File(dir, "first").apply { writeText("a") }; File(dir, "remaining").writeText("b")
        try {
            deleteIdleLocalSpeechModel(cache) { first.delete(); throw java.io.IOException("partial removal") }; fail()
        } catch (e: java.io.IOException) { assertEquals("partial removal", e.message) }
        assertFalse(cache.loaded); assertFalse(first.exists()); assertTrue(File(dir, "remaining").exists())
    }
    @Test fun cancellationDuringMutationIsNotReturnedAsSuccess() = runBlocking {
        val cache = WarmCachePolicy<String, String>({ }); val owner = Any()
        cache.borrow(owner, "identity") { "identity" to "native" }; cache.finish(owner, 0)
        var ack = false
        try {
            deleteIdleLocalSpeechModel(cache) { throw CancellationException("partial deletion") }
            ack = true
        } catch (e: CancellationException) { assertEquals("partial deletion", e.message) }
        assertFalse(ack); assertFalse(cache.loaded)
    }
    @Test fun operationFailureAndCancellationReleaseMutexAndFileLease() = runBlocking {
        val lock = LocalSpeechModelLock(); val file = File(temp.newFolder(), "target.lock")
        try { lock.withLock({ file }) { throw java.io.IOException("partial") }; fail() }
        catch (_: java.io.IOException) { }
        try { lock.withLock({ file }) { throw CancellationException("cancelled") }; fail() }
        catch (_: CancellationException) { }
        assertEquals("retry", lock.withLock({ file }, wait = false) { "retry" })
    }
    @Test fun concurrentSymlinkReplacementsNeverDeleteExternalPayloads() {
        val root = root(); val outside = temp.newFolder()
        val keep = File(outside, "keep").apply { writeText("outside") }
        repeat(40) { index ->
            val id = "model_$index"; val model = File(root, id).apply { mkdir() }
            val link = File(model, "changing").toPath()
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val ready = java.util.concurrent.CountDownLatch(1)
            val thread = Thread {
                while (running.get()) {
                    try {
                        Files.deleteIfExists(link)
                        Files.createSymbolicLink(link, outside.toPath())
                        ready.countDown()
                    } catch (_: java.io.IOException) { ready.countDown() }
                }
            }.apply { start() }
            try {
                assertTrue(ready.await(2, TimeUnit.SECONDS))
                try { LocalSpeechSafeDelete.delete(root, id) } catch (_: java.io.IOException) { /* safe partial/refusal */ }
            } finally { running.set(false); thread.join(2_000) }
            assertFalse(thread.isAlive)
            assertEquals("outside", keep.readText())
            LocalSpeechSafeDelete.delete(root, id)
        }
    }
    @Test fun deletionRefusesBusyProcessLocalInstallOrLoad() = runBlocking {
        val lock = LocalSpeechModelLock(); val file = File(temp.newFolder(), "target.lock")
        val held = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val install = launch { lock.withLock({ file }) { held.complete(Unit); finish.await() } }
        held.await()
        try { lock.withLock({ file }, wait = false) { fail("Must not delete") }; fail() }
        catch (_: LocalSpeechModelBusyException) { }
        finish.complete(Unit); install.join()
        assertEquals("deleted", lock.withLock({ file }, wait = false) { "deleted" })
    }
    @Test fun separateStoreInstancesUseFileLockAndCancelledWaitNeverDeletes() = runBlocking {
        val one = LocalSpeechModelLock(); val two = LocalSpeechModelLock()
        val file = File(temp.newFolder(), "target.lock")
        one.withLock({ file }) {
            try { two.withLock({ file }, wait = false) { fail("Overlapping lease") }; fail() }
            catch (_: LocalSpeechModelBusyException) { }
            val waiter = launch { two.withLock({ file }) { fail("Cancelled waiter must never mutate") } }
            yield(); waiter.cancelAndJoin()
        }
        assertEquals("next", two.withLock({ file }, wait = false) { "next" })
    }
    @Test fun realSeparateProcessFileLeaseRejectsDeletionUntilReleased() = runBlocking {
        val file = File(temp.newFolder(), "target.lock")
        // Gradle loads tests through its own classloader, not necessarily java.class.path.
        fun location(type: Class<*>): String = File(requireNotNull(requireNotNull(type.protectionDomain).codeSource).location.toURI()).path
        val childClasspath = listOf(System.getProperty("java.class.path"),
            location(LocalSpeechDeleteLockChild::class.java), location(Unit::class.java)).joinToString(File.pathSeparator)
        val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", childClasspath, LocalSpeechDeleteLockChild::class.java.name, file.path)
            .redirectErrorStream(true).start()
        try {
            assertEquals("locked", withContext(Dispatchers.IO) { child.inputStream.bufferedReader().readLine() })
            val lock = LocalSpeechModelLock()
            try { lock.withLock({ file }, wait = false) { fail("Cross-process lease") }; fail() }
            catch (_: LocalSpeechModelBusyException) { }
            child.outputStream.write(10); child.outputStream.flush()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, child.exitValue())
            assertEquals("deleted", lock.withLock({ file }, wait = false) { "deleted" })
        } finally { child.destroyForcibly() }
    }
    @Test fun verificationProofIsForgottenForDeletedOrPartialModels() = runBlocking {
        val cache = LocalSpeechVerificationCache<String>(); val id = "vosk_small_cn_022"
        cache.record(id, "same", "same"); assertFalse(cache.needsFullVerification(id, "same"))
        cache.forget(id); assertTrue(cache.needsFullVerification(id, "same"))
        var hashes = 0
        cache.verify(id, false, { "same" }) { hashes++ }
        assertEquals(1, hashes)
    }
}

object LocalSpeechDeleteLockChild {
    @JvmStatic fun main(args: Array<String>) {
        RandomAccessFile(args.single(), "rw").use { file ->
            file.channel.lock().use { println("locked"); System.out.flush(); System.`in`.read() }
        }
    }
}
