package io.github.mangi.eta.data.model

import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import io.github.mangi.eta.data.datastore.migratedLocalAsrJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpeechSettingsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test fun diskMigrationChangesOnlyAsrRetainingUnknownCloudFields() {
        for (key in listOf("SHERPA_NCNN", "QWEN3_LOCAL")) {
            val raw = """{"asr":"$key","qwenAsr":{"region":"SINGAPORE","baseUrl":"https://saved.example"},"futureCloudConfig":{"keep":[1,true,"x"]},"deepseekAsr":{"baseUrl":"https://saved-asr.example"},"deepseekTts":{"voice":"saved-voice","filterMarkdown":false},"systemTts":{"engine":"saved"},"refineTranscript":true}"""
            val before = json.parseToJsonElement(raw).jsonObject
            val migrated = migratedLocalAsrJson(json, raw)!!
            val after = json.parseToJsonElement(migrated).jsonObject
            assertEquals(before.filterKeys { it != "asr" }, after.filterKeys { it != "asr" })
            assertEquals(AsrProvider.LOCAL, json.decodeFromString<SpeechSettings>(migrated).asr)
            assertNull(migratedLocalAsrJson(json, migrated))
        }
        for (key in listOf("QWEN_REALTIME", "QWEN_FLASH", "QWEN_FILE", "DOUBAO", "MIMO", "LOCAL", "DEEPSEEK")) {
            assertNull(migratedLocalAsrJson(json, """{"asr":"$key"}"""))
        }
    }

    @Test fun legacyReadNormalizationIsAsrOnlyAndCloudsAreUnchanged() {
        assertEquals(7, AsrProvider.LOCAL.ordinal)
        assertEquals(5, AsrProvider.SHERPA_NCNN.ordinal)
        assertEquals(6, AsrProvider.QWEN3_LOCAL.ordinal)
        assertEquals(7, AsrProvider.selectable.size)
        assertFalse(AsrProvider.selectable.contains(AsrProvider.SHERPA_NCNN))
        assertFalse(AsrProvider.selectable.contains(AsrProvider.QWEN3_LOCAL))
        for (provider in AsrProvider.entries) {
            val saved = json.decodeFromString<SpeechSettings>(
                """{"asr":"${provider.name}","tts":"MIMO","language":"ja","refineTranscript":true,"qwenAsr":{"baseUrl":"https://saved.example"},"mimoTts":{"voice":"saved-voice"}}""",
            )
            val actual = saved.canonicalized()
            assertEquals(if (provider.ordinal in 5..6) AsrProvider.LOCAL else provider, actual.asr)
            assertEquals(saved.copy(asr = actual.asr), actual)
            assertEquals(actual, actual.canonicalized())
        }
    }

    @Test fun newAndUnknownProvidersDefaultToOfflineAndPreserveOtherSettings() {
        val config = json.decodeFromString<SpeechSettings>("{}")
        assertEquals(AsrProvider.LOCAL, config.asr)
        assertEquals(TtsProvider.NONE, config.tts)
        assertFalse(config.autoSpeak)
        assertTrue(config.recognitionEnabled)
        validateSpeechSettings(config, SpeechCredentials(), synthesis = false)
        val legacy = json.decodeFromString<SpeechSettings>(
            """{"asr":"SYSTEM","tts":"DOUBAO","autoSpeak":true,"refineTranscript":true}""",
        )
        assertEquals(AsrProvider.LOCAL, legacy.asr)
        assertEquals(TtsProvider.DOUBAO, legacy.tts)
        assertTrue(legacy.autoSpeak)
        assertTrue(legacy.refineTranscript)
    }

    @Test fun offlineAppendsOrdinalAndExplicitSavedCloudProvidersAreNotMigrated() {
        assertEquals(listOf("QWEN_REALTIME", "QWEN_FLASH", "QWEN_FILE", "DOUBAO", "MIMO", "SHERPA_NCNN", "QWEN3_LOCAL", "LOCAL", "DEEPSEEK"),
            AsrProvider.entries.map { it.name })
        assertEquals(5, AsrProvider.SHERPA_NCNN.ordinal)
        for (provider in AsrProvider.entries) {
            val saved = SpeechSettings(asr = provider, refineTranscript = true,
                qwenAsr = QwenSpeechConfig(baseUrl = "https://saved.example"))
            assertEquals(saved, json.decodeFromString<SpeechSettings>(json.encodeToString(saved)))
        }
        validateSpeechSettings(SpeechSettings(qwenAsr = QwenSpeechConfig(baseUrl = "invalid-unused")),
            SpeechCredentials(), synthesis = false)
    }

    @Test fun qwenLocalAppendsAndLegacyJsonProviderKeysRemainUnchanged() {
        assertEquals(6, AsrProvider.QWEN3_LOCAL.ordinal)
        for ((ordinal, key) in listOf("QWEN_REALTIME", "QWEN_FLASH", "QWEN_FILE", "DOUBAO", "MIMO", "SHERPA_NCNN").withIndex()) {
            val saved = json.decodeFromString<SpeechSettings>("""{"asr":"$key","language":"ja","refineTranscript":true}""")
            assertEquals(key, saved.asr.name)
            assertEquals(ordinal, saved.asr.ordinal)
            assertEquals("ja", saved.language)
            assertTrue(saved.refineTranscript)
        }
        val local = SpeechSettings(asr = AsrProvider.QWEN3_LOCAL, refineTranscript = true,
            qwenAsr = QwenSpeechConfig(baseUrl = "invalid-unused"))
        validateSpeechSettings(local, SpeechCredentials(), synthesis = false)
        assertTrue(json.encodeToString(local).contains("\"asr\":\"QWEN3_LOCAL\""))
        assertEquals(local, json.decodeFromString<SpeechSettings>(json.encodeToString(local)))
        assertEquals(AsrProvider.LOCAL, json.decodeFromString<SpeechSettings>("{}").asr)
    }

    @Test fun deepseekNamesAndSystemTtsRoundTripWithoutOrdinalPersistence() {
        for (provider in TtsProvider.entries) {
            val settings = SpeechSettings(asr = AsrProvider.DEEPSEEK, tts = provider,
                deepseekAsr = DeepSeekAsrConfig("https://asr.example.test"),
                deepseekTts = DeepSeekTtsConfig("https://tts.example.test", "saved-voice", false),
                systemTts = SystemTtsConfig(engine = "com.heytap.speechassist", language = "zh-CN"))
            val encoded = json.encodeToString(settings)
            assertTrue(encoded.contains("\"asr\":\"DEEPSEEK\""))
            assertTrue(encoded.contains("\"tts\":\"${provider.name}\""))
            assertEquals(settings, json.decodeFromString<SpeechSettings>(encoded))
            assertEquals(settings, settings.canonicalized())
        }
        val credentials = SpeechCredentials(qwenAsr = "qwen", mimoTts = "mimo")
            .withValue(SpeechCredentialField.DEEPSEEK_ASR, "deepseek-asr")
            .withValue(SpeechCredentialField.DEEPSEEK_TTS, "deepseek-tts")
        val restored = json.decodeFromString<SpeechCredentials>(json.encodeToString(credentials))
        assertEquals("deepseek-asr", restored.deepseekAsr)
        assertEquals("deepseek-tts", restored.deepseekTts)
        assertEquals("qwen", restored.qwenAsr)
        assertEquals("mimo", restored.mimoTts)
        assertEquals(6, TtsProvider.SYSTEM.ordinal) // Preserve the custom branch's legacy index.
        assertEquals(1, AsrProvider.selectable.count { it.isLocal })
        assertTrue(AsrProvider.selectable.contains(AsrProvider.DEEPSEEK))
    }

    @Test fun switchingServicesPreservesIndependentConfigurations() {
        val config = SpeechSettings(asr = AsrProvider.QWEN_REALTIME, tts = TtsProvider.DOUBAO,
            qwenAsr = QwenSpeechConfig(SpeechRegion.SINGAPORE),
            doubaoTts = DoubaoSpeechConfig(legacyAuth = true, appId = "test-app"), qwenVoice = "custom-voice")
        val changed = config.copy(asr = AsrProvider.DOUBAO, tts = TtsProvider.NONE)
        assertEquals(changed, json.decodeFromString<SpeechSettings>(json.encodeToString(changed)))
        assertEquals(config.qwenAsr, changed.qwenAsr)
        assertEquals(config.doubaoTts, changed.doubaoTts)
        assertEquals(config.qwenVoice, changed.qwenVoice)
    }

    @Test fun selectedCloudProviderRequiresCredentialsWithoutFallback() {
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(SpeechSettings(asr = AsrProvider.QWEN_FLASH), SpeechCredentials(), false)
        }
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(SpeechSettings(asr = AsrProvider.QWEN_FILE), SpeechCredentials(qwenAsr = "test"), false)
        }
    }

    @Test fun credentialsAreRedactedAndNeverPartOfSettings() {
        val secrets = SpeechCredentials(qwenAsr = "private-key")
        assertFalse(secrets.toString().contains("private-key"))
        val updated = secrets.withValue(SpeechCredentialField.DOUBAO_TTS, "other-key")
        assertEquals("private-key", updated.qwenAsr)
        assertEquals("other-key", updated.doubaoTts)
        assertFalse(json.encodeToString(SpeechSettings()).contains("private-key"))
    }

    @Test fun existingSpeechConfigurationAndCredentialsSurviveNewProviders() {
        val old = json.decodeFromString<SpeechSettings>(
            """{"tts":"DOUBAO","qwenVoice":"custom-qwen","doubaoVoice":"custom-doubao","chatAutoSpeak":true}""",
        )
        assertEquals(TtsProvider.DOUBAO, old.tts)
        assertTrue(old.chatAutoSpeak)
        assertEquals("custom-qwen", old.qwenVoice)
        assertEquals("custom-doubao", old.doubaoVoice)
        assertEquals(GeminiTtsConfig(), old.geminiTts)
        assertEquals(MiniMaxTtsConfig(), old.minimaxTts)
        val updated = old.copy(
            tts = TtsProvider.GEMINI,
            geminiTts = old.geminiTts.copy(model = GeminiTtsModel.FLASH_LITE, style = "自然"),
            minimaxTts = old.minimaxTts.copy(model = MiniMaxTtsModel.HD, speed = 1.25f),
        )
        assertEquals(updated, json.decodeFromString<SpeechSettings>(json.encodeToString(updated)))
        val credentials = json.decodeFromString<SpeechCredentials>(
            """{"qwenAsr":"old-asr","doubaoTts":"old-tts","ossAccessKeySecret":"old-oss"}""",
        ).withValue(SpeechCredentialField.GEMINI_TTS, "new-gemini")
            .withValue(SpeechCredentialField.MINIMAX_TTS, "new-minimax")
        val restored = json.decodeFromString<SpeechCredentials>(json.encodeToString(credentials))
        assertEquals("old-asr", restored.qwenAsr)
        assertEquals("old-tts", restored.doubaoTts)
        assertEquals("old-oss", restored.ossAccessKeySecret)
        assertEquals("new-gemini", restored.geminiTts)
        assertEquals("new-minimax", restored.minimaxTts)
        assertEquals("SpeechCredentials(redacted)", restored.toString())
    }

    @Test fun deepseekSpeechValidationRespectsProviderFallback() {
        validateSpeechSettings(
            SpeechSettings(asr = AsrProvider.DEEPSEEK),
            SpeechCredentials(),
            synthesis = false,
            hasProviderDeepSeekKey = true,
        )
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(
                SpeechSettings(asr = AsrProvider.DEEPSEEK),
                SpeechCredentials(),
                synthesis = false,
                hasProviderDeepSeekKey = false,
            )
        }
        validateSpeechSettings(
            SpeechSettings(tts = TtsProvider.DEEPSEEK),
            SpeechCredentials(),
            synthesis = true,
            hasProviderDeepSeekKey = true,
        )
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(
                SpeechSettings(tts = TtsProvider.DEEPSEEK),
                SpeechCredentials(),
                synthesis = true,
                hasProviderDeepSeekKey = false,
            )
        }
    }

    @Test fun newProvidersValidateOnlyTheirOwnCredentialsAndOptions() {
        for (provider in listOf(TtsProvider.GEMINI, TtsProvider.MINIMAX)) {
            val settings = SpeechSettings(tts = provider)
            assertThrows(SpeechFailure::class.java) {
                validateSpeechSettings(settings, SpeechCredentials(qwenTts = "other-key"), true)
            }
            validateSpeechSettings(settings, SpeechCredentials(geminiTts = "test", minimaxTts = "test"), true)
        }
        val secrets = SpeechCredentials(geminiTts = "test", minimaxTts = "test")
        for (speed in listOf(0f, 2.1f, Float.NaN)) {
            assertThrows(SpeechFailure::class.java) {
                validateSpeechSettings(SpeechSettings(tts = TtsProvider.MINIMAX,
                    minimaxTts = MiniMaxTtsConfig(speed = speed)), secrets, true)
            }
        }
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(SpeechSettings(tts = TtsProvider.GEMINI,
                geminiTts = GeminiTtsConfig(style = "字".repeat(501))), secrets, true)
        }
    }

    @Test fun disablingRecognitionPreservesProviderConfigurationAndPlayback() {
        val original = SpeechSettings(asr = AsrProvider.QWEN_FILE, tts = TtsProvider.GEMINI,
            language = "zh", refineTranscript = true, autoSpeak = true)
        val disabled = original.copy(recognitionEnabled = false)
        val restored = json.decodeFromString<SpeechSettings>(json.encodeToString(disabled))
        assertFalse(restored.recognitionEnabled)
        assertEquals(original, restored.copy(recognitionEnabled = true))
        validateSpeechSettings(restored, SpeechCredentials(), synthesis = false)
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(restored, SpeechCredentials(), synthesis = true)
        }
    }

    @Test fun mimoConfigurationsDefaultForOldDataAndRoundTripIndependently() {
        val old = json.decodeFromString<SpeechSettings>(
            """{"asr":"QWEN_FLASH","language":"ja","tts":"MINIMAX","minimaxTts":{"speed":1.25},"autoSpeak":true}""",
        )
        assertEquals(MimoAsrConfig(), old.mimoAsr)
        assertEquals(MimoTtsConfig(), old.mimoTts)
        val mimo = old.copy(
            asr = AsrProvider.MIMO,
            tts = TtsProvider.MIMO,
            mimoAsr = MimoAsrConfig(
                baseUrl = "https://recognition.example.test/v1",
                language = MimoAsrLanguage.CHINESE,
            ),
            mimoTts = MimoTtsConfig(
                baseUrl = "https://synthesis.example.test/v1",
                voice = "Mia",
                style = "自然、温和",
            ),
        )
        assertEquals(mimo, json.decodeFromString<SpeechSettings>(json.encodeToString(mimo)))
    }

    @Test fun mimoCredentialUpdatesPreserveEachOtherAndExistingProviders() {
        val oldCredentials = json.decodeFromString<SpeechCredentials>(
            """{"qwenAsr":"qwen-key","geminiTts":"gemini-key","ossAccessKeySecret":"oss-key"}""",
        )
        assertEquals("", oldCredentials.mimoAsr)
        assertEquals("", oldCredentials.mimoTts)
        val updated = oldCredentials.withValue(SpeechCredentialField.MIMO_ASR, "asr-key")
            .withValue(SpeechCredentialField.MIMO_TTS, "tts-key")
            .withValue(SpeechCredentialField.DOUBAO_ASR, "doubao-key")
        val restoredCredentials = json.decodeFromString<SpeechCredentials>(json.encodeToString(updated))
        assertEquals("asr-key", restoredCredentials.mimoAsr)
        assertEquals("tts-key", restoredCredentials.mimoTts)
        assertEquals("doubao-key", restoredCredentials.doubaoAsr)
        assertEquals("qwen-key", restoredCredentials.qwenAsr)
        assertEquals("gemini-key", restoredCredentials.geminiTts)
        assertEquals("oss-key", restoredCredentials.ossAccessKeySecret)
        assertEquals(
            "tts-key",
            restoredCredentials.withValue(SpeechCredentialField.MIMO_ASR, "changed-asr-key").mimoTts,
        )
    }

    @Test fun mimoSynthesisRequiresPlaybackCredentialsAndBoundedStyle() {
        val settings = SpeechSettings(tts = TtsProvider.MIMO)
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(settings, SpeechCredentials(mimoAsr = "recognition-only"), true)
        }
        val credentials = SpeechCredentials(mimoTts = "playback-key")
        validateSpeechSettings(settings, credentials, true)
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(settings.copy(mimoTts = settings.mimoTts.copy(style = "字".repeat(501))),
                credentials, true)
        }
    }
}
