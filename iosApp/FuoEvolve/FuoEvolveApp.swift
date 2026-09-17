import AVFoundation
import BackgroundTasks
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
        IosPlaylistMigrationBackgroundKt.installIosPlaylistMigrationBackgroundOutput(
            output: IOSPlaylistMigrationBackground.shared
        )
        IOSPlaylistMigrationBackground.shared.configure()
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

private final class IOSPlaylistMigrationBackground: NSObject, IosPlaylistMigrationBackgroundOutput {
    static let shared = IOSPlaylistMigrationBackground()

    private let processingIdentifier = "org.feeluown.mobile.playlist-migration.processing"
    private let continuedPrefix = "org.feeluown.mobile.playlist-migration."
    private let pendingKey = "playlistMigration.pendingBackgroundTasks"
    private var registeredContinuedIdentifiers = Set<String>()
    private var immediateBackgroundTasks: [String: UIBackgroundTaskIdentifier] = [:]

    private override init() {
        super.init()
    }

    func configure() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: processingIdentifier,
            using: .main
        ) { [weak self] task in
            guard let self, let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            guard let taskId = self.pendingTasks().keys.first else {
                processingTask.setTaskCompleted(success: true)
                return
            }
            processingTask.expirationHandler = { [weak self] in
                self?.scheduleDeferredProcessing()
            }
            self.runUntilBlocked(taskId: taskId, onProgress: nil) { success, needsMore in
                if needsMore {
                    self.scheduleDeferredProcessing()
                } else {
                    self.finish(taskId: taskId)
                }
                processingTask.setTaskCompleted(success: success)
            }
        }

        if #available(iOS 26.0, *) {
            for (taskId, _) in pendingTasks() {
                registerContinuedTask(taskId: taskId)
            }
        }
    }

    func enqueue(taskId: String, sourceTitle: String) {
        remember(taskId: taskId, sourceTitle: sourceTitle)
        if #available(iOS 26.0, *), UIApplication.shared.applicationState == .active {
            submitContinuedTask(taskId: taskId, sourceTitle: sourceTitle)
        } else {
            startImmediateFallback(taskId: taskId)
        }
    }

    @available(iOS 26.0, *)
    private func submitContinuedTask(taskId: String, sourceTitle: String) {
        let identifier = continuedIdentifier(taskId)
        registerContinuedTask(taskId: taskId)
        let request = BGContinuedProcessingTaskRequest(
            identifier: identifier,
            title: "迁移歌单",
            subtitle: sourceTitle.isEmpty ? "正在准备迁移" : sourceTitle
        )
        request.strategy = .queue
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            startImmediateFallback(taskId: taskId)
        }
    }

    @available(iOS 26.0, *)
    private func registerContinuedTask(taskId: String) {
        let identifier = continuedIdentifier(taskId)
        guard registeredContinuedIdentifiers.insert(identifier).inserted else { return }
        let registered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: identifier,
            using: .main
        ) { [weak self] task in
            guard
                let self,
                let continuedTask = task as? BGContinuedProcessingTask
            else {
                task.setTaskCompleted(success: false)
                return
            }
            self.runContinued(taskId: taskId, task: continuedTask)
        }
        if !registered {
            registeredContinuedIdentifiers.remove(identifier)
        }
    }

    @available(iOS 26.0, *)
    private func runContinued(taskId: String, task: BGContinuedProcessingTask) {
        var expired = false
        task.expirationHandler = { [weak self] in
            expired = true
            PlaylistMigrationBackgroundSchedulerKt.pausePlaylistMigrationFromBackground(
                taskId: taskId,
                completionHandler: { _ in }
            )
            self?.scheduleDeferredProcessing()
        }
        runUntilBlocked(
            taskId: taskId,
            onProgress: { progress in
                let total = progress.indeterminate ? 100 : max(1, Int(progress.total))
                let completed = progress.indeterminate ? 0 : min(total, Int(progress.completed))
                task.progress.totalUnitCount = Int64(total)
                task.progress.completedUnitCount = Int64(completed)
                task.updateTitle(progress.title, subtitle: progress.detail)
            }
        ) { [weak self] success, needsMore in
            guard let self else {
                task.setTaskCompleted(success: false)
                return
            }
            if expired {
                task.setTaskCompleted(success: false)
                return
            }
            if needsMore {
                self.runContinued(taskId: taskId, task: task)
            } else {
                self.finish(taskId: taskId)
                task.setTaskCompleted(success: success)
            }
        }
    }

    private func startImmediateFallback(taskId: String) {
        if immediateBackgroundTasks[taskId] == nil {
            var token = UIBackgroundTaskIdentifier.invalid
            token = UIApplication.shared.beginBackgroundTask(withName: "Playlist migration") { [weak self] in
                if token != .invalid {
                    UIApplication.shared.endBackgroundTask(token)
                }
                self?.immediateBackgroundTasks.removeValue(forKey: taskId)
                self?.scheduleDeferredProcessing()
            }
            immediateBackgroundTasks[taskId] = token
        }
        runUntilBlocked(taskId: taskId, onProgress: nil) { [weak self] _, needsMore in
            guard let self else { return }
            if needsMore {
                self.runUntilBlocked(taskId: taskId, onProgress: nil) { _, stillNeedsMore in
                    if stillNeedsMore { self.scheduleDeferredProcessing() } else { self.finish(taskId: taskId) }
                }
            } else {
                self.finish(taskId: taskId)
            }
        }
    }

    private func runUntilBlocked(
        taskId: String,
        onProgress: ((PlaylistMigrationBackgroundProgress) -> Void)?,
        completion: @escaping (Bool, Bool) -> Void
    ) {
        PlaylistMigrationBackgroundSchedulerKt.runPlaylistMigrationBackgroundSlice(
            taskId: taskId,
            maxSteps: 24,
            onProgress: { progress in onProgress?(progress) },
            completionHandler: { needsContinuation, error in
                completion(error == nil, needsContinuation.boolValue)
            }
        )
    }

    private func scheduleDeferredProcessing() {
        guard !pendingTasks().isEmpty else { return }
        let request = BGProcessingTaskRequest(identifier: processingIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Checkpoints remain durable; the next foreground resume can retry.
        }
    }

    private func finish(taskId: String) {
        var pending = pendingTasks()
        pending.removeValue(forKey: taskId)
        UserDefaults.standard.set(pending, forKey: pendingKey)
        if let token = immediateBackgroundTasks.removeValue(forKey: taskId), token != .invalid {
            UIApplication.shared.endBackgroundTask(token)
        }
        if #available(iOS 26.0, *) {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: continuedIdentifier(taskId))
        }
    }

    private func remember(taskId: String, sourceTitle: String) {
        var pending = pendingTasks()
        pending[taskId] = sourceTitle
        UserDefaults.standard.set(pending, forKey: pendingKey)
    }

    private func pendingTasks() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: pendingKey) as? [String: String] ?? [:]
    }

    private func continuedIdentifier(_ taskId: String) -> String {
        let safeId = taskId.map { character -> Character in
            character.isLetter || character.isNumber || character == "-" ? character : "-"
        }
        return continuedPrefix + String(safeId)
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
