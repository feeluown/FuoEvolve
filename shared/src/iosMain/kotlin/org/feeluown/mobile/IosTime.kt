package org.feeluown.mobile

import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL

internal fun String.toNsUrl(): NSURL? {
    return when {
        startsWith("file://") -> NSURL.URLWithString(this)
        startsWith("/") -> NSURL.fileURLWithPath(this)
        else -> NSURL.URLWithString(this)
    }
}

internal fun Long.toCMTime(): CMTime = CMTimeMakeWithSeconds(toDouble() / 1000.0, preferredTimescale = 600)

internal fun CMTime.toMillis(): Long {
    val seconds = CMTimeGetSeconds(this)
    if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return 0
    return (seconds * 1000.0).toLong()
}
