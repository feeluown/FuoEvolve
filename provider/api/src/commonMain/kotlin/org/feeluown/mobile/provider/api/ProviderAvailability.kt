package org.feeluown.mobile.provider.api

/** Provider availability boundary consumed by features without exposing session/runtime internals. */
fun interface ProviderAvailability {
    fun contains(providerId: String): Boolean
}
