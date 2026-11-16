// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Production archive/parser/manifest/cache paths; no JNI and no implicit network in tests. */
class LocalSpeechVoskModelTest {
    private val id = "vosk_small_cn_022"
    private val root = "vosk-model-small-cn-0.22"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z -> entries.forEach { (name, data) ->
            z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
        } }
    }.toByteArray()
    private val payload = listOf("am/final.mdl" to byteArrayOf(1, 2, 3), "conf/mfcc.conf" to byteArrayOf(),
        "conf/model.conf" to "x".toByteArray(), "graph/HCLr.fst" to byteArrayOf(4), "README" to byteArrayOf(5))
    private fun descriptor(bytes: ByteArray, entries: List<Pair<String, ByteArray>> = payload): LocalSpeechModelDescriptor {
        val url = LocalSpeechRegistry.voskUrl(id)
        val files = entries.map { (path, data) -> LocalSpeechModelFile("vosk/$path", data.size.toLong(), hash(data), url, "0.22", "payload") }
        return LocalSpeechModelDescriptor(id, "test", LocalSpeechBackend.VOSK, "vosk", "not specified", "test", "Apache-2.0",
            "local_speech/licenses/Vosk-Apache-2.0.txt", true, null, files, false,
            LocalSpeechModelFile("$root.zip", bytes.size.toLong(), hash(bytes), url, "0.22", "archive"),
            "https://alphacephei.com/vosk/models")
    }
    private fun <T> temporary(block: (File) -> T): T {
        val d = Files.createTempDirectory("eta-vosk-model-test-").toFile()
        try { return block(d) } finally { d.deleteRecursively() }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Unsafe input accepted") } catch (_: IllegalArgumentException) { }
    }
    private fun extract(bytes: ByteArray, model: LocalSpeechModelDescriptor, stage: File) = runBlocking {
        LocalSpeechImport.extract(model, stage, { ByteArrayInputStream(bytes) }) {}
    }
    private fun unpackUnchecked(bytes: ByteArray, stage: File, model: LocalSpeechModelDescriptor = descriptor(bytes)) = runBlocking {
        LocalSpeechVoskArchive.extractVerified(model, stage, ByteArrayInputStream(bytes)) {}
    }
    private fun catalogText(): String = listOfNotNull(System.getProperty("eta.localSpeechCatalog")?.let(::File),
        File("app/src/main/assets/local_speech/catalog.json"), File("src/main/assets/local_speech/catalog.json"))
        .first { it.isFile }.readText()

    @Test fun catalogHasEightEntriesWithQ8DefaultAndMeasuredSixUnchanged() {
        val models = LocalSpeechRegistry.parse(catalogText())
        assertEquals(8, models.size)
        assertEquals(listOf(LocalSpeechRegistry.DEFAULT_ID), models.filter { it.recommended }.map { it.id })
        assertEquals(2, models.count { it.engine == LocalSpeechBackend.VOSK })
        assertEquals(252684608L, models.single { it.recommended }.sizeBytes)
        models.filter { it.engine == LocalSpeechBackend.VOSK }.forEach {
            assertEquals("vosk", it.architecture); assertNotNull(it.archive); assertFalse(it.recommended)
            assertEquals(it.files.sumOf { f -> f.sizeBytes }, it.installedSizeBytes)
            assertTrue(it.installationSpaceBytes > it.sizeBytes)
            assertTrue(it.files.all { f -> LocalSpeechRegistry.safeVoskPath(f.name) })
        }
    }
    @Test fun strictOfficialHostAndExactlyTwoFixedZipNamesOnly() {
        val bytes = zip(payload.map { "$root/${it.first}" to it.second }); val model = descriptor(bytes)
        assertTrue(LocalSpeechDownload.allowedUrl(model, URI(LocalSpeechRegistry.voskUrl(id))))
        listOf("http://alphacephei.com/vosk/models/$root.zip", "https://evil.alphacephei.com/vosk/models/$root.zip",
            "https://alphacephei.com.evil/vosk/models/$root.zip", "https://alphacephei.com:443/vosk/models/$root.zip",
            "https://user@alphacephei.com/vosk/models/$root.zip", "https://alphacephei.com/vosk/models/$root.zip?download=1",
            "https://alphacephei.com/vosk/models/$root.zip#x", "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip",
            "https://alphacephei.com/other/$root.zip", "https://huggingface.co/$root.zip").forEach {
            assertFalse(it, LocalSpeechDownload.allowedUrl(model, URI(it)))
        }
    }
    @Test fun catalogRejectsTraversalWrongHostBackendLicenseSizesAndMissingPayload() {
        repeat(11) { mutation ->
            val json = JSONObject(catalogText()); val a = json.getJSONArray("models")
            val m = (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == id }
            val archive = m.getJSONObject("archive"); val file = m.getJSONArray("files").getJSONObject(0)
            when (mutation) {
                0 -> file.put("name", "vosk/../../secret")
                1 -> archive.put("pinnedUrl", archive.getString("pinnedUrl").replace("alphacephei.com", "evil.alphacephei.com"))
                2 -> archive.put("name", "other.zip")
                3 -> m.put("architecture", "sensevoice")
                4 -> m.put("engine", "SENSEVOICE_ONNX")
                5 -> m.put("licenseSource", "https://example.com")
                6 -> archive.put("sha256", "invented")
                7 -> m.put("installedSizeBytes", 1)
                8 -> archive.put("revision", "main")
                9 -> file.put("name", "vosk/am/not-final.mdl")
                10 -> m.put("licenseAsset", "local_speech/licenses/Qwen-Apache-2.0.txt")
            }
            rejected { LocalSpeechRegistry.parse(json.toString()) }
        }
    }
    @Test fun originalZipImportsAllFilesIncludingEmptyFileAndRemovesOnlyItsTemporaryZip() = temporary { stage ->
        File(stage, "user-data").writeText("keep")
        val bytes = zip(payload.map { "$root/${it.first}" to it.second })
        val events = mutableListOf<LocalSpeechProgress>()
        runBlocking { LocalSpeechImport.extract(descriptor(bytes), stage, { ByteArrayInputStream(bytes) }, events::add) }
        payload.forEach { (name, data) -> assertArrayEquals(data, File(stage, "vosk/$name").readBytes()) }
        assertFalse(File(stage, "$root.zip").exists()); assertEquals("keep", File(stage, "user-data").readText())
        assertTrue(events.any { it.stage == LocalSpeechStage.VERIFYING })
        assertNotEquals(descriptor(bytes).sizeBytes, descriptor(bytes).installedSizeBytes)
    }
    @Test fun repackedZipOrWrongCompressedSizeIsRejectedBeforeExtraction() = temporary { parent ->
        val bytes = zip(payload.map { "$root/${it.first}" to it.second }); val model = descriptor(bytes)
        val changed = bytes.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        listOf(changed, bytes + byteArrayOf(0), bytes.dropLast(1).toByteArray()).forEachIndexed { i, bad ->
            val stage = File(parent, "$i").apply { mkdir() }
            rejected { extract(bad, model, stage) }
            assertFalse(File(stage, "vosk").exists()); assertFalse(File(stage, "$root.zip").exists())
        }
    }
    @Test fun conflictingTemporaryArchiveIsNotDeleted() = temporary { stage ->
        val bytes = zip(payload.map { "$root/${it.first}" to it.second })
        val existing = File(stage, "$root.zip").apply { writeText("keep") }
        rejected { extract(bytes, descriptor(bytes), stage) }
        assertEquals("keep", existing.readText())
    }
    @Test fun duplicateZipPathsAndCatalogPayloadLimitAreRejected() = temporary { stage ->
        val bytes = zip(listOf("$root/am/a" to byteArrayOf(1), "$root/am/b" to byteArrayOf(2)))
        // ZIP writers forbid duplicates; create equal-length duplicate names in both headers.
        val from = "$root/am/b".toByteArray(); val to = "$root/am/a".toByteArray()
        var i = 0
        while (i <= bytes.size - from.size) {
            if (from.indices.all { bytes[i + it] == from[it] }) { to.copyInto(bytes, i); i += from.size } else i++
        }
        val model = descriptor(bytes, listOf("am/a" to byteArrayOf(1), "am/b" to byteArrayOf(2)))
        rejected { unpackUnchecked(bytes, stage, model) }
        val tooMany = descriptor(bytes).copy(files = (0..128).map {
            LocalSpeechModelFile("vosk/a$it", 1, "0".repeat(64), LocalSpeechRegistry.voskUrl(id), "0.22", "payload")
        })
        rejected { unpackUnchecked(bytes, stage, tooMany) }
        val oversized = descriptor(bytes).copy(files = listOf(descriptor(bytes).files.first().copy(sizeBytes = 4_000_000_001)))
        rejected { unpackUnchecked(bytes, stage, oversized) }
    }
    @Test fun payloadDigestMismatchIsRejected() = temporary { stage ->
        val bytes = zip(payload.map { "$root/${it.first}" to it.second }); val model = descriptor(bytes)
        rejected { extract(bytes, model.copy(files = model.files.mapIndexed { i, f -> if (i == 0) f.copy(sha256 = "0".repeat(64)) else f }), stage) }
    }
    @Test fun traversalAbsoluteBackslashDeepUnknownAndWrongWrapperAreRejected() {
        listOf("../escape", "/tmp/escape", "$root/../escape", "$root//am/final.mdl", "$root\\am\\final.mdl",
            "$root/C:escape", "$root/a/b/c/d/e/f/evil", "$root/unknown", "other/am/final.mdl").forEach { bad ->
            temporary { stage -> rejected { unpackUnchecked(zip(listOf(bad to byteArrayOf(1))), stage) } }
        }
    }
    @Test fun missingFilesDirectoryDataOversizedPayloadAndExtraEntriesAreRejected() {
        val cases = listOf(payload.dropLast(1).map { "$root/${it.first}" to it.second },
            listOf("$root/" to byteArrayOf(1)),
            listOf("$root/am/final.mdl" to ByteArray(100_000)),
            payload.map { "$root/${it.first}" to it.second } + ("$root/evil" to byteArrayOf(1)))
        cases.forEach { entries -> temporary { stage -> rejected { unpackUnchecked(zip(entries), stage) } } }
    }
    @Test fun existingOutputAndSymlinkAreNeverOverwritten() = temporary { parent ->
        val bytes = zip(payload.map { "$root/${it.first}" to it.second })
        val stage = File(parent, "stage").apply { mkdir() }; val other = File(parent, "other").apply { mkdir() }
        Files.createSymbolicLink(File(stage, "vosk").toPath(), other.toPath())
        rejected { unpackUnchecked(bytes, stage) }; assertTrue(other.listFiles().isNullOrEmpty())
        File(stage, "vosk").delete(); File(stage, "vosk/am").mkdirs()
        val existing = File(stage, "vosk/am/final.mdl").apply { writeText("keep") }
        rejected { unpackUnchecked(bytes, stage) }; assertEquals("keep", existing.readText())
    }
    @Test fun cancellationPropagatesAndClosesInputAndClearsTemporaryArchive() = temporary { stage ->
        val bytes = zip(payload.map { "$root/${it.first}" to it.second }); var closed = false; var cancelled = false
        try { runBlocking {
            LocalSpeechImport.extract(descriptor(bytes), stage, { object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw CancellationException("stop")
                override fun close() { closed = true; super.close() }
            } }) {}
        } } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertTrue(closed); assertFalse(File(stage, "$root.zip").exists())
    }
    @Test fun manifestBindsArchiveLicenseSourceAndEveryExtractedFile() {
        val bytes = zip(payload.map { "$root/${it.first}" to it.second }); val model = descriptor(bytes)
        LocalSpeechManifest.validate(LocalSpeechManifest.encode(model).toString(), model)
        repeat(3) { mutation ->
            val json = LocalSpeechManifest.encode(model)
            when (mutation) {
                0 -> json.getJSONObject("archive").put("sha256", "0".repeat(64))
                1 -> json.getJSONArray("files").getJSONObject(0).put("sha256", "0".repeat(64))
                2 -> json.put("licenseSource", "https://example.com")
            }
            rejected { LocalSpeechManifest.validate(json.toString(), model) }
        }
    }
    @Test fun fingerprintCacheAvoidsPerRecordingHashButInvalidatesOnAnyChangeAndRestart() {
        val cache = LocalSpeechVerificationCache<List<Long>>()
        val original = listOf(3L, 4L, 5L, 6L, 7L, 8L)
        assertTrue(cache.needsFullVerification(id, original)); cache.record(id, original, original)
        repeat(10) { assertFalse(cache.needsFullVerification(id, original)) }
        original.indices.forEach { i ->
            val changed = original.mapIndexed { index, v -> if (index == i) v + 1 else v }
            assertTrue(cache.needsFullVerification(id, changed)); rejected { cache.record(id, original, changed) }
        }
        assertTrue(LocalSpeechVerificationCache<List<Long>>().needsFullVerification(id, original))
        cache.forget(id); assertTrue(cache.needsFullVerification(id, original))
    }
    @Test fun streamingCopyHashesExactlyTheBytesWrittenWithoutReopeningOutput() = temporary { stage ->
        val data = ByteArray(200_003) { (it % 253).toByte() }; var readBytes = 0L
        val input = object : ByteArrayInputStream(data) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) readBytes += it }
        }
        val output = File(stage, "payload")
        runBlocking { LocalSpeechDownload.copyExact(input, output, data.size.toLong(), hash(data)) {} }
        assertEquals(data.size.toLong(), readBytes); assertArrayEquals(data, output.readBytes())
        rejected { runBlocking { LocalSpeechDownload.copyExact(ByteArrayInputStream(data), File(stage, "wrong"),
            data.size.toLong(), "0".repeat(64)) {} } }
        runBlocking { LocalSpeechDownload.copyExact(ByteArrayInputStream(byteArrayOf()), File(stage, "empty"),
            0, hash(byteArrayOf())) {} }
    }
    @Test fun installationRenameOnlyPermitsRootCtimeChangeNotAnyPayloadOrRootIdentityChange() {
        val rootIdentity = LocalSpeechModelStore.FileIdentity("", 4096, 1, 2, 3, 4, 5, 6)
        val payloadIdentity = rootIdentity.copy(name = "vosk/am/final.mdl", size = 7, inode = 9)
        val before = LocalSpeechModelStore.ModelIdentity(id, "manifest", listOf(rootIdentity, payloadIdentity))
        val renamed = before.copy(files = listOf(rootIdentity.copy(changed = 10, changedNanos = 11), payloadIdentity))
        assertTrue(before.matchesAfterRename(renamed))
        listOf(rootIdentity.copy(inode = 10), rootIdentity.copy(device = 2), rootIdentity.copy(size = 0),
            rootIdentity.copy(modified = 8), rootIdentity.copy(modifiedNanos = 8)).forEach {
            assertFalse(before.matchesAfterRename(before.copy(files = listOf(it, payloadIdentity))))
        }
        assertFalse(before.matchesAfterRename(renamed.copy(files = listOf(renamed.files.first(), payloadIdentity.copy(changedNanos = 88)))))
        assertFalse(before.matchesAfterRename(renamed.copy(manifest = "changed")))
        assertFalse(before.matchesAfterRename(renamed.copy(id = "vosk_cn_022")))
        assertFalse(before.matchesAfterRename(renamed.copy(files = renamed.files.dropLast(1))))
    }
    @Test fun installedProofFlowsAcrossInspectionsSelectionAndExplicitFullCheck() = runBlocking {
        val cache = LocalSpeechVerificationCache<List<Long>>()
        var identity = listOf(1L, 2L, 3L); var scans = 0
        // The installation path already independently hashed the on-disk data.
        scans++; cache.record(id, identity, identity)
        repeat(4) { cache.verify(id, false, { identity }) { scans++ } }
        assertEquals("post-install inspect and selection must not reread payload", 1, scans)
        cache.verify(id, true, { identity }) { scans++ }
        assertEquals("explicit recheck always scans", 2, scans)
        cache.verify(id, false, { identity }) { scans++ }; assertEquals(2, scans)
        identity = listOf(1L, 2L, 4L)
        cache.verify(id, false, { identity }) { scans++ }; assertEquals(3, scans)
    }
    @Test fun failedOrConcurrentFullVerificationNeverSeedsReusableProof() = runBlocking {
        val cache = LocalSpeechVerificationCache<List<Long>>(); var identity = listOf(1L)
        cache.record(id, identity, identity)
        var failed = false
        try { cache.verify(id, true, { identity }) { throw IllegalArgumentException("damaged") } }
        catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed); assertTrue(cache.needsFullVerification(id, identity))
        failed = false
        try { cache.verify(id, false, { identity }) { identity = listOf(2L) } }
        catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed); assertTrue(cache.needsFullVerification(id, identity))
    }
    @Test fun optionalBothOfficialOriginalZipsRunThroughProductionImportAndFullPayloadHashes() {
        val cache = System.getProperty("eta.voskOfficialCache")?.let(::File)
        assumeTrue("Set eta.voskOfficialCache to the external ZIP cache for real model coverage", cache != null)
        LocalSpeechRegistry.parse(catalogText()).filter { it.engine == LocalSpeechBackend.VOSK }.forEach { model ->
            val archive = File(cache!!, requireNotNull(model.archive).name)
            assertTrue("Missing official fixture ${archive.name}", archive.isFile)
            temporary { stage ->
                runBlocking { LocalSpeechImport.extract(model, stage, { archive.inputStream() }) {} }
                assertEquals(model.files.size, File(stage, "vosk").walkTopDown().count { it.isFile })
                model.files.forEach { f ->
                    assertEquals(f.name, f.sizeBytes, File(stage, f.name).length())
                    assertEquals(f.name, f.sha256, LocalSpeechValidation.sha256(File(stage, f.name)))
                }
                assertFalse(File(stage, requireNotNull(model.archive).name).exists())
                println("Official ZIP production import passed: ${archive.name} (${archive.length()} bytes)")
            }
        }
    }
}
