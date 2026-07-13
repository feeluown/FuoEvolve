import SwiftUI
import Shared

@main
struct FuoEvolveApp: App {
    var body: some Scene {
        WindowGroup {
            SharedComposeRoot()
                .ignoresSafeArea()
        }
    }
}

private struct SharedComposeRoot: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let viewController = IosAppHostKt.MainViewController(
            pythonRuntime: PythonCoreBridge.shared,
            audioOutput: IOSNativeAudioEngine.shared,
            videoOutput: IOSNativeVideoOutput.shared,
            mediaLibraryOutput: IOSMediaLibraryOutput.shared,
            downloadOutput: IOSDownloadOutput.shared,
            webLoginOutput: IOSWebLoginOutput.shared,
            shareOutput: IOSShareOutput.shared
        )
        IOSWebLoginOutput.shared.hostViewController = viewController
        return viewController
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}
