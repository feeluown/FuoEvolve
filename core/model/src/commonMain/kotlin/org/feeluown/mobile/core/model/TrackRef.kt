package org.feeluown.mobile.core.model

/** Stable cross-feature track identity exposed by architecture APIs. */
data class TrackRef(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val source: String,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
)
