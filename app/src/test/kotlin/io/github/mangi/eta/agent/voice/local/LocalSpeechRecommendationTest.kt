// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Pure metadata regression: recommendation must never invalidate installed payload identities. */
class LocalSpeechRecommendationTest {
    private fun catalog(): String = listOfNotNull(
        System.getProperty("eta.localSpeechCatalog")?.let(::File),
        File("app/src/main/assets/local_speech/catalog.json"),
        File("src/main/assets/local_speech/catalog.json"),
    ).first { it.isFile }.readText()

    @Test fun q8IsSoleRecommendationAndAllEightModelsRemainAvailable() {
        val root = JSONObject(catalog())
        assertEquals("sensevoice_small_q8_0", root.getString("recommendedModelId"))
        assertFalse(root.has("alternativeRecommendedModelId"))
        val all = LocalSpeechRegistry.parse(root.toString())
        assertEquals(8, all.size)
        assertEquals(8, all.map { it.id }.toSet().size)
        assertEquals(1, all.count { it.recommended })
        assertEquals("sensevoice_small_q8_0", all.single { it.recommended }.id)
        assertTrue(all.all { it.available })
        assertEquals(3, all.map { it.engine }.toSet().size)
    }

    @Test fun validSavedSelectionsSurviveIncludingFormerInt8Default() {
        LocalSpeechRegistry.parse(catalog()).forEach { model ->
            assertEquals(model.id, LocalSpeechRegistry.selectionOrDefault(model.id))
        }
        assertEquals("sensevoice_small_int8", LocalSpeechRegistry.selectionOrDefault("sensevoice_small_int8"))
    }

    @Test fun onlyAbsentOrInvalidSavedSelectionFallsBackToQ8() {
        listOf(null, "", "unknown", "sensevoice_small_q8", "../sensevoice_small_q8_0").forEach { saved ->
            assertEquals("sensevoice_small_q8_0", LocalSpeechRegistry.selectionOrDefault(saved))
        }
    }

    @Test fun recommendationOnlyChangeKeepsAllEightInstallationManifestsValid() {
        LocalSpeechRegistry.parse(catalog()).forEach { model ->
            val previous = model.copy(recommended = !model.recommended)
            val installedMarker = LocalSpeechManifest.encode(previous).toString()
            LocalSpeechManifest.validate(installedMarker, model)
            assertFalse(LocalSpeechManifest.encode(model).has("recommended"))
            assertEquals(LocalSpeechManifest.encode(previous).toString(), LocalSpeechManifest.encode(model).toString())
        }
    }

    @Test fun registryRejectsWrongRecommendationMissingIdsAndDuplicateIds() {
        fun reject(change: (JSONObject) -> Unit) {
            val root = JSONObject(catalog()).also(change)
            assertTrue(runCatching { LocalSpeechRegistry.parse(root.toString()) }.isFailure)
        }
        reject { it.put("recommendedModelId", "sensevoice_small_int8") }
        reject { it.getJSONArray("models").remove(0) }
        reject { root ->
            val models = root.getJSONArray("models")
            models.put(1, models.getJSONObject(0))
        }
    }
}
