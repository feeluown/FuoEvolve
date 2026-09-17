import AVFoundation
import BackgroundTasks
import SwiftUI
import UIKit
import UserNotifications
import Shared

private let playlistMigrationNotificationKindKey = "playlistMigration.kind"
private let playlistMigrationNotificationKind = "result"
private let playlistMigrationNotificationTaskIdKey = "playlistMigration.taskId"
private let playlistMigrationNotificationTargetKey = "playlistMigration.target"

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

private final class FuoEvolveAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private var routeChangeObserver: NSObjectProtocol?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        _ = IOSOAuthDeviceCodeOutput.shared
        UNUserNotificationCenter.current().delegate = self
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

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if notification.request.content.userInfo[playlistMigrationNotificationKindKey] as? String == playlistMigrationNotificationKind {
            completionHandler([.banner, .list, .sound])
        } else {
            completionHandler([])
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        guard
            userInfo[playlistMigrationNotificationKindKey] as? String == playlistMigrationNotificationKind,
            let taskId = userInfo[playlistMigrationNotificationTaskIdKey] as? String,
            let target = userInfo[playlistMigrationNotificationTargetKey] as? String
        else {
            completionHandler()
            return
        }
        PlaylistMigrationNotificationNavigationKt.openPlaylistMigrationFromNotification(
            taskId: taskId,
            target: target
        )
        completionHandler()
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
    private let workKeySeparator: Character = "|"
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
            guard
                let workKey = self.pendingTasks().keys.first,
                let work = self.parseWorkKey(workKey)
            else {
                processingTask.setTaskCompleted(success: true)
                return
            }
            processingTask.expirationHandler = { [weak self] in
                self?.scheduleDeferredProcessing()
            }
            self.runUntilBlocked(
                taskId: work.taskId,
                stage: work.stage,
                onProgress: nil
            ) { success, needsMore in
                if needsMore {
                    self.scheduleDeferredProcessing()
                } else {
                    self.finish(taskId: work.taskId, stage: work.stage)
                }
                processingTask.setTaskCompleted(success: success)
            }
        }

        if #available(iOS 26.0, *) {
            for workKey in pendingTasks().keys {
                guard let work = parseWorkKey(workKey) else { continue }
                registerContinuedTask(taskId: work.taskId, stage: work.stage)
            }
        }
    }

    func enqueue(taskId: String, sourceTitle: String, stage: String) {
        remember(taskId: taskId, sourceTitle: sourceTitle, stage: stage)
        requestNotificationAuthorizationIfNeeded()
        if #available(iOS 26.0, *), UIApplication.shared.applicationState == .active {
            submitContinuedTask(taskId: taskId, sourceTitle: sourceTitle, stage: stage)
        } else {
            startImmediateFallback(taskId: taskId, stage: stage)
        }
    }

    @available(iOS 26.0, *)
    private func submitContinuedTask(taskId: String, sourceTitle: String, stage: String) {
        let identifier = continuedIdentifier(taskId: taskId, stage: stage)
        registerContinuedTask(taskId: taskId, stage: stage)
        let request = BGContinuedProcessingTaskRequest(
            identifier: identifier,
            title: stage == "Writing" ? "写入歌单" : "转换歌单",
            subtitle: sourceTitle.isEmpty ? "正在准备迁移" : sourceTitle
        )
        request.strategy = .queue
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            startImmediateFallback(taskId: taskId, stage: stage)
        }
    }

    @available(iOS 26.0, *)
    private func registerContinuedTask(taskId: String, stage: String) {
        let identifier = continuedIdentifier(taskId: taskId, stage: stage)
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
            self.runContinued(taskId: taskId, stage: stage, task: continuedTask)
        }
        if !registered {
            registeredContinuedIdentifiers.remove(identifier)
        }
    }

    @available(iOS 26.0, *)
    private func runContinued(
        taskId: String,
        stage: String,
        task: BGContinuedProcessingTask
    ) {
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
            stage: stage,
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
                self.runContinued(taskId: taskId, stage: stage, task: task)
            } else {
                self.finish(taskId: taskId, stage: stage)
                task.setTaskCompleted(success: success)
            }
        }
    }

    private func startImmediateFallback(taskId: String, stage: String) {
        let key = workKey(taskId: taskId, stage: stage)
        if immediateBackgroundTasks[key] == nil {
            var token = UIBackgroundTaskIdentifier.invalid
            token = UIApplication.shared.beginBackgroundTask(withName: "Playlist migration") { [weak self] in
                if token != .invalid {
                    UIApplication.shared.endBackgroundTask(token)
                }
                self?.immediateBackgroundTasks.removeValue(forKey: key)
                self?.scheduleDeferredProcessing()
            }
            immediateBackgroundTasks[key] = token
        }
        runUntilBlocked(taskId: taskId, stage: stage, onProgress: nil) { [weak self] _, needsMore in
            guard let self else { return }
            if needsMore {
                self.runUntilBlocked(taskId: taskId, stage: stage, onProgress: nil) { _, stillNeedsMore in
                    if stillNeedsMore {
                        self.scheduleDeferredProcessing()
                    } else {
                        self.finish(taskId: taskId, stage: stage)
                    }
                }
            } else {
                self.finish(taskId: taskId, stage: stage)
            }
        }
    }

    private func runUntilBlocked(
        taskId: String,
        stage: String,
        onProgress: ((PlaylistMigrationBackgroundProgress) -> Void)?,
        completion: @escaping (Bool, Bool) -> Void
    ) {
        var lastProgress: PlaylistMigrationBackgroundProgress?
        PlaylistMigrationBackgroundSchedulerKt.runPlaylistMigrationBackgroundSlice(
            taskId: taskId,
            stage: stage,
            maxSteps: 24,
            onProgress: { progress in
                lastProgress = progress
                onProgress?(progress)
            },
            completionHandler: { [weak self] needsContinuation, error in
                let needsMore = needsContinuation.boolValue
                let success = error == nil
                if success, !needsMore, let progress = lastProgress, progress.terminal {
                    self?.publishTerminalNotification(progress, taskId: taskId, stage: stage)
                }
                completion(success, needsMore)
            }
        )
    }

    private func requestNotificationAuthorizationIfNeeded() {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .notDetermined else { return }
            center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
    }

    private func publishTerminalNotification(
        _ progress: PlaylistMigrationBackgroundProgress,
        taskId: String,
        stage: String
    ) {
        let content = UNMutableNotificationContent()
        content.title = progress.title
        content.body = progress.detail
        content.sound = .default
        content.userInfo = [
            playlistMigrationNotificationKindKey: playlistMigrationNotificationKind,
            playlistMigrationNotificationTaskIdKey: taskId,
            playlistMigrationNotificationTargetKey: stage == "Writing" ? "Result" : "Review",
        ]
        let request = UNNotificationRequest(
            identifier: "playlist-migration.result.\(stage.lowercased()).\(taskId)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
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

    private func finish(taskId: String, stage: String) {
        let key = workKey(taskId: taskId, stage: stage)
        var pending = pendingTasks()
        pending.removeValue(forKey: key)
        UserDefaults.standard.set(pending, forKey: pendingKey)
        if let token = immediateBackgroundTasks.removeValue(forKey: key), token != .invalid {
            UIApplication.shared.endBackgroundTask(token)
        }
        if #available(iOS 26.0, *) {
            BGTaskScheduler.shared.cancel(
                taskRequestWithIdentifier: continuedIdentifier(taskId: taskId, stage: stage)
            )
        }
    }

    private func remember(taskId: String, sourceTitle: String, stage: String) {
        var pending = pendingTasks()
        pending[workKey(taskId: taskId, stage: stage)] = sourceTitle
        UserDefaults.standard.set(pending, forKey: pendingKey)
    }

    private func pendingTasks() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: pendingKey) as? [String: String] ?? [:]
    }

    private func workKey(taskId: String, stage: String) -> String {
        stage + String(workKeySeparator) + taskId
    }

    private func parseWorkKey(_ key: String) -> (stage: String, taskId: String)? {
        guard let separator = key.firstIndex(of: workKeySeparator) else { return nil }
        let stage = String(key[..<separator])
        let taskId = String(key[key.index(after: separator)...])
        guard !stage.isEmpty, !taskId.isEmpty else { return nil }
        return (stage, taskId)
    }

    private func continuedIdentifier(taskId: String, stage: String) -> String {
        let safeTaskId = taskId.map { character -> Character in
            character.isLetter || character.isNumber || character == "-" ? character : "-"
        }
        let safeStage = stage.lowercased().map { character -> Character in
            character.isLetter || character.isNumber || character == "-" ? character : "-"
        }
        return continuedPrefix + String(safeStage) + "." + String(safeTaskId)
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
