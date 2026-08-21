package org.feeluown.mobile

/** Provider queue behavior belongs to provider feature policy, not the app-controller facade. */
internal fun ProviderFeature.isDynamicQueueFeature(): Boolean = id.endsWith("_radio")
