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
        IosAppHostKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}
