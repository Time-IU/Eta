package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** custom.5 deliberately removed Kimi Web, not the ordinary Moonshot API connection. */
class KimiWebUiStateTest {
    @Test
    fun builtinKimiRetainsOrdinaryApiWithoutRequiringTheRemovedWebIntegration() {
        val provider = requireNotNull(BuiltinProviders.providerById(BuiltinProviders.KIMI_ID))
        assertTrue(provider is OpenAiCompatibleProviderSetting)
        assertEquals(ProviderSourceTypes.MOONSHOT, provider.sourceType)
        assertEquals("https://api.moonshot.cn/v1", provider.baseUrl)
        assertEquals(ProviderAuthModes.API_KEY, provider.authMode)
        assertFalse(ProviderAuthModes.isOAuth(provider.authMode))
        assertFalse(ProviderAuthModes.isBorrowedVendor(provider.authMode))
        assertNull(ProviderConnectionProfile.forAuthMode(provider.authMode))
        assertTrue(provider.id in BuiltinProviders.SEEDED_PROVIDER_IDS)
    }

    @Test
    fun ordinaryKimiApiModelsAndSettingsStillRoundTrip() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val model = Model(id = "saved-kimi-model", modelId = "fixture-kimi-api", displayName = "Saved Kimi")
        val provider = requireNotNull(BuiltinProviders.providerById(BuiltinProviders.KIMI_ID))
            .withModels(listOf(model))
        val encoded = json.encodeToString(ProviderSetting.serializer(), provider)
        val restored = json.decodeFromString<ProviderSetting>(encoded)
        assertEquals(provider, restored)
        assertEquals(listOf(model), restored.models)
        assertEquals(ProviderAuthModes.API_KEY, restored.authMode)
    }
}
