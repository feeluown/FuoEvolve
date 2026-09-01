package org.feeluown.mobile

/** Only established playback can become a durable resume point. */
internal fun PlayerStatus.isDurablePlaybackResumeStatus(): Boolean =
    this == PlayerStatus.Playing || this == PlayerStatus.Paused

/** Only a new logical selection invalidates the previous durable resume point. */
internal val PlaybackStartReason.clearsDurablePlaybackResume: Boolean
    get() = isActiveSelection
