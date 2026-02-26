package com.gapmesh.droid.nostr

import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fetches and caches NIP-11 relay information documents.
 *
 * Before connecting to a relay, call [fetch] to discover its capabilities.
 * Results are cached in memory for [CACHE_TTL_MS] (30 minutes) to avoid
 * excessive HTTP requests.
 *
 * Reference: Noghteha's `RelayInfoDocument.INSTANCE.fetch()` with identical
 * caching and timeout behavior.
 */
object RelayInfoFetcher {

    private const val TAG = "RelayInfoFetcher"
    private const val CACHE_TTL_MS = 30L * 60 * 1000  // 30 minutes
    private const val REQUEST_TIMEOUT_SECONDS = 10L

    private data class CachedInfo(
        val info: RelayInfoDocument?,
        val fetchedAtMs: Long
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - fetchedAtMs >= CACHE_TTL_MS
    }

    private val cache = ConcurrentHashMap<String, CachedInfo>()

    private val gson = Gson()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(REQUEST_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // MARK: - Public API

    /**
     * Fetch the NIP-11 relay info document for the given relay URL.
     * Returns a cached result if available and not expired, otherwise makes
     * an HTTP GET with `Accept: application/nostr+json`.
     *
     * Returns `null` if the relay does not respond or returns invalid JSON.
     * Never throws — all exceptions are caught and logged.
     */
    fun fetch(relayUrl: String): RelayInfoDocument? {
        val normalizedUrl = normalizeRelayUrl(relayUrl)

        // Return cache hit if still valid
        cache[normalizedUrl]?.let { cached ->
            if (!cached.isExpired()) return cached.info
        }

        return try {
            val httpUrl = convertToHttpUrl(normalizedUrl)
            if (httpUrl == null) {
                Log.w(TAG, "Cannot convert relay URL to HTTP: $normalizedUrl")
                cacheResult(normalizedUrl, null)
                return null
            }

            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/nostr+json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.v(TAG, "NIP-11 fetch failed for $normalizedUrl: HTTP ${response.code}")
                    cacheResult(normalizedUrl, null)
                    return null
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Log.v(TAG, "NIP-11 empty response from $normalizedUrl")
                    cacheResult(normalizedUrl, null)
                    return null
                }

                val doc = gson.fromJson(body, RelayInfoDocument::class.java)
                cacheResult(normalizedUrl, doc)
                Log.i(TAG, "NIP-11 fetched: ${doc.name ?: normalizedUrl} " +
                    "(NIPs: ${doc.supportedNips?.joinToString(",") ?: "unknown"}, " +
                    "auth=${doc.limitation?.authRequired}, pay=${doc.requiresPayment()})")
                doc
            }
        } catch (e: Exception) {
            Log.v(TAG, "NIP-11 fetch error for $normalizedUrl: ${e.message}")
            cacheResult(normalizedUrl, null)
            null
        }
    }

    /**
     * Returns the cached NIP-11 info for a relay without making a network request.
     * Returns `null` if nothing is cached or if the cache entry has expired.
     */
    fun getCached(relayUrl: String): RelayInfoDocument? {
        val key = normalizeRelayUrl(relayUrl)
        return cache[key]?.takeIf { !it.isExpired() }?.info
    }

    /**
     * Returns true if we have a cached (non-expired) NIP-11 document for this relay.
     */
    fun isCached(relayUrl: String): Boolean = getCached(relayUrl) != null

    /**
     * Returns a snapshot of all cached (non-expired) relay info entries.
     */
    fun getAllCached(): Map<String, RelayInfoDocument?> {
        val now = System.currentTimeMillis()
        return cache.entries
            .filter { now - it.value.fetchedAtMs < CACHE_TTL_MS }
            .associate { it.key to it.value.info }
    }

    /**
     * Fetch NIP-11 info for multiple relays concurrently.
     * Returns a map of relay URL → info document (null for failures).
     */
    suspend fun fetchMultiple(relayUrls: List<String>): Map<String, RelayInfoDocument?> {
        val results = ConcurrentHashMap<String, RelayInfoDocument?>()
        val jobs = relayUrls.map { url ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                results[url] = fetch(url)
            }
        }
        jobs.forEach { it.await() }
        return results
    }

    /**
     * Find all cached relays that advertise support for a specific NIP.
     */
    fun findRelaysSupportingNip(nip: Int): List<String> {
        return cache.entries
            .filter { !it.value.isExpired() }
            .filter { it.value.info?.supportsNip(nip) == true }
            .map { it.key }
    }

    /**
     * Clear all cached entries, or just the entry for a specific relay.
     */
    fun clearCache(relayUrl: String? = null) {
        if (relayUrl != null) {
            cache.remove(normalizeRelayUrl(relayUrl))
        } else {
            cache.clear()
        }
    }

    /**
     * Returns a human-readable summary of all cached relay capabilities.
     */
    fun getCapabilitiesSummary(): String = buildString {
        val entries = getAllCached()
        if (entries.isEmpty()) {
            append("No relay info cached.\n")
            return@buildString
        }
        for ((url, info) in entries) {
            append("=== $url ===\n")
            if (info == null) {
                append("  (no NIP-11 data)\n")
            } else {
                append(info.capabilitiesSummary().prependIndent("  "))
            }
            append("\n")
        }
    }

    // MARK: - Internals

    private fun cacheResult(url: String, info: RelayInfoDocument?) {
        cache[url] = CachedInfo(info, System.currentTimeMillis())
    }

    private fun convertToHttpUrl(wsUrl: String): String? {
        return when {
            wsUrl.startsWith("wss://") -> wsUrl.replaceFirst("wss://", "https://")
            wsUrl.startsWith("ws://") -> wsUrl.replaceFirst("ws://", "http://")
            wsUrl.startsWith("https://") || wsUrl.startsWith("http://") -> wsUrl
            else -> "https://$wsUrl"
        }
    }

    private fun normalizeRelayUrl(url: String): String {
        var u = url.trim().trimEnd('/')
        if (!u.contains("://")) {
            u = "wss://$u"
        }
        return u
    }
}
