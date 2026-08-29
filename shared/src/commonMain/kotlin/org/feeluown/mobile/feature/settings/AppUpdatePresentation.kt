package org.feeluown.mobile

/** Presentation-only name for update channels shown by Settings UI. */
internal val AppUpdateChannel.label: String
    get() = when (this) {
        AppUpdateChannel.Stable -> "正式版"
        AppUpdateChannel.Canary -> "抢先体验"
    }
