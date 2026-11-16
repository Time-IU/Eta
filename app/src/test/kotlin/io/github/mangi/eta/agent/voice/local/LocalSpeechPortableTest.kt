// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import io.github.mangi.eta.agent.voice.qwen.WarmCachePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real production parser/accumulator/cache tests. No mocked JNI or claims of device decode coverage. */
class LocalSpeechPortableTest {
    private fun <T> temporary(block: (File) -> T): T {
        val dir = Files.createTempDirectory("eta-local-speech-test-").toFile()
        try { return block(dir) } finally { dir.deleteRecursively() }
    }
    private fun reject(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("unsafe input was accepted", failed)
    }
    private fun header(architecture: String = "sensevoice"): ByteArray {
        val bytes = ByteArrayOutputStream(); val data = DataOutputStream(bytes)
        fun u32(n: Int) = data.writeInt(Integer.reverseBytes(n))
        fun u64(n: Long) = data.writeLong(java.lang.Long.reverseBytes(n))
        fun text(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); data.write(b) }
        u32(0x46554747); u32(3); u64(1); u64(1)
        text("general.architecture"); u32(8); text(architecture)
        return bytes.toByteArray()
    }
    private fun descriptor(onnx: Boolean = false, payload: ByteArray = header()): LocalSpeechModelDescriptor {
        fun file(name: String, size: Int) = LocalSpeechModelFile(name, size.toLong(), "0".repeat(64),
            "https://huggingface.co/test/test/resolve/${"a".repeat(40)}/$name", "a".repeat(40), "model")
        return LocalSpeechModelDescriptor("sensevoice_small_int8", "test", if (onnx) LocalSpeechBackend.SENSEVOICE_ONNX else LocalSpeechBackend.GGUF,
            "sensevoice", "test", "test", "test", "test", true, null,
            if (onnx) listOf(file("model.int8.onnx", 3), file("tokens.txt", 4)) else listOf(file("model.gguf", payload.size)), false)
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        return bytes.toByteArray()
    }
    private fun extract(dir: File, bytes: ByteArray, model: LocalSpeechModelDescriptor = descriptor()) = runBlocking {
        LocalSpeechImport.extract(model, dir, { ByteArrayInputStream(bytes) }) {}
    }
    @Test fun boundedGgufHeaderAccepted() { LocalSpeechValidation.gguf(ByteArrayInputStream(header()), "sensevoice") }
    @Test fun wrongArchitectureRejected() { reject { LocalSpeechValidation.gguf(ByteArrayInputStream(header()), "qwen3_asr") } }
    @Test fun truncatedHeaderRejected() { for (n in 0 until header().size) reject { LocalSpeechValidation.gguf(ByteArrayInputStream(header().copyOf(n)), "sensevoice") } }
    @Test fun unboundedMetadataRejected() {
        val b = header(); for (i in 16..23) b[i] = 127
        reject { LocalSpeechValidation.gguf(ByteArrayInputStream(b), "sensevoice") }
    }
    @Test fun sha256ActuallyHashesBytes() = temporary { dir ->
        val f = File(dir, "hash"); f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LocalSpeechValidation.sha256(f))
        f.appendText("d"); assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LocalSpeechValidation.sha256(f))
    }
    @Test fun hashCancellationPropagates() = temporary { dir ->
        val f = File(dir, "hash").apply { writeText("abc") }
        var cancelled = false
        try { LocalSpeechValidation.sha256(f, { throw CancellationException() }) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun tokenValidation() = temporary { dir ->
        val f = File(dir, "tokens"); f.writeText("<blank> 0\nhello 1\n")
        LocalSpeechValidation.tokens(f)
        f.writeText("<blank> 0\nhello 0\n"); reject { LocalSpeechValidation.tokens(f) }
        f.writeBytes(byteArrayOf(0xc3.toByte(), 0x28)); reject { LocalSpeechValidation.tokens(f) }
        f.writeText("x".repeat(4097) + " 1\n"); reject { LocalSpeechValidation.tokens(f) }
    }
    @Test fun directGgufImport() = temporary { dir ->
        val events = mutableListOf<LocalSpeechProgress>()
        runBlocking { LocalSpeechImport.extract(descriptor(), dir, { ByteArrayInputStream(header()) }, events::add) }
        assertArrayEquals(header(), File(dir, "model.gguf").readBytes())
        assertEquals(header().size.toLong(), events.last().bytes)
    }
    @Test fun extraBytesAndIncompleteDirectFileRejected() = temporary { dir ->
        reject { extract(File(dir, "extra").apply { mkdir() }, header() + byteArrayOf(1)) }
        reject { extract(File(dir, "short").apply { mkdir() }, header().dropLast(1).toByteArray()) }
    }
    @Test fun directGgufCannotPretendToBeOnnxBundle() = temporary { dir -> reject { extract(dir, header(), descriptor(true)) } }
    @Test fun rawSafetensorsRejectedWithActionableReason() = temporary { dir ->
        var reason = ""
        try { extract(dir, "{\"safetensors\":1}".toByteArray()) } catch (e: IllegalArgumentException) { reason = e.message.orEmpty() }
        assertTrue(reason.contains("safetensors"))
    }
    @Test fun rootAndTwoWrapperBundlesAccepted() {
        listOf("", "pack/", "outer/pack/").forEach { prefix -> temporary { dir ->
            extract(dir, zip("${prefix}model.gguf" to header(), "${prefix}README.md" to "info".toByteArray()))
            assertTrue(File(dir, "model.gguf").isFile)
            assertTrue(File(dir, "import-metadata").listFiles().orEmpty().isNotEmpty())
        } }
    }
    @Test fun traversalAbsoluteAndDeepZipPathsRejected() {
        listOf("../model.gguf", "/model.gguf", "x/../../model.gguf", "x\\model.gguf", "a/b/c/model.gguf", "x//model.gguf", "C:model.gguf").forEach { path ->
            temporary { dir -> reject { extract(dir, zip(path to header())) } }
        }
    }
    @Test fun duplicateBasenamesRejected() = temporary { dir -> reject { extract(dir, zip("model.gguf" to header(), "pack/model.gguf" to header())) } }
    @Test fun zipBombAndUnknownPayloadRejected() = temporary { dir ->
        reject { extract(File(dir, "bomb").apply { mkdir() }, zip("model.gguf" to header(), "README.md" to ByteArray(65_537))) }
        reject { extract(File(dir, "unknown").apply { mkdir() }, zip("model.gguf" to header(), "payload.so" to byteArrayOf(1))) }
    }
    @Test fun directoryPayloadRejected() = temporary { dir -> reject { extract(dir, zip("pack/" to byteArrayOf(1), "model.gguf" to header())) } }
    @Test fun missingOnnxTokensRejectedAndCompleteAccepted() {
        temporary { dir -> reject { extract(dir, zip("model.int8.onnx" to byteArrayOf(8, 10, 0)), descriptor(true)) } }
        temporary { dir ->
            extract(dir, zip("x/model.int8.onnx" to byteArrayOf(8, 10, 0), "x/tokens.txt" to "a 0\n".toByteArray()), descriptor(true))
            assertEquals(4L, File(dir, "tokens.txt").length())
        }
    }
    @Test fun inputClosedOnFailure() = temporary { dir ->
        var closed = false
        reject { runBlocking { LocalSpeechImport.extract(descriptor(), dir, { object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun close() { closed = true; super.close() }
        } }) {} } }
        assertTrue(closed)
    }
    private fun voiced(): ByteArray = ByteArray(3200) { index -> if (index % 2 == 0) 0 else 16 }
    @Test fun energyEndpointUsesConsecutiveSampleClock() {
        val a = LocalSpeechPcmAccumulator(200)
        assertFalse(a.append(ByteArray(3200))); assertFalse(a.hasVoice)
        assertFalse(a.append(voiced())); assertTrue(a.hasVoice)
        assertFalse(a.append(ByteArray(3200))); assertFalse(a.append(voiced()))
        assertFalse(a.append(ByteArray(3200))); assertTrue(a.append(ByteArray(3200)))
    }
    @Test fun manualEndpointAndPcmBounds() {
        val a = LocalSpeechPcmAccumulator(0)
        assertFalse(a.append(voiced())); repeat(299) { assertFalse(a.append(ByteArray(3200))) }
        assertEquals(960_000, a.size); reject { a.append(ByteArray(2)) }
        reject { LocalSpeechPcmAccumulator(0).append(ByteArray(3)) }
        reject { LocalSpeechPcmAccumulator(0).append(ByteArray(3202)) }
    }
    @Test fun pcmLittleEndianConversion() {
        val a = LocalSpeechPcmAccumulator(0); a.append(byteArrayOf(0, 128.toByte(), 255.toByte(), 127))
        assertArrayEquals(floatArrayOf(-1f, 32767f / 32768f), a.samples(0, 2), 0f)
    }
    @Test fun warmCacheIdentityInvalidationAndTrim() {
        val released = mutableListOf<String>(); val cache = WarmCachePolicy<String, String>({ released += it })
        val owner = Any()
        assertFalse(cache.borrow(owner, "a") { "a" to "resource-a" }.second); cache.finish(owner, 0)
        assertTrue(cache.borrow(owner, "a") { error("warm hit must not load") }.second); cache.finish(owner, 1)
        assertFalse(cache.borrow(owner, "b") { "b" to "resource-b" }.second)
        assertEquals(listOf("resource-a"), released)
        cache.trim(); assertTrue(cache.loaded); cache.finish(owner, 2); assertFalse(cache.loaded)
    }
    @Test fun staleLeaseAndExpiryCannotEvictActiveModel() {
        val cache = WarmCachePolicy<String, String>({}); val owner = Any()
        cache.borrow(owner, "a") { "a" to "resource" }
        assertFalse(cache.finish(Any(), 0)); assertFalse(cache.expire(cache.generation, Long.MAX_VALUE))
        cache.finish(owner, 10); assertFalse(cache.expire(cache.generation - 1, Long.MAX_VALUE))
        assertFalse(cache.expire(cache.generation, 120_009)); assertTrue(cache.expire(cache.generation, 120_010))
    }
    @Test fun manifestRoundTripUsesNumericSizesNotBoxedTypes() {
        val model = descriptor()
        LocalSpeechManifest.validate(LocalSpeechManifest.encode(model).toString(), model)
    }
    @Test fun forgedInstallationMarkersRejected() {
        val model = descriptor()
        for (change in 0..4) {
            val json = LocalSpeechManifest.encode(model)
            when (change) {
                0 -> json.put("modelId", "other")
                1 -> json.put("downloaded", true)
                2 -> json.getJSONArray("files").getJSONObject(0).put("sha256", "f".repeat(64))
                3 -> json.getJSONArray("files").getJSONObject(0).put("sizeBytes", 1)
                4 -> json.getJSONArray("files").getJSONObject(0).put("pinnedUrl", "https://example.com/model.gguf")
            }
            reject { LocalSpeechManifest.validate(json.toString(), model) }
        }
    }
    private fun catalogText(): String {
        val configured = System.getProperty("eta.localSpeechCatalog")?.let(::File)
        val file = listOfNotNull(configured, File("app/src/main/assets/local_speech/catalog.json"), File("src/main/assets/local_speech/catalog.json")).firstOrNull { it.isFile }
        return requireNotNull(file) { "Pass -Deta.localSpeechCatalog=/path/to/catalog.json" }.readText()
    }
    @Test fun actualEightEntryCatalogParsedWithoutNativeRuntime() {
        val models = LocalSpeechRegistry.parse(catalogText())
        assertEquals(8, models.size); assertEquals(5, models.count { it.engine == LocalSpeechBackend.GGUF })
        val int8 = models.single { it.id == "sensevoice_small_int8" }
        assertFalse(int8.recommended); assertEquals(2, int8.files.size)
        assertEquals(239549735L, int8.sizeBytes)
    }
    @Test fun catalogMutableRevisionsAndUnsafePathsRejected() {
        val original = catalogText()
        for (change in 0..4) {
            val root = JSONObject(original); val model = root.getJSONArray("models").getJSONObject(0)
            val file = model.getJSONArray("files").getJSONObject(0)
            when (change) {
                0 -> file.put("pinnedUrl", file.getString("pinnedUrl").replace(file.getString("revision"), "main"))
                1 -> file.put("name", "../model.gguf")
                2 -> file.put("sha256", "not-a-sha")
                3 -> model.put("architecture", "arbitrary_llm")
                4 -> model.put("licenseAsset", "../../private")
            }
            reject { LocalSpeechRegistry.parse(root.toString()) }
        }
    }
    @Test fun optionalRealCatalogHeadersAndTokens() {
        val root = System.getProperty("eta.localSpeechModels")?.let(::File) ?: return
        val actual = mapOf("Qwen3-ASR-0.6B-Q4_K_M.gguf" to "qwen3_asr", "Qwen3-ASR-0.6B-Q5_K_M.gguf" to "qwen3_asr",
            "SenseVoiceSmall-F16.gguf" to "sensevoice", "SenseVoiceSmall-Q8_0.gguf" to "sensevoice", "SenseVoiceSmall-Q4_K_M.gguf" to "sensevoice")
        actual.forEach { (name, architecture) -> LocalSpeechValidation.gguf(File(root, name), architecture) }
        val tokens = File(root, "tokens.txt"); LocalSpeechValidation.tokens(tokens)
        assertEquals("f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", LocalSpeechValidation.sha256(tokens))
        LocalSpeechValidation.onnx(File(root, "model.int8.onnx"))
    }
}
