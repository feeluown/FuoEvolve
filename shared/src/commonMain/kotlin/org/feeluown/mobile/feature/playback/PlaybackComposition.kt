package org.feeluown.mobile

import androidx.compose.runtime.staticCompositionLocalOf
import org.feeluown.mobile.playback.api.PlaybackSession

/** App-scoped playback session supplied by the platform composition root. */
internal val LocalPlaybackSession = staticCompositionLocalOf<PlaybackSession> {
    error("PlaybackSession is not provided")
}
