import AVFoundation
import AVKit
import CoreMedia
import MediaPlayer
import Network
import Shared
import UIKit
import UniformTypeIdentifiers
import WebKit

final class IOSNativeAudioEngine: NSObject, NativeAudioEngine, IosAudioOutput {
    static let shared = IOSNativeAudioEngine()
    private let player = AVPlayer()
    private var currentPayload: PlaybackPayload?
    private var didReachEnd = false
    private var playbackError: String?
    private var endObserver: NSObjectProtocol?
    private var periodicTimeObserver: Any?

    override init() {
        super.init()
        configureAudioSession()
        configureRemoteCommands()
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self, notification.object as? AVPlayerItem === self.player.currentItem else { return }
            self.didReachEnd = true
            self.updateNowPlaying()
        }
        periodicTimeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1, preferredTimescale: 600),
            queue: .main
        ) { [weak self] _ in
            self?.updateNowPlaying()
        }
    }

    deinit {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        if let periodicTimeObserver {
            player.removeTimeObserver(periodicTimeObserver)
        }
    }

    func play(_ payload: PlaybackPayload) {
        guard let url = preferredMediaURL(payload.url) else {
            playbackError = "音频地址无效"
            return
        }
        didReachEnd = false
        playbackError = nil
        currentPayload = payload
        var assetOptions: [String: Any] = [:]
        if !payload.headers.isEmpty {
            assetOptions["AVURLAssetHTTPHeaderFieldsKey"] = payload.headers
        }
        let asset = AVURLAsset(url: url, options: assetOptions)
        player.replaceCurrentItem(with: AVPlayerItem(asset: asset))
        player.play()
        updateNowPlaying(payload: payload, playbackRate: 1)
    }

    func play(url: String, headers: [String: String], title: String, artists: String, album: String) {
        play(PlaybackPayload(url: url, title: title, artists: artists, album: album, source: "", headers: headers, coverUrl: nil))
    }

    func pause() {
        player.pause()
        updateNowPlaying()
    }

    func resume() {
        player.play()
        updateNowPlaying(playbackRate: 1)
    }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        didReachEnd = false
        playbackError = nil
        currentPayload = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    func seekTo(positionMs: Int64) {
        didReachEnd = false
        let duration = durationMs()
        let nonNegativePosition = max(positionMs, 0)
        let normalizedPosition = duration > 0 ? min(nonNegativePosition, duration) : nonNegativePosition
        player.seek(to: CMTime(value: normalizedPosition, timescale: 1000)) { [weak self] _ in
            self?.updateNowPlaying()
        }
    }

    func playbackStatus() -> String {
        if playbackError != nil || player.currentItem?.status == .failed {
            return "Error"
        }
        if didReachEnd {
            return "Ended"
        }
        guard player.currentItem != nil else {
            return "Idle"
        }
        switch player.timeControlStatus {
        case .waitingToPlayAtSpecifiedRate:
            return "Loading"
        case .playing:
            return "Playing"
        case .paused:
            return "Paused"
        @unknown default:
            return "Paused"
        }
    }

    func positionMs() -> Int64 {
        milliseconds(player.currentTime())
    }

    func durationMs() -> Int64 {
        guard let duration = player.currentItem?.duration else { return 0 }
        return milliseconds(duration)
    }

    func bufferedMs() -> Int64 {
        guard let range = player.currentItem?.loadedTimeRanges.last?.timeRangeValue else { return 0 }
        return milliseconds(CMTimeAdd(range.start, range.duration))
    }

    func errorMessage() -> String? {
        playbackError ?? player.currentItem?.error?.localizedDescription
    }

    func audioFormatInfo() -> Shared.AudioFormatInfo? {
        guard let track = player.currentItem?.asset.tracks(withMediaType: .audio).first else { return nil }
        let codec = track.formatDescriptions.first
            .map { CMFormatDescriptionGetMediaSubType($0 as! CMFormatDescription) }
            .map(fourCharacterCode)
        let averageBitrate = track.estimatedDataRate > 0 ? Int64(track.estimatedDataRate.rounded()) : nil
        return Shared.AudioFormatInfo(
            format: codec.map(audioFormatName),
            codec: codec,
            averageBitrate: averageBitrate.map { KotlinLong(value: $0) },
            peakBitrate: nil
        )
    }

    private func milliseconds(_ time: CMTime) -> Int64 {
        let seconds = CMTimeGetSeconds(time)
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return Int64(seconds * 1000)
    }

    private func audioFormatName(_ codec: String) -> String {
        switch codec {
        case "mp4a": return "AAC"
        case ".mp3": return "MP3"
        case "alac": return "ALAC"
        case "fLaC": return "FLAC"
        case "Opus": return "Opus"
        case "vorb": return "Vorbis"
        default: return codec
        }
    }

    private func fourCharacterCode(_ value: UInt32) -> String {
        String(bytes: [
            UInt8((value >> 24) & 0xff),
            UInt8((value >> 16) & 0xff),
            UInt8((value >> 8) & 0xff),
            UInt8(value & 0xff),
        ], encoding: .macOSRoman) ?? String(value)
    }

    private func preferredMediaURL(_ rawURL: String) -> URL? {
        guard !rawURL.isEmpty, var components = URLComponents(string: rawURL) else { return nil }
        if components.scheme == "http", components.host?.hasSuffix(".qqmusic.qq.com") == true {
            components.scheme = "https"
        }
        return components.url
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            assertionFailure(error.localizedDescription)
        }
    }

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            self?.resume()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        center.changePlaybackPositionCommand.isEnabled = true
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard
                let self,
                self.player.currentItem != nil,
                let event = event as? MPChangePlaybackPositionCommandEvent
            else {
                return .commandFailed
            }
            self.seekTo(positionMs: Int64((event.positionTime * 1000).rounded()))
            return .success
        }
    }

    private func updateNowPlaying(payload: PlaybackPayload? = nil, playbackRate: Float? = nil) {
        guard let activePayload = payload ?? currentPayload else { return }
        var nowPlayingInfo: [String: Any] = [
            MPMediaItemPropertyTitle: activePayload.title,
            MPMediaItemPropertyArtist: activePayload.artists,
            MPMediaItemPropertyAlbumTitle: activePayload.album,
        ]
        let duration = durationMs()
        if duration > 0 {
            nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = Double(duration) / 1000
        }
        let elapsed = max(0, CMTimeGetSeconds(player.currentTime()))
        if elapsed.isFinite {
            nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed
        }
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = playbackRate ?? (didReachEnd ? 0 : player.rate)
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
}

final class IOSNativeVideoOutput: NSObject, IosVideoOutput {
    static let shared = IOSNativeVideoOutput()

    func makePlayer(
        url: String,
        videoUrl: String,
        audioUrl: String,
        headers: [String: String]
    ) -> UIViewController {
        let controller = FuoVideoViewController()
        configure(controller, url: url, videoUrl: videoUrl, audioUrl: audioUrl, headers: headers)
        return controller
    }

    func updatePlayer(
        viewController: UIViewController,
        url: String,
        videoUrl: String,
        audioUrl: String,
        headers: [String: String]
    ) {
        guard let controller = viewController as? FuoVideoViewController else { return }
        configure(controller, url: url, videoUrl: videoUrl, audioUrl: audioUrl, headers: headers)
    }

    func releasePlayer(viewController: UIViewController) {
        guard let controller = viewController as? AVPlayerViewController else { return }
        controller.player?.pause()
        controller.player = nil
    }

    private func configure(
        _ controller: FuoVideoViewController,
        url: String,
        videoUrl: String,
        audioUrl: String,
        headers: [String: String]
    ) {
        let signature = [url, videoUrl, audioUrl, headers.description].joined(separator: "|")
        guard controller.payloadSignature != signature else { return }
        controller.payloadSignature = signature
        controller.player?.pause()
        controller.player = makePlayer(url: url, videoUrl: videoUrl, audioUrl: audioUrl, headers: headers)
        controller.player?.play()
    }

    private func makePlayer(
        url: String,
        videoUrl: String,
        audioUrl: String,
        headers: [String: String]
    ) -> AVPlayer? {
        if let mediaURL = URL(string: url), !url.isEmpty {
            return AVPlayer(playerItem: AVPlayerItem(asset: asset(url: mediaURL, headers: headers)))
        }
        guard
            let videoMediaURL = URL(string: videoUrl),
            let audioMediaURL = URL(string: audioUrl),
            !videoUrl.isEmpty,
            !audioUrl.isEmpty
        else {
            return nil
        }
        let videoAsset = asset(url: videoMediaURL, headers: headers)
        let audioAsset = asset(url: audioMediaURL, headers: headers)
        guard
            let videoTrack = videoAsset.tracks(withMediaType: .video).first,
            let audioTrack = audioAsset.tracks(withMediaType: .audio).first
        else {
            return nil
        }
        let composition = AVMutableComposition()
        guard
            let compositionVideo = composition.addMutableTrack(
                withMediaType: .video,
                preferredTrackID: kCMPersistentTrackID_Invalid
            ),
            let compositionAudio = composition.addMutableTrack(
                withMediaType: .audio,
                preferredTrackID: kCMPersistentTrackID_Invalid
            )
        else {
            return nil
        }
        do {
            try compositionVideo.insertTimeRange(
                CMTimeRange(start: .zero, duration: videoAsset.duration),
                of: videoTrack,
                at: .zero
            )
            try compositionAudio.insertTimeRange(
                CMTimeRange(start: .zero, duration: audioAsset.duration),
                of: audioTrack,
                at: .zero
            )
            compositionVideo.preferredTransform = videoTrack.preferredTransform
            return AVPlayer(playerItem: AVPlayerItem(asset: composition))
        } catch {
            return nil
        }
    }

    private func asset(url: URL, headers: [String: String]) -> AVURLAsset {
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        if components?.scheme == "http", components?.host?.hasSuffix(".qqmusic.qq.com") == true {
            components?.scheme = "https"
        }
        let resolvedURL = components?.url ?? url
        let options: [String: Any]? = headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers]
        return AVURLAsset(url: resolvedURL, options: options)
    }
}

private final class FuoVideoViewController: AVPlayerViewController {
    var payloadSignature = ""
}

final class IOSMediaLibraryOutput: NSObject, IosMediaLibraryOutput {
    static let shared = IOSMediaLibraryOutput()

    func hasPermission() -> Bool {
        MPMediaLibrary.authorizationStatus() == .authorized
    }

    func requestPermission(completionHandler: @escaping @Sendable (KotlinBoolean) -> Void) {
        if hasPermission() {
            completionHandler(KotlinBoolean(bool: true))
            return
        }
        MPMediaLibrary.requestAuthorization { status in
            DispatchQueue.main.async {
                completionHandler(KotlinBoolean(bool: status == .authorized))
            }
        }
    }

    func tracksJson() -> String {
        let tracks: [[String: Any]] = (MPMediaQuery.songs().items ?? []).compactMap { item in
            guard let assetURL = item.assetURL else { return nil }
            return [
                "id": String(item.persistentID),
                "title": item.title ?? "",
                "artists": item.artist ?? "",
                "album": item.albumTitle ?? "",
                "duration_ms": Int64(item.playbackDuration * 1000),
                "local_uri": assetURL.absoluteString,
            ]
        }
        guard
            let data = try? JSONSerialization.data(withJSONObject: ["tracks": tracks]),
            let json = String(data: data, encoding: .utf8)
        else {
            return #"{"tracks":[]}"#
        }
        return json
    }
}

final class IOSDownloadOutput: NSObject, IosDownloadOutput, URLSessionDownloadDelegate {
    static let shared = IOSDownloadOutput()
    private var tasks: [String: URLSessionDownloadTask] = [:]
    private var taskContexts: [String: DownloadContext] = [:]
    private let resumePrefix = "ios_download_resume_"
    private var backgroundCompletionHandler: (() -> Void)?
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: "org.feeluown.mobile.downloads")
        configuration.sessionSendsLaunchEvents = true
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    func download(
        taskId: String,
        url: String,
        headers: [String: String],
        fileName: String,
        lyrics: String?,
        completionHandler: @escaping (String?, String?) -> Void
    ) {
        guard let sourceURL = URL(string: url) else {
            completionHandler(nil, "下载地址无效")
            return
        }
        var request = URLRequest(url: sourceURL)
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let resumeKey = resumePrefix + taskId
        let task: URLSessionDownloadTask
        if let resumeData = UserDefaults.standard.data(forKey: resumeKey) {
            task = session.downloadTask(withResumeData: resumeData)
            UserDefaults.standard.removeObject(forKey: resumeKey)
        } else {
            task = session.downloadTask(with: request)
        }
        task.taskDescription = taskId
        tasks[taskId] = task
        taskContexts[taskId] = DownloadContext(
            fileName: fileName,
            lyrics: lyrics,
            completionHandler: completionHandler
        )
        task.resume()
    }

    func pause(taskId: String) {
        guard let task = tasks.removeValue(forKey: taskId) else { return }
        taskContexts.removeValue(forKey: taskId)
        task.cancel(byProducingResumeData: { [resumePrefix] data in
            guard let data else { return }
            UserDefaults.standard.set(data, forKey: resumePrefix + taskId)
        })
    }

    func deleteTemporary(taskId: String) {
        tasks.removeValue(forKey: taskId)?.cancel()
        taskContexts.removeValue(forKey: taskId)
        UserDefaults.standard.removeObject(forKey: resumePrefix + taskId)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard
            let taskId = downloadTask.taskDescription,
            let context = taskContexts.removeValue(forKey: taskId)
        else {
            return
        }
        tasks.removeValue(forKey: taskId)
        do {
            let directory = try downloadDirectory()
            let target = directory.appendingPathComponent(context.fileName)
            if FileManager.default.fileExists(atPath: target.path) {
                try FileManager.default.removeItem(at: target)
            }
            try FileManager.default.moveItem(at: location, to: target)
            if let lyrics = context.lyrics, !lyrics.isEmpty {
                let lyricsURL = target.deletingPathExtension().appendingPathExtension("lrc")
                try? lyrics.write(to: lyricsURL, atomically: true, encoding: .utf8)
            }
            DispatchQueue.main.async { context.completionHandler(target.absoluteString, nil) }
        } catch {
            DispatchQueue.main.async { context.completionHandler(nil, error.localizedDescription) }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard
            let error,
            let taskId = task.taskDescription,
            let context = taskContexts.removeValue(forKey: taskId)
        else {
            return
        }
        tasks.removeValue(forKey: taskId)
        DispatchQueue.main.async { context.completionHandler(nil, error.localizedDescription) }
    }

    func handleBackgroundEvents(identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == session.configuration.identifier else {
            completionHandler()
            return
        }
        backgroundCompletionHandler = completionHandler
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.backgroundCompletionHandler?()
            self.backgroundCompletionHandler = nil
        }
    }

    func delete(uri: String) -> Bool {
        guard let url = URL(string: uri) else { return false }
        do {
            if FileManager.default.fileExists(atPath: url.path) {
                try FileManager.default.removeItem(at: url)
            }
            let lyricsURL = url.deletingPathExtension().appendingPathExtension("lrc")
            if FileManager.default.fileExists(atPath: lyricsURL.path) {
                try? FileManager.default.removeItem(at: lyricsURL)
            }
            return true
        } catch {
            return false
        }
    }

    private func downloadDirectory() throws -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let directory = documents.appendingPathComponent("FeelUOwn", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private struct DownloadContext {
        let fileName: String
        let lyrics: String?
        let completionHandler: (String?, String?) -> Void
    }
}

final class IOSWebLoginOutput: NSObject, IosWebLoginOutput {
    static let shared = IOSWebLoginOutput()
    weak var hostViewController: UIViewController?

    func open(
        providerId: String,
        providerName: String,
        loginUrl: String,
        cookieKeyGroupsJson: String,
        completionHandler: @escaping (String?) -> Void
    ) {
        DispatchQueue.main.async {
            guard let url = URL(string: loginUrl), let presenter = Self.topViewController() else {
                completionHandler(nil)
                return
            }
            let groups = (try? JSONSerialization.jsonObject(with: Data(cookieKeyGroupsJson.utf8)))
                as? [[String]] ?? []
            let login = FuoWebLoginViewController(
                providerName: providerName,
                url: url,
                requiredCookieGroups: groups,
                completion: completionHandler
            )
            presenter.present(UINavigationController(rootViewController: login), animated: true)
        }
    }

    func clear() {
        WKWebsiteDataStore.default().httpCookieStore.getAllCookies { cookies in
            cookies.forEach { WKWebsiteDataStore.default().httpCookieStore.delete($0) }
        }
    }

    fileprivate static func topViewController() -> UIViewController? {
        let sceneController = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
        var controller = shared.hostViewController ?? sceneController
        while true {
            if let presented = controller?.presentedViewController {
                controller = presented
            } else if let navigation = controller as? UINavigationController {
                controller = navigation.visibleViewController
            } else if let tabs = controller as? UITabBarController {
                controller = tabs.selectedViewController
            } else {
                return controller
            }
        }
    }
}

private final class IOSLocalPlaylistDocumentPickerDelegate: NSObject, UIDocumentPickerDelegate {
    private let completion: (String?, String?) -> Void

    init(completion: @escaping (String?, String?) -> Void) {
        self.completion = completion
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            completion(nil, nil)
            return
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        let content = try? String(contentsOf: url, encoding: .utf8)
        completion(url.lastPathComponent, content)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        completion(nil, nil)
    }
}

final class IOSShareOutput: NSObject, IosShareOutput, IosLocalPlaylistFileOutput {
    static let shared = IOSShareOutput()
    private var localPlaylistPickerDelegate: IOSLocalPlaylistDocumentPickerDelegate?

    func share(text: String) {
        guard let presenter = IOSWebLoginOutput.topViewController() else { return }
        let activity = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = presenter.view
        activity.popoverPresentationController?.sourceRect = CGRect(
            x: presenter.view.bounds.midX,
            y: presenter.view.bounds.midY,
            width: 1,
            height: 1
        )
        presenter.present(activity, animated: true)
    }

    func importFile(completion: @escaping (String?, String?) -> Void) {
        guard let presenter = IOSWebLoginOutput.topViewController() else {
            completion(nil, nil)
            return
        }
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: [.plainText, .data],
            asCopy: true
        )
        let delegate = IOSLocalPlaylistDocumentPickerDelegate(completion: { [weak self] fileName, content in
            self?.localPlaylistPickerDelegate = nil
            completion(fileName, content)
        })
        localPlaylistPickerDelegate = delegate
        picker.delegate = delegate
        presenter.present(picker, animated: true)
    }

    func exportFile(fileName: String, content: String) {
        guard let presenter = IOSWebLoginOutput.topViewController(),
              let file = temporaryLocalPlaylistFile(fileName: fileName, content: content) else { return }
        let picker = UIDocumentPickerViewController(forExporting: [file])
        presenter.present(picker, animated: true)
    }

    func shareFile(fileName: String, content: String) {
        guard let presenter = IOSWebLoginOutput.topViewController(),
              let file = temporaryLocalPlaylistFile(fileName: fileName, content: content) else { return }
        let activity = UIActivityViewController(activityItems: [file], applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = presenter.view
        activity.popoverPresentationController?.sourceRect = CGRect(
            x: presenter.view.bounds.midX,
            y: presenter.view.bounds.midY,
            width: 1,
            height: 1
        )
        presenter.present(activity, animated: true)
    }

    private func temporaryLocalPlaylistFile(fileName: String, content: String) -> URL? {
        let safeName = URL(fileURLWithPath: fileName).lastPathComponent
        let target = FileManager.default.temporaryDirectory
            .appendingPathComponent(safeName.isEmpty ? "playlist.fuo" : safeName)
        do {
            try? FileManager.default.removeItem(at: target)
            try content.write(to: target, atomically: true, encoding: .utf8)
            return target
        } catch {
            return nil
        }
    }
}

final class IOSNetworkStatusOutput: NSObject, IosNetworkStatusOutput {
    static let shared = IOSNetworkStatusOutput()

    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "org.feeluown.mobile.network-status")
    private let lock = NSLock()
    private var cellularConnection = false

    override init() {
        super.init()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock()
            self.cellularConnection = path.usesInterfaceType(.cellular)
            self.lock.unlock()
        }
        monitor.start(queue: monitorQueue)
    }

    func isCellularConnection() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return cellularConnection
    }
}

private final class FuoWebLoginViewController: UIViewController, WKNavigationDelegate {
    private let webView = WKWebView(frame: .zero)
    private let requiredCookieGroups: [[String]]
    private let completion: (String?) -> Void

    init(
        providerName: String,
        url: URL,
        requiredCookieGroups: [[String]],
        completion: @escaping (String?) -> Void
    ) {
        self.requiredCookieGroups = requiredCookieGroups
        self.completion = completion
        super.init(nibName: nil, bundle: nil)
        title = providerName
        webView.navigationDelegate = self
        webView.load(URLRequest(url: url))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        view = webView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(cancel)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done,
            target: self,
            action: #selector(done)
        )
    }

    @objc private func cancel() {
        dismiss(animated: true) { self.completion(nil) }
    }

    @objc private func done() {
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
            var values: [String: String] = [:]
            for cookie in cookies where !cookie.value.isEmpty || values[cookie.name] == nil {
                values[cookie.name] = cookie.value
            }
            let valid = self.requiredCookieGroups.isEmpty || self.requiredCookieGroups.contains { group in
                group.allSatisfy { !(values[$0] ?? "").isEmpty }
            }
            guard valid,
                  let data = try? JSONSerialization.data(withJSONObject: values),
                  let json = String(data: data, encoding: .utf8)
            else {
                return
            }
            DispatchQueue.main.async {
                self.dismiss(animated: true) { self.completion(json) }
            }
        }
    }
}
