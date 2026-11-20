package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class EnhancementSettingsHistoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearHistory() {
        context.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun historyPreservesDisplayedValuesWithoutWritingBackToTheRemoteSource() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear()
            .putBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true)
            .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, PowerAssistantTarget.GEMINI.persistedValue)
            .commit()
        val history = EnhancementSettingsHistory(context)
        assertFalse(history.hasConnected)
        history.captureConnected(source)
        history.recordCommittedBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false)
        val restored = EnhancementSettingsHistory(context)
        assertTrue(restored.hasConnected)
        assertFalse(restored.checked(Prefs.Keys.ASSISTANT_AUTO_CONFIG, true))
        assertEquals(PowerAssistantTarget.GEMINI, restored.powerTarget())
        assertTrue(source.getBoolean(Prefs.Keys.ASSISTANT_AUTO_CONFIG, false))
    }
    @Test
    fun vendorTakeoverSwitchesRemainRemoteAndSurviveDisconnectedUiHistory() {
        val source = context.getSharedPreferences("vendor_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear()
            .putBoolean(Prefs.Keys.AGENT_CUSTOM_MODEL, false)
            .putBoolean(Prefs.Keys.AGENT_REQUIRE_PREFIX, true)
            .commit()
        val history = EnhancementSettingsHistory(context)
        history.captureConnected(source)
        val restored = EnhancementSettingsHistory(context)
        assertFalse(restored.checked(Prefs.Keys.AGENT_CUSTOM_MODEL, true))
        assertTrue(restored.checked(Prefs.Keys.AGENT_REQUIRE_PREFIX, false))
        assertFalse(Prefs.Keys.AGENT_CUSTOM_MODEL in Prefs.Keys.LOCAL_AGENT_KEYS)
        assertFalse(Prefs.Keys.AGENT_REQUIRE_PREFIX in Prefs.Keys.LOCAL_AGENT_KEYS)
        restored.recordCommittedBoolean(Prefs.Keys.AGENT_CUSTOM_MODEL, true)
        assertFalse(source.getBoolean(Prefs.Keys.AGENT_CUSTOM_MODEL, true))
    }

}
