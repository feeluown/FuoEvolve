package org.feeluown.mobile

/** Playback only needs to know whether a provider feature owns an extendable radio-style queue. */
internal fun ProviderFeature.isDynamicQueueFeature(): Boolean = id.endsWith("_radio")
