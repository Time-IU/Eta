package io.github.mangi.eta.data.datastore

import android.app.Application
import android.os.RemoteException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.config.CanvasSettingsErrorBoundary
import io.github.mangi.eta.config.Prefs
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual updateSettings -> remote bridge failure -> UI coroutine path, not only mirror policy.
 * Uses reflection only in the fixture, preserving production service ownership and DataStore API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class CanvasSettingsSaveFailureTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(preferencesOf(
            booleanPreferencesKey("virtual_screen_enabled") to false,
            booleanPreferencesKey("memory_enabled") to false))
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
    private fun brokenService(): XposedService {
        val api = Class.forName("io.github.libxposed.service.IXposedService")
        val binder = Proxy.newProxyInstance(api.classLoader, arrayOf(api)) { _, method, _ ->
            if (method.name == "getApiVersion") 102 else throw RemoteException("fixture Binder unavailable")
        }
        return XposedService::class.java.getDeclaredConstructor(api).apply { isAccessible = true }
            .newInstance(binder)
    }
    private suspend fun fixture(service: XposedService?, block: suspend (MemoryStore) -> Unit) {
        val storeField = SettingsDataStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
        val contextField = SettingsDataStore::class.java.getDeclaredField("canvasContext").apply { isAccessible = true }
        val serviceField = EtaApp::class.java.getDeclaredField("serviceInstance").apply { isAccessible = true }
        val previousStore = storeField.get(null)
        val previousContext = contextField.get(null)
        val previousService = serviceField.get(null)
        val store = MemoryStore()
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        try {
            storeField.set(null, store)
            contextField.set(null, context)
            serviceField.set(null, service)
            block(store)
        } finally {
            storeField.set(null, previousStore)
            contextField.set(null, previousContext)
            serviceField.set(null, previousService)
        }
    }
    @Test fun unrelatedUpdateReturnsLocalSavedAndFailedWithoutUnhandledLaunch() = runBlocking {
        fixture(brokenService()) { store ->
            var presented: Exception? = null
            val job = launch {
                CanvasSettingsErrorBoundary.action({ presented = it }) {
                    SettingsDataStore.updateSettings { it.copy(memoryEnabled = true) }
                }
            }
            job.join()
            assertFalse(job.isCancelled)
            assertNull(presented)
            assertTrue(store.data.value[booleanPreferencesKey("memory_enabled")] == true)
            assertEquals(SettingsDataStore.CanvasGateSync.FAILED, SettingsDataStore.canvasGateSync.value)
        }
    }
    @Test fun rootOnlyUpdateIsUnavailableNotRemoteConfirmed() = runBlocking {
        fixture(null) { store ->
            SettingsDataStore.updateSettings { it.copy(memoryEnabled = true) }
            assertTrue(store.data.value[booleanPreferencesKey("memory_enabled")] == true)
            assertEquals(SettingsDataStore.CanvasGateSync.UNAVAILABLE, SettingsDataStore.canvasGateSync.value)
        }
    }
}
