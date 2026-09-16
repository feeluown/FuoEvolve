import AVFoundation
import SwiftUI
import UIKit
import UserNotifications
import Shared

@main
struct FuoEvolveApp: App {
    @UIApplicationDelegateAdaptor(FuoEvolveAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            SharedComposeRoot()
                .ignoresSafeArea()
        }
    }
}

private final class FuoEvolveAppDelegate: NSObject, UIApplicationDelegate {
    private var routeChangeObserver: NSObjectProtocol?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        _ = IOSOAuthDeviceCodeOutput.shared
        // A route change can arrive while the UI is backgrounded; observe it for the lifetime
        // of the process rather than tying the safety behavior to the Compose view.
        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { notification in
            guard
                let reasonValue = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                AVAudioSession.RouteChangeReason(rawValue: reasonValue) == .oldDeviceUnavailable,
                let previousRoute = notification.userInfo?[AVAudioSessionRouteChangePreviousRouteKey]
                    as? AVAudioSessionRouteDescription,
                previousRoute.outputs.contains(where: { $0.portType.isHeadphoneOutput }),
                !AVAudioSession.sharedInstance().currentRoute.outputs.contains(where: { $0.portType.isHeadphoneOutput })
            else {
                return
            }
            IOSNativeAudioEngine.shared.pause()
        }
        return true
    }

    deinit {
        if let routeChangeObserver {
            NotificationCenter.default.removeObserver(routeChangeObserver)
        }
    }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        IOSDownloadOutput.shared.handleBackgroundEvents(
            identifier: identifier,
            completionHandler: completionHandler
        )
    }
}

private extension AVAudioSession.Port {
    var isHeadphoneOutput: Bool {
        switch self {
        case .headphones, .bluetoothA2DP, .bluetoothHFP, .bluetoothLE:
            return true
        default:
            return false
        }
    }
}

private struct SharedComposeRoot: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let viewController = IosAppHostKt.MainViewController(
            audioOutput: IOSNativeAudioEngine.shared,
            videoOutput: IOSNativeVideoOutput.shared,
            mediaLibraryOutput: IOSMediaLibraryOutput.shared,
            downloadOutput: IOSDownloadOutput.shared,
            webLoginOutput: IOSWebLoginOutput.shared,
            shareOutput: IOSShareOutput.shared,
            localPlaylistFileOutput: IOSShareOutput.shared,
            networkStatusOutput: IOSNetworkStatusOutput.shared,
            audioRecognitionOutput: IOSAudioRecognitionOutput.shared,
            oauthDeviceCodeOutput: IOSOAuthDeviceCodeOutput.shared
        )
        IOSWebLoginOutput.shared.hostViewController = viewController
        return viewController
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}
