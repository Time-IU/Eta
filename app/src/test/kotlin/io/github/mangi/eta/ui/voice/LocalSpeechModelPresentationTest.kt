// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.ui.voice

import io.github.mangi.eta.agent.voice.local.LocalSpeechInstallation
import io.github.mangi.eta.agent.voice.local.LocalSpeechRegistry
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real checked-in assets and production helpers; no Robolectric, JNI, downloads or device data. */
class LocalSpeechModelPresentationTest {
    private fun mainSource(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .mapNotNull { root -> listOf(File(root, "src/main"), File(root, "app/src/main")).firstOrNull {
            File(it, "assets/local_speech/catalog.json").isFile
        } }.firstOrNull() ?: error("Run with cwd at project root or app module; production asset fixtures are required")
    private fun asset(name: String) = File(mainSource(), "assets/local_speech/$name").readText()
    private fun source(name: String) = File(mainSource(), "kotlin/io/github/mangi/eta/ui/voice/$name").readText()
    private fun models() = LocalSpeechRegistry.parse(asset("catalog.json"))

    @Test fun allSixActualCatalogPayloadSizesAndFriendlyRounding() {
        val expected = linkedMapOf(
            "qwen3_asr_06b_q4_k_m" to (589560480L to "590MB"),
            "qwen3_asr_06b_q5_k_m" to (645356192L to "645MB"),
            "sensevoice_small_f16" to (470412128L to "470MB"),
            "sensevoice_small_q8_0" to (252684608L to "253MB"),
            "sensevoice_small_q4_k_m" to (145738304L to "146MB"),
            "sensevoice_small_int8" to (239549735L to "240MB"))
        assertEquals(expected.keys, models().map { it.id }.toSet())
        models().forEach { m ->
            assertEquals(m.id, expected.getValue(m.id).first, m.sizeBytes)
            assertEquals(m.id, expected.getValue(m.id).second, friendlyModelSize(m.sizeBytes))
            assertEquals(m.id, m.files.sumOf { it.sizeBytes }, m.sizeBytes)
        }
    }
    @Test fun onnxPayloadIncludesTokensExactlyOnce() {
        val m = models().single { it.id == "sensevoice_small_int8" }
        assertEquals(setOf("model.int8.onnx", "tokens.txt"), m.files.map { it.name }.toSet())
        assertEquals(2, m.files.size)
        assertTrue(m.files.single { it.name == "tokens.txt" }.sizeBytes > 0L)
        assertTrue(m.sizeBytes > m.files.single { it.name == "model.int8.onnx" }.sizeBytes)
    }
    @Test fun friendlyMbIsDecimalNotMibAndIsLocaleStable() {
        val previous = Locale.getDefault()
        try { Locale.setDefault(Locale.GERMANY); assertEquals("146MB", friendlyModelSize(145738304L)) }
        finally { Locale.setDefault(previous) }
        assertEquals("1MB", friendlyModelSize(1_000_000L))
        assertEquals("2MB", friendlyModelSize(1_500_000L))
    }
    @Test fun actualMeasurementsHaveSixMatchingModelsAndTimingUnits() {
        val parsed = parseLocalSpeechMeasurements(asset("measurements.json"))
        assertEquals(models().map { it.id }.toSet(), parsed.models.keys)
        assertEquals("public-0.wav", parsed.fixture)
        assertEquals(10.053125, parsed.audioSeconds, 0.0000001)
        assertFalse(parsed.diskCacheCold); assertFalse(parsed.serviceIpcTested)
        val q4 = parsed.models.getValue("sensevoice_small_q4_k_m")
        assertEquals(353908L, q4.peakRssKiB)
        assertEquals(89L, q4.factoryMs); assertEquals(4117L, q4.decodeMs)
    }
    @Test fun ramIsKibToMib346NotDecimal354OrFileSize139() {
        val q4 = parseLocalSpeechMeasurements(asset("measurements.json")).models.getValue("sensevoice_small_q4_k_m")
        assertEquals("约346MiB", String.format(Locale.ROOT, "约%.0fMiB", q4.peakRssKiB / 1024.0))
        val ui = source("LocalSpeechModelInfo.kt")
        assertTrue(ui.contains("it.peakRssKiB / 1024.0"))
        assertTrue(ui.contains("RAM 参考（含测试）")); assertTrue(ui.contains("下载大小"))
        assertFalse(ui.contains("139MB"))
    }
    @Test fun installationStatesDoNotConflateSelectionAndInstallation() {
        assertEquals("待检测", localInstallationLabel(null))
        assertEquals("就绪", localInstallationLabel(LocalSpeechInstallation("m", true, "ok")))
        assertEquals("未安装", localInstallationLabel(LocalSpeechInstallation("m", false, "尚未安装，请导入")))
        assertEquals("损坏 / 缺失", localInstallationLabel(LocalSpeechInstallation("m", false, "SHA mismatch")))
    }
    @Test fun malformedAndWrongSchemaMeasurementsUseNullableFallbackContract() {
        val real = asset("measurements.json")
        listOf("", "not JSON", "{}", JSONObject(real).put("schemaVersion", 2).toString(),
            JSONObject(real).apply { remove("models") }.toString()).forEach { bad ->
            assertNull(runCatching { parseLocalSpeechMeasurements(bad) }.getOrNull())
        }
        // This source assertion ties the tested catch boundary to the actual IO wrapper, without mocked Context.
        val wrapper = source("LocalSpeechModelInfo.kt").substringAfter("internal fun readLocalSpeechMeasurements")
            .substringBefore("internal fun parseLocalSpeechMeasurements")
        assertTrue(wrapper.contains("runCatching")); assertTrue(wrapper.contains("}.getOrNull()"))
    }
    @Test fun incompleteMeasurementEntryFailsGracefullyWithoutFakeZeros() {
        val root = JSONObject(asset("measurements.json"))
        root.getJSONArray("models").getJSONObject(0).remove("peakRssKiB")
        assertNull(runCatching { parseLocalSpeechMeasurements(root.toString()) }.getOrNull())
        assertTrue(source("LocalSpeechModelInfo.kt").contains("暂无可读取的实测数据，不影响模型安装与使用。"))
    }
    @Test fun missingSelectedMeasurementDoesNotInventAValue() {
        val root = JSONObject(asset("measurements.json")); root.getJSONArray("models").remove(4)
        assertNull(parseLocalSpeechMeasurements(root.toString()).models["sensevoice_small_q4_k_m"])
        assertTrue(source("LocalSpeechModelInfo.kt").contains("?: \"暂无数据\""))
    }
    @Test fun preciseBytesLiveInFoldNotCompactOverview() {
        val ui = source("LocalSpeechModelInfo.kt")
        val compact = ui.substringAfter("internal fun LocalSpeechMetrics(").substringBefore("internal fun LocalSpeechStatus(")
        val detail = ui.substringAfter("internal fun LocalSpeechModelInfo(")
        assertTrue(compact.contains("friendlyModelSize(model.sizeBytes)"))
        assertFalse(compact.contains(" bytes"))
        assertEquals(1, Regex("精确文件合计").findAll(ui).count())
        assertTrue(detail.indexOf("精确文件合计") > detail.indexOf("更多参数与测试数据"))
        assertTrue(detail.indexOf("精确文件合计") < detail.indexOf("导入说明"))
        assertFalse(source("LocalSpeechSettings.kt").contains(" bytes"))
    }
    @Test fun foldsDefaultClosedAndScopeByModelWithoutSelectingOrDownloading() {
        val ui = source("LocalSpeechModelInfo.kt")
        assertTrue(ui.contains("rememberSaveable(modelId, title) { mutableStateOf(false) }"))
        assertEquals(3, Regex("LocalSpeechFold\\(model.id,").findAll(ui).count())
    }
    @Test fun fixtureAndUiDoNotClaimColdCacheServiceIpcOrAccuracyRanking() {
        val measured = JSONObject(asset("measurements.json"))
        assertFalse(measured.getBoolean("accuracyMeasured"))
        val ui = source("LocalSpeechModelInfo.kt")
        assertTrue(ui.contains("测试不代表准确率排名、常驻内存或内存需求保证"))
        assertTrue(ui.contains("缓存一个模型，空闲 120 秒释放"))
    }
}
