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
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        _ = IOSOAuthDeviceCodeOutput.shared
        return true
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
