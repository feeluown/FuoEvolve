import AVFoundation
import AVKit
import CoreMedia
import Shared
import UIKit

private var videoFullscreenActive = false
private var previousVideoInterfaceOrientation: UIInterfaceOrientation?

extension IOSNativeVideoOutput {
    func play(viewController: UIViewController) {
        guard let controller = viewController as? AVPlayerViewController else { return }
        controller.player?.play()
    }

    func pause(viewController: UIViewController) {
        guard let controller = viewController as? AVPlayerViewController else { return }
        controller.player?.pause()
    }

    func seekTo(viewController: UIViewController, positionMs: Int64) {
        guard let controller = viewController as? AVPlayerViewController else { return }
        let target = max(positionMs, 0)
        controller.player?.seek(to: CMTime(value: target, timescale: 1000))
    }

    func positionMs(viewController: UIViewController) -> Int64 {
        guard let controller = viewController as? AVPlayerViewController else { return 0 }
        return milliseconds(controller.player?.currentTime())
    }

    func durationMs(viewController: UIViewController) -> Int64 {
        guard let controller = viewController as? AVPlayerViewController else { return 0 }
        return milliseconds(controller.player?.currentItem?.duration)
    }

    func bufferedMs(viewController: UIViewController) -> Int64 {
        guard
            let controller = viewController as? AVPlayerViewController,
            let range = controller.player?.currentItem?.loadedTimeRanges.last?.timeRangeValue
        else {
            return 0
        }
        return milliseconds(CMTimeAdd(range.start, range.duration))
    }

    func isPlaying(viewController: UIViewController) -> Bool {
        guard let controller = viewController as? AVPlayerViewController else { return false }
        return (controller.player?.rate ?? 0) > 0
    }

    func videoWidth(viewController: UIViewController) -> Int32 {
        guard let controller = viewController as? AVPlayerViewController else { return 0 }
        let width = controller.player?.currentItem?.presentationSize.width ?? 0
        return width.isFinite && width > 0 ? Int32(width.rounded()) : 0
    }

    func videoHeight(viewController: UIViewController) -> Int32 {
        guard let controller = viewController as? AVPlayerViewController else { return 0 }
        let height = controller.player?.currentItem?.presentationSize.height ?? 0
        return height.isFinite && height > 0 ? Int32(height.rounded()) : 0
    }

    func setFullscreenOrientation(isFullscreen: Bool, landscape: Bool) {
        DispatchQueue.main.async {
            guard let scene = activeWindowScene() else { return }
            if isFullscreen {
                if !videoFullscreenActive {
                    previousVideoInterfaceOrientation = scene.interfaceOrientation
                    videoFullscreenActive = true
                }
                requestInterfaceOrientation(
                    landscape ? .landscape : .portrait,
                    fallback: landscape ? .landscapeRight : .portrait,
                    scene: scene
                )
            } else if videoFullscreenActive {
                let previous = previousVideoInterfaceOrientation ?? .portrait
                requestInterfaceOrientation(
                    orientationMask(previous),
                    fallback: previous,
                    scene: scene
                )
                previousVideoInterfaceOrientation = nil
                videoFullscreenActive = false
            }
        }
    }
}

private func milliseconds(_ time: CMTime?) -> Int64 {
    guard let time else { return 0 }
    let seconds = CMTimeGetSeconds(time)
    guard seconds.isFinite, seconds > 0 else { return 0 }
    return Int64((seconds * 1000).rounded())
}

private func activeWindowScene() -> UIWindowScene? {
    UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .first { $0.activationState == .foregroundActive }
        ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
}

private func orientationMask(_ orientation: UIInterfaceOrientation) -> UIInterfaceOrientationMask {
    switch orientation {
    case .landscapeLeft:
        return .landscapeLeft
    case .landscapeRight:
        return .landscapeRight
    case .portraitUpsideDown:
        return .portraitUpsideDown
    case .portrait, .unknown:
        return .portrait
    @unknown default:
        return .portrait
    }
}

private func requestInterfaceOrientation(
    _ mask: UIInterfaceOrientationMask,
    fallback: UIInterfaceOrientation,
    scene: UIWindowScene
) {
    if #available(iOS 16.0, *) {
        scene.windows.first { $0.isKeyWindow }?.rootViewController?
            .setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
    } else {
        UIDevice.current.setValue(fallback.rawValue, forKey: "orientation")
        UIViewController.attemptRotationToDeviceOrientation()
    }
}
