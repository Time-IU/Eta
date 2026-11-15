package io.github.mangi.eta.agent.voice

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.SystemTtsConfig
import io.github.mangi.eta.data.model.TtsProvider
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemTtsPolicyTest {
    private fun voice(name: String, locale: Locale, network: Boolean = false, installed: Boolean = true) =
        Voice(name, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, network,
            if (installed) emptySet() else setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))

    @Test fun providerIsAppendOnlyAndDefaultUnchanged() {
        assertEquals(listOf("NONE", "QWEN", "DOUBAO", "GEMINI", "MINIMAX", "MIMO", "SYSTEM", "DEEPSEEK"), TtsProvider.entries.map { it.name })
        assertEquals(TtsProvider.NONE, SpeechSettings().tts)
    }

    @Test fun legacySettingsDecodeAndSystemConfigRoundTrips() {
        assertEquals(SystemTtsConfig(), Json.decodeFromString<SpeechSettings>("{}").systemTts)
        val settings = SpeechSettings(tts = TtsProvider.SYSTEM, systemTts = SystemTtsConfig(rate = 1.25f, pitch = 0.75f))
        assertEquals(settings, Json.decodeFromString<SpeechSettings>(Json.encodeToString(settings)))
    }

    @Test fun offlineVoicesMustBeInstalledAndLanguageMatched() {
        val rejected = setOf(voice("cloud", Locale.SIMPLIFIED_CHINESE, network = true),
            voice("missing", Locale.SIMPLIFIED_CHINESE, installed = false), voice("en", Locale.US))
        assertNull(chooseOfflineSystemVoice(rejected, Locale.SIMPLIFIED_CHINESE, null))
        val accepted = voice("zh", Locale.SIMPLIFIED_CHINESE)
        assertEquals(accepted, chooseOfflineSystemVoice(rejected + accepted, Locale.SIMPLIFIED_CHINESE, "cloud"))
    }

    @Test fun exactLocalePreferredBeforeDefaultVoice() {
        val exact = voice("cn", Locale.SIMPLIFIED_CHINESE)
        assertEquals(exact, chooseOfflineSystemVoice(setOf(voice("tw", Locale.TRADITIONAL_CHINESE), exact),
            Locale.SIMPLIFIED_CHINESE, "tw"))
    }

    @Test fun systemNeedsNoCloudKeysButValidatesRateAndPitch() {
        validateSpeechSettings(SpeechSettings(tts = TtsProvider.SYSTEM), SpeechCredentials(), synthesis = true)
        for (config in listOf(SystemTtsConfig(rate = Float.NaN), SystemTtsConfig(pitch = 3f), SystemTtsConfig(rate = 0.1f))) {
            try {
                validateSpeechSettings(SpeechSettings(tts = TtsProvider.SYSTEM, systemTts = config), SpeechCredentials(), true)
                fail("Invalid system voice settings must be rejected")
            } catch (failure: SpeechFailure) { assertEquals(SpeechErrorCode.CONFIGURATION, failure.code) }
        }
    }

    @Test fun systemCannotFallThroughCloudSynthesis() = runBlocking {
        assertFalse(supportsSpeechSession(TtsProvider.SYSTEM))
        val settings = SpeechSettings(tts = TtsProvider.SYSTEM)
        try {
            synthesizeSpeech(settings, SpeechCredentials(), "本地", { fail("No cloud audio") })
            fail("Cloud synthesis must reject SYSTEM")
        } catch (failure: SpeechFailure) { assertEquals(SpeechErrorCode.CONFIGURATION, failure.code) }
        try {
            streamSpeech(settings, SpeechCredentials(), listOf("本地"), { fail("No cloud stream") })
            fail("Cloud streaming must reject SYSTEM")
        } catch (failure: SpeechFailure) { assertEquals(SpeechErrorCode.CONFIGURATION, failure.code) }
    }
}
