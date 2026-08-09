package org.feeluown.mobile

import platform.UIKit.UIViewController

interface IosVideoOutput {
    fun makePlayer(url: String, videoUrl: String, audioUrl: String, headers: Map<String, String>): UIViewController

    fun updatePlayer(
        viewController: UIViewController,
        url: String,
        videoUrl: String,
        audioUrl: String,
        headers: Map<String, String>,
    )

    fun releasePlayer(viewController: UIViewController)

    fun play(viewController: UIViewController)

    fun pause(viewController: UIViewController)

    fun seekTo(viewController: UIViewController, positionMs: Long)

    fun positionMs(viewController: UIViewController): Long

    fun durationMs(viewController: UIViewController): Long

    fun bufferedMs(viewController: UIViewController): Long

    fun isPlaying(viewController: UIViewController): Boolean

    fun videoWidth(viewController: UIViewController): Int

    fun videoHeight(viewController: UIViewController): Int

    fun setFullscreenOrientation(isFullscreen: Boolean, landscape: Boolean)
}
