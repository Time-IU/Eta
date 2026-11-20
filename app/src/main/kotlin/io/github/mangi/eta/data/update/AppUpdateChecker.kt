package io.github.mangi.eta.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** Exact release tag (without the optional v prefix) and validated personal-repository URL. */
internal data class AppLatestRelease(val version: String, val url: String)

internal object AppUpdateChecker {
    /** Lists include prereleases; /latest excludes them and can legitimately return 404. */
    fun fetchLatest(currentVersion: String): AppLatestRelease? {
        val request = Request.Builder()
            .url(PersonalDistribution.RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Eta-Personal-Update-Checker")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateCheckHttpException(response.code,
                response.code == 429 || response.code == 403 &&
                    (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null))
            return parseReleaseList(response.body.string(), currentVersion)
        }
    }

    internal fun parseReleaseList(body: String, currentVersion: String): AppLatestRelease? {
        val releases = try { JSONArray(body) } catch (error: Exception) {
            throw IOException("Invalid GitHub release list", error)
        }
        var newest: AppLatestRelease? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            val tag = release.optString("tag_name").trim()
            val url = release.optString("html_url").trim()
            if (!PersonalDistribution.isReleaseUrl(tag, url) ||
                !PersonalVersionPolicy.isCompatibleUpdate(tag, currentVersion)) continue
            if (newest == null || isNewer(tag, newest.version)) {
                newest = AppLatestRelease(tag.removePrefix("v"), url)
            }
        }
        return newest
    }

    fun isNewer(latest: String, current: String): Boolean = PersonalVersionPolicy.isNewer(latest, current)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
