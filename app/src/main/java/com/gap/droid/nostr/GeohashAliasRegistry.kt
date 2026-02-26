package com.gapmesh.droid.nostr

import java.util.concurrent.ConcurrentHashMap

/**
 * GeohashAliasRegistry
 * - Global, thread-safe registry for alias->Nostr pubkey mappings (e.g., nostr_<pub16> -> pubkeyHex)
 * - Allows non-UI components (e.g., MessageRouter) to resolve geohash DM aliases without depending on UI ViewModels
 */
object GeohashAliasRegistry {
    private val map: MutableMap<String, String> = ConcurrentHashMap()
    private const val PREFS_NAME = "geohash_alias_registry"
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: android.content.Context) {
        if (prefs == null) {
            prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
            loadFromPrefs()
        }
    }

    private fun loadFromPrefs() {
        prefs?.let { p ->
            val allEntries = p.all
            for ((key, value) in allEntries) {
                if (key is String && value is String) {
                    map[key] = value
                }
            }
        }
    }

    fun put(alias: String, pubkeyHex: String) {
        map[alias] = pubkeyHex
        prefs?.edit()?.putString(alias, pubkeyHex)?.apply()
    }

    fun get(alias: String): String? = map[alias]

    fun contains(alias: String): Boolean = map.containsKey(alias)

    fun snapshot(): Map<String, String> = HashMap(map)

    fun clear() {
        map.clear()
        prefs?.edit()?.clear()?.apply()
    }
}
