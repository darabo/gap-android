package com.gapmesh.droid.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages Slipstream (QUIC-over-DNS censorship bypass) preferences.
 *
 * Persists the user's Slipstream toggle state, tunnel domain, and DNS resolver
 * using the same SecurePrefs backing store as TorPreferenceManager.
 *
 * When Slipstream is enabled, Tor routes through a local Slipstream SOCKS5
 * proxy so all traffic flows: App → Tor → Slipstream → DNS tunnel → Internet.
 */
object SlipstreamPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_ENABLED = "slipstream_enabled"
    private const val KEY_DOMAIN = "slipstream_domain"
    private const val KEY_RESOLVER = "slipstream_resolver"

    // Default tunnel domain — must match your slipstream-server domain
    private const val DEFAULT_DOMAIN = "t.gapmesh.com"
    // Default upstream DNS resolver (Cloudflare DoH/UDP)
    private const val DEFAULT_RESOLVER = "1.1.1.1"

    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow

    private val _domainFlow = MutableStateFlow(DEFAULT_DOMAIN)
    val domainFlow: StateFlow<String> = _domainFlow

    private val _resolverFlow = MutableStateFlow(DEFAULT_RESOLVER)
    val resolverFlow: StateFlow<String> = _resolverFlow

    fun init(context: Context) {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        _enabledFlow.value = prefs.getBoolean(KEY_ENABLED, false)
        _domainFlow.value = prefs.getString(KEY_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN
        _resolverFlow.value = prefs.getString(KEY_RESOLVER, DEFAULT_RESOLVER) ?: DEFAULT_RESOLVER
    }

    fun isEnabled(context: Context): Boolean {
        return false // Disabled
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabledFlow.value = enabled
    }

    fun getDomain(context: Context): String {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        return prefs.getString(KEY_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN
    }

    fun setDomain(context: Context, domain: String) {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        prefs.edit().putString(KEY_DOMAIN, domain.trim()).apply()
        _domainFlow.value = domain.trim()
    }

    fun getResolver(context: Context): String {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        return prefs.getString(KEY_RESOLVER, DEFAULT_RESOLVER) ?: DEFAULT_RESOLVER
    }

    fun setResolver(context: Context, resolver: String) {
        val prefs = com.gapmesh.droid.core.SecurePrefsFactory.create(context, PREFS_NAME)
        prefs.edit().putString(KEY_RESOLVER, resolver.trim()).apply()
        _resolverFlow.value = resolver.trim()
    }

    /**
     * Returns true if Slipstream is both enabled and has a valid domain configured.
     */
    fun isConfiguredAndEnabled(context: Context): Boolean {
        return isEnabled(context) && getDomain(context).isNotBlank()
    }
}
