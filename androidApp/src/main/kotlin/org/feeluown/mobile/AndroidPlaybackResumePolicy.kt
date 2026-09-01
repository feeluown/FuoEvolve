package org.feeluown.mobile

/** Only established playback can become a durable resume point. */
internal fun PlayerStatus.isDurablePlaybackResumeStatus(): Boolean =
    this == PlayerStatus.Playing || this == PlayerStatus.Paused
