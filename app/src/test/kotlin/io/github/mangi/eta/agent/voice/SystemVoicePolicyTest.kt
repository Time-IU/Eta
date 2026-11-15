package io.github.mangi.eta.agent.voice

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import io.github.mangi.eta.data.model.SystemTtsConfig
import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SystemVoicePolicyTest {
    private val oplus = "com.oplus.ttsaccessibilityengine"
    private fun voice(name: String = "local", locale: Locale = Locale.SIMPLIFIED_CHINESE,
        network: Boolean = false, features: Set<String> = emptySet()) =
        Voice(name, locale, 400, 300, network, features)

    @Test fun strictIsDefaultAndLegacyJsonRemainsStrict() {
        assertFalse(SystemTtsConfig().useSystemDefault)
        assertFalse(Json.decodeFromString<SystemTtsConfig>("{}").useSystemDefault)
    }
    @Test fun explicitPolicyRoundTrips() {
        val config = SystemTtsConfig(useSystemDefault = true)
        assertEquals(config, Json.decodeFromString<SystemTtsConfig>(Json.encodeToString(config)))
    }
    @Test fun canonicalizesIso3BeforeBcp47CanLoseTheLanguage() {
        for (tag in listOf("zh-CN", "zh_CHN", "zho-CHN", "cmn-CN", "cmn-CHN"))
            assertEquals(tag, Locale.SIMPLIFIED_CHINESE, systemTtsLocale(tag))
        assertEquals(Locale.US, systemTtsLocale("eng-USA"))
    }
    @Test fun oplusChineseMetadataSelectsActualDefaultName() {
        val gentle = voice("中文（温柔女声）", Locale.forLanguageTag("zho-CHN"), features = setOf("0"))
        val male = voice("中文（明朗男声）", gentle.locale, features = setOf("3"))
        assertEquals(gentle, chooseOfflineSystemVoice(setOf(male, gentle), Locale.SIMPLIFIED_CHINESE, gentle.name, oplus))
    }
    @Test fun malformedCountryAsLanguageIsNotTrustedForOtherEngines() {
        val v = voice(locale = Locale.forLanguageTag("zho-CHN"))
        assertNull(chooseOfflineSystemVoice(setOf(v), Locale.SIMPLIFIED_CHINESE, v.name, "another.engine"))
        assertNull(chooseOfflineSystemVoice(setOf(v), Locale.SIMPLIFIED_CHINESE, v.name))
    }
    @Test fun oplusEnglishAliasNeverMatchesChinese() {
        val v = voice(locale = Locale.forLanguageTag("eng-USA"))
        assertNull(chooseOfflineSystemVoice(setOf(v), Locale.SIMPLIFIED_CHINESE, v.name, oplus))
        assertEquals(v, chooseOfflineSystemVoice(setOf(v), Locale.US, v.name, oplus))
    }
    @Test fun standardIso3LocaleMatchesChinese() {
        val v = voice(locale = Locale("zho", "CHN"))
        assertEquals(v, chooseOfflineSystemVoice(setOf(v), Locale.SIMPLIFIED_CHINESE, null))
    }
    @Test fun networkFlagRemainsRejected() {
        assertNull(chooseOfflineSystemVoice(setOf(voice(network = true)), Locale.SIMPLIFIED_CHINESE, null, oplus))
    }
    @Test fun networkFeatureRemainsRejectedEvenWhenNetworkFlagIsFalse() {
        assertNull(chooseOfflineSystemVoice(setOf(voice(features = setOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS))), Locale.SIMPLIFIED_CHINESE, null, oplus))
    }
    @Test fun notInstalledFlagRemainsRejectedForOplusToo() {
        val v = voice(locale = Locale.forLanguageTag("zho-CHN"), features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        assertNull(chooseOfflineSystemVoice(setOf(v), Locale.SIMPLIFIED_CHINESE, v.name, oplus))
    }
    @Test fun exactRegionWinsBeforeDefaultOfAnotherRegion() {
        val cn = voice("cn")
        val tw = voice("tw", Locale.TRADITIONAL_CHINESE)
        assertEquals(cn, chooseOfflineSystemVoice(setOf(tw, cn), Locale.SIMPLIFIED_CHINESE, tw.name))
    }
    @Test fun explicitConflictingScriptsDoNotMatch() {
        val v = voice(locale = Locale.forLanguageTag("zh-Hant-TW"))
        assertNull(chooseOfflineSystemVoice(setOf(v), Locale.forLanguageTag("zh-Hans-CN"), null))
    }
    @Test fun differentLanguageAndBlankLanguageNeverMatch() {
        assertNull(chooseOfflineSystemVoice(setOf(voice(locale = Locale.US)), Locale.SIMPLIFIED_CHINESE, null))
        assertNull(chooseOfflineSystemVoice(setOf(voice()), Locale.ROOT, null))
    }
    @Test fun absentMetadataDoesNotClaimMissingDataOrSuggestForcedInstallation() {
        val error = offlineSystemVoiceFailure(emptySet(), Locale.SIMPLIFIED_CHINESE, oplus)
        assertTrue(error.contains("引擎未提供音色元数据，无法确认离线"))
        assertFalse(error.contains("安装离线语言包"))
    }
    @Test fun explicitNotInstalledMetadataGetsAccurateMessage() {
        val v = voice(features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        assertTrue(offlineSystemVoiceFailure(setOf(v), Locale.SIMPLIFIED_CHINESE, oplus).contains("标记为未安装"))
    }
}
