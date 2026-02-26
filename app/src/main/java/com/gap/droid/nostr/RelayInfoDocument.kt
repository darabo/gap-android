package com.gapmesh.droid.nostr

import com.google.gson.annotations.SerializedName

/**
 * NIP-11 Relay Information Document.
 *
 * When you send a regular HTTP GET to a relay URL with `Accept: application/nostr+json`,
 * the relay responds with this JSON structure describing its capabilities, limitations,
 * fees, and supported NIPs. This allows us to skip dead, paid, or incompatible relays.
 *
 * Reference: Noghteha's `RelayInfoDocument.java` (1365 lines) — this is a clean-room
 * Kotlin implementation covering the same NIP-11 fields.
 *
 * Spec: https://github.com/nostr-protocol/nips/blob/master/11.md
 */
data class RelayInfoDocument(
    val name: String? = null,
    val description: String? = null,
    val pubkey: String? = null,
    val contact: String? = null,
    @SerializedName("supported_nips")
    val supportedNips: List<Int>? = null,
    val software: String? = null,
    val version: String? = null,
    val icon: String? = null,
    val limitation: RelayLimitation? = null,
    @SerializedName("payments_url")
    val paymentsUrl: String? = null,
    val fees: RelayFees? = null,
    val retention: List<RetentionPolicy>? = null,
    @SerializedName("relay_countries")
    val relayCountries: List<String>? = null,
    @SerializedName("language_tags")
    val languageTags: List<String>? = null,
    val tags: List<String>? = null,
    @SerializedName("posting_policy")
    val postingPolicy: String? = null
) {
    /** Returns true if the relay explicitly requires authentication. */
    fun requiresAuth(): Boolean = limitation?.authRequired == true

    /** Returns true if the relay explicitly requires payment. */
    fun requiresPayment(): Boolean =
        (limitation?.paymentRequired == true) ||
        (!fees?.admission.isNullOrEmpty())

    /** Returns true if the relay advertises support for a given NIP number. */
    fun supportsNip(nip: Int): Boolean = supportedNips?.contains(nip) == true

    /** Maximum message length in bytes, or null if unspecified. */
    fun maxMessageLength(): Int? = limitation?.maxMessageLength

    /** Maximum number of event tags allowed, or null if unspecified. */
    fun maxEventTags(): Int? = limitation?.maxEventTags

    /** Supports NIP-17 (gift-wrapped DMs). */
    fun supportsPrivateMessaging(): Boolean = supportsNip(17)

    /** Supports NIP-09 (event deletion). */
    fun supportsEventDeletion(): Boolean = supportsNip(9)

    /** Supports NIP-42 (authentication). */
    fun supportsAuthentication(): Boolean = supportsNip(42)

    /** Human-readable summary of the relay's capabilities. */
    fun capabilitiesSummary(): String = buildString {
        name?.let { append("Name: $it\n") }
        description?.let { append("Description: $it\n") }
        software?.let { s -> version?.let { v -> append("Software: $s v$v\n") } ?: append("Software: $s\n") }
        supportedNips?.let { append("NIPs: ${it.joinToString(", ")}\n") }
        limitation?.let { lim ->
            if (lim.authRequired == true) append("⚠️ Auth required\n")
            if (lim.paymentRequired == true) append("⚠️ Payment required\n")
            lim.maxMessageLength?.let { append("Max message: $it bytes\n") }
            lim.maxSubscriptions?.let { append("Max subscriptions: $it\n") }
        }
        if (requiresPayment()) append("💰 Paid relay\n")
    }
}

data class RelayLimitation(
    @SerializedName("max_message_length")
    val maxMessageLength: Int? = null,
    @SerializedName("max_subscriptions")
    val maxSubscriptions: Int? = null,
    @SerializedName("max_filters")
    val maxFilters: Int? = null,
    @SerializedName("max_limit")
    val maxLimit: Int? = null,
    @SerializedName("max_subid_length")
    val maxSubidLength: Int? = null,
    @SerializedName("max_event_tags")
    val maxEventTags: Int? = null,
    @SerializedName("max_content_length")
    val maxContentLength: Int? = null,
    @SerializedName("min_pow_difficulty")
    val minPowDifficulty: Int? = null,
    @SerializedName("auth_required")
    val authRequired: Boolean? = null,
    @SerializedName("payment_required")
    val paymentRequired: Boolean? = null,
    @SerializedName("restricted_writes")
    val restrictedWrites: Boolean? = null,
    @SerializedName("created_at_lower_limit")
    val createdAtLowerLimit: Long? = null,
    @SerializedName("created_at_upper_limit")
    val createdAtUpperLimit: Long? = null
)

data class RelayFees(
    val admission: List<FeeSchedule>? = null,
    val subscription: List<FeeSchedule>? = null,
    val publication: List<FeeSchedule>? = null
)

data class FeeSchedule(
    val amount: Long? = null,
    val unit: String? = null,
    val period: Long? = null,
    val kinds: List<Int>? = null
)

data class RetentionPolicy(
    val kinds: List<Int>? = null,
    val time: Long? = null,
    val count: Int? = null
)
