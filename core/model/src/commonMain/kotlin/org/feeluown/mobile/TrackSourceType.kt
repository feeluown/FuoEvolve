package org.feeluown.mobile

import kotlinx.serialization.Serializable

@Serializable
enum class TrackSourceType {
    Provider,
    LocalMediaStore,
    Downloaded,
}
