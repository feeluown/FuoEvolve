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
}
