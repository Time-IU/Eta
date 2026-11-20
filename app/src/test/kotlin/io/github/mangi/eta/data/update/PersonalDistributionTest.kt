package io.github.mangi.eta.data.update

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PersonalDistributionTest {
    private val tag = "v3.4.4-custom.6"
    private val url = "https://github.com/Time-IU/Eta/releases/tag/$tag"
    private fun release(tag: String = this.tag, url: String = this.url, extra: String = "") =
        """{"tag_name":"$tag","html_url":"$url"$extra}"""

    @Test fun personalDestinationsOnly() {
        assertEquals("https://github.com/Time-IU/Eta", PersonalDistribution.REPOSITORY_URL)
        assertEquals("https://github.com/Time-IU/Eta/issues", PersonalDistribution.FEEDBACK_URL)
        assertEquals("https://api.github.com/repos/Time-IU/Eta/releases?per_page=100", PersonalDistribution.RELEASES_API)
    }
    @Test fun exactReleaseUrlAccepted() { assertTrue(PersonalDistribution.isReleaseUrl(tag, url)) }
    @Test fun foreignAndSpoofedUrlsRejected() {
        listOf("https://github.com/Oo0520/Eta-por/releases/tag/$tag",
            "https://github.com/Mangi-11/Eta/releases/tag/$tag", "$url?x=1", "$url#x",
            "http://github.com/Time-IU/Eta/releases/tag/$tag", "$url/",
            "https://github.com.evil.test/Time-IU/Eta/releases/tag/$tag",
            "https://github.com@evil.test/Time-IU/Eta/releases/tag/$tag",
            "https://evil.test@github.com/Time-IU/Eta/releases/tag/$tag",
            "https://github.com:443/Time-IU/Eta/releases/tag/$tag").forEach {
                assertFalse(it, PersonalDistribution.isReleaseUrl(tag, it))
            }
    }
    @Test fun mismatchedTagAndEncodedTraversalRejected() {
        assertFalse(PersonalDistribution.isReleaseUrl(tag, url.replace("custom.6", "custom.5")))
        assertFalse(PersonalDistribution.isReleaseUrl("../latest", "https://github.com/Time-IU/Eta/releases/tag/../latest"))
        assertFalse(PersonalDistribution.isReleaseUrl("v3.4.4-custom.6%2f", "$url%2f"))
    }
    @Test fun customNumbersNotLexical() {
        assertTrue(PersonalVersionPolicy.isCompatibleUpdate("3.4.4-custom.10", "3.4.4-custom.9"))
        assertFalse(PersonalVersionPolicy.isCompatibleUpdate("3.4.4-custom.5", "3.4.4-custom.10"))
    }
    @Test fun sameAndOlderAreNotUpdates() {
        assertFalse(PersonalVersionPolicy.isCompatibleUpdate("v3.4.4-custom.5", "3.4.4-custom.5"))
        assertFalse(PersonalVersionPolicy.isCompatibleUpdate("3.4.3-custom.99", "3.4.4-custom.5"))
    }
    @Test fun upstreamAndPersonalNeverCross() {
        assertFalse(PersonalVersionPolicy.isCompatibleUpdate("4.0.0", "3.4.4-custom.5"))
        assertFalse(PersonalVersionPolicy.isCompatibleUpdate("4.0.0-custom.6", "3.4.4"))
    }
    @Test fun unpublishedIntegrationCannotBeDowngraded() {
        listOf("3.4.4-debug+integration-por.chat-unified", "3.4.4-debug+integration-por.ui-unified",
            "3.4.4-debug+integration-por.settings-fix", "3.4.4-debug").forEach {
            assertFalse(it, PersonalVersionPolicy.isCompatibleUpdate("3.4.4-custom.5", it))
            assertNull(it, AppUpdateChecker.parseReleaseList("[${release()}]", it))
        }
    }
    @Test fun metadataDoesNotInventCustomLineage() {
        assertFalse(PersonalVersionPolicy.isCompatibleUpdate("3.4.4-custom.6", "3.4.4-debug+custom.5-chat-search"))
    }
    @Test fun prereleaseOrdering() {
        assertTrue(PersonalVersionPolicy.isCompatibleUpdate("3.4.4-custom.6", "3.4.4-custom.6-rc.2"))
        assertTrue(PersonalVersionPolicy.isCompatibleUpdate("3.4.4-custom.6-beta.10", "3.4.4-custom.6-beta.9"))
        assertFalse(PersonalVersionPolicy.isCompatibleUpdate("3.4.4-custom.6-beta.1", "3.4.4-custom.6"))
    }
    @Test fun malformedVersionsFailClosed() {
        listOf("banana", "3.4.4-custom.x", "3.4.4-custom.99999999999999999999999", "3.4.4-beta.99").forEach {
            assertFalse(PersonalVersionPolicy.isCompatibleUpdate(it, "3.4.4-custom.5"))
        }
    }
    @Test fun emptyListIsNormal() { assertNull(AppUpdateChecker.parseReleaseList("[]", "3.4.4-custom.5")) }
    @Test fun onlyPrereleasesCanStillUpdate() {
        val selected = AppUpdateChecker.parseReleaseList("[${release(extra = ",\"prerelease\":true")}]", "3.4.4-custom.5")
        assertEquals("3.4.4-custom.6", selected?.version)
        assertEquals(url, selected?.url)
    }
    @Test fun selectionNotDependentOnApiOrder() {
        val ten = "v3.4.4-custom.10"
        val list = "[${release()},${release(ten, url.replace(tag, ten))}]"
        assertEquals("3.4.4-custom.10", AppUpdateChecker.parseReleaseList(list, "3.4.4-custom.5")?.version)
    }
    @Test fun draftsForeignLinksAndStableUpstreamIgnored() {
        val list = "[${release(extra = ",\"draft\":true")},${release(url = url.replace("Time-IU/Eta", "Oo0520/Eta-por"))},${release(tag = "v9.0.0")}]"
        assertNull(AppUpdateChecker.parseReleaseList(list, "3.4.4-custom.5"))
    }
    @Test(expected = IOException::class) fun brokenJsonIsAnErrorNotEmptyList() {
        AppUpdateChecker.parseReleaseList("not json", "3.4.4-custom.5")
    }
    @Test fun conciseVisibleVersionAndTestMarker() {
        assertEquals("3.4.4 定制版 · 测试", PersonalDistribution.versionSummary("3.4.4-debug+integration-por.chat-unified", "zh"))
        assertEquals("3.4.4 定製版", PersonalDistribution.versionSummary("3.4.4-custom.5", "zh", true))
        assertEquals("3.4.4 Custom · Test", PersonalDistribution.versionSummary("3.4.4-debug+abc", "en"))
    }
    @Test fun detailsPreserveRealIdentifiers() {
        val raw = "3.4.4-debug+integration-por.chat-unified"
        val details = PersonalDistribution.buildDetails(raw, 2026111302L, "abc123", "modified")
        assertTrue(details.contains("versionName: $raw"))
        assertTrue(details.contains("versionCode: 2026111302"))
        assertTrue(details.contains("HEAD: abc123"))
        assertTrue(details.contains("Source tree: modified"))
    }
}
