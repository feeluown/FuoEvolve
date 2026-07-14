import AVFoundation
import AVKit
import AudioToolbox
import CoreMedia
import MediaToolbox
import MediaPlayer
import Network
import Shared
import WebKit

final class IOSNativeAudioEngine: NSObject, NativeAudioEngine, IosAudioOutput {
    static let shared = IOSNativeAudioEngine()
    private let player = AVPlayer()
    private var currentPayload: PlaybackPayload?
    private var didReachEnd = false
    private var playbackError: String?
    private var endObserver: NSObjectProtocol?
    private let spectrumAnalyzer = AudioTapSpectrumAnalyzer()

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
        }
    }

    deinit {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
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
        player.replaceCurrentItem(with: playerItem(asset: asset))
        updateNowPlaying(payload: payload)
        player.play()
    }

    func play(url: String, headers: [String: String], title: String, artists: String, album: String) {
        play(PlaybackPayload(url: url, title: title, artists: artists, album: album, source: "", headers: headers, coverUrl: nil))
    }

    func pause() {
        player.pause()
    }

    func resume() {
        player.play()
    }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        didReachEnd = false
        playbackError = nil
        spectrumAnalyzer.clear()
    }

    func seekTo(positionMs: Int64) {
        didReachEnd = false
        player.seek(to: CMTime(value: positionMs, timescale: 1000))
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
            averageBitrate: averageBitrate.map { KotlinLong(long: $0) },
            peakBitrate: nil
        )
    }

    func spectrumLevels() -> [KotlinFloat] {
        spectrumAnalyzer.levels().map { KotlinFloat(float: $0) }
    }

    private func playerItem(asset: AVURLAsset) -> AVPlayerItem {
        let item = AVPlayerItem(asset: asset)
        guard let audioTrack = asset.tracks(withMediaType: .audio).first, let tap = makeAudioTap() else {
            spectrumAnalyzer.clear()
            return item
        }
        let parameters = AVMutableAudioMixInputParameters(track: audioTrack)
        parameters.audioTapProcessor = tap
        let mix = AVMutableAudioMix()
        mix.inputParameters = [parameters]
        item.audioMix = mix
        return item
    }

    private func makeAudioTap() -> MTAudioProcessingTap? {
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: Unmanaged.passUnretained(spectrumAnalyzer).toOpaque(),
            init: { _, clientInfo, storageOut in
                storageOut.pointee = clientInfo
            },
            finalize: { _ in },
            prepare: { tap, _, processingFormat in
                let analyzer = Unmanaged<AudioTapSpectrumAnalyzer>
                    .fromOpaque(MTAudioProcessingTapGetStorage(tap))
                    .takeUnretainedValue()
                analyzer.prepare(processingFormat.pointee)
            },
            unprepare: { _ in },
            process: { tap, frameCount, _, bufferList, framesOut, flagsOut in
                let status = MTAudioProcessingTapGetSourceAudio(
                    tap,
                    frameCount,
                    bufferList,
                    flagsOut,
                    nil,
                    framesOut,
                )
                guard status == noErr else { return }
                let analyzer = Unmanaged<AudioTapSpectrumAnalyzer>
                    .fromOpaque(MTAudioProcessingTapGetStorage(tap))
                    .takeUnretainedValue()
                analyzer.consume(bufferList)
            },
        )
        var tap: Unmanaged<MTAudioProcessingTap>?
        guard MTAudioProcessingTapCreate(
            kCFAllocatorDefault,
            &callbacks,
            kMTAudioProcessingTapCreationFlag_PostEffects,
            &tap,
        ) == noErr else {
            return nil
        }
        return tap?.takeRetainedValue()
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
    }

    private func updateNowPlaying(payload: PlaybackPayload) {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: payload.title,
            MPMediaItemPropertyArtist: payload.artists,
            MPMediaItemPropertyAlbumTitle: payload.album,
        ]
    }
}

private final class AudioTapSpectrumAnalyzer {
    private static let windowSize = 512
    private static let bandCenters: [Double] = [60, 120, 250, 500, 1_000, 2_000, 3_500, 5_000, 7_000, 9_000, 12_000, 15_000]
    private let lock = NSLock()
    private var sampleRate = 0.0
    private var isFloat = true
    private var samples = Array(repeating: Float.zero, count: windowSize)
    private var sampleIndex = 0
    private var lastPublishedAt = Date.distantPast
    private var currentLevels: [Float] = []

    func prepare(_ format: AudioStreamBasicDescription) {
        lock.lock()
        sampleRate = format.mSampleRate
        isFloat = format.mFormatFlags & kAudioFormatFlagIsFloat != 0
        clearLocked()
        lock.unlock()
    }

    func consume(_ bufferList: UnsafeMutablePointer<AudioBufferList>) {
        let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
        guard let buffer = buffers.first, let data = buffer.mData else { return }
        let sampleCount = Int(buffer.mDataByteSize) / (isFloat ? MemoryLayout<Float>.size : MemoryLayout<Int16>.size)
        lock.lock()
        if isFloat {
            let pointer = data.assumingMemoryBound(to: Float.self)
            for index in 0..<sampleCount { append(pointer[index]) }
        } else {
            let pointer = data.assumingMemoryBound(to: Int16.self)
            for index in 0..<sampleCount { append(Float(pointer[index]) / 32768) }
        }
        lock.unlock()
    }

    func levels() -> [Float] {
        lock.lock()
        defer { lock.unlock() }
        return currentLevels
    }

    func clear() {
        lock.lock()
        clearLocked()
        lock.unlock()
    }

    private func append(_ sample: Float) {
        samples[sampleIndex] = sample
        sampleIndex += 1
        guard sampleIndex == Self.windowSize else { return }
        sampleIndex = 0
        guard Date().timeIntervalSince(lastPublishedAt) >= 0.05, sampleRate > 0 else { return }
        lastPublishedAt = Date()
        let nyquist = sampleRate / 2
        currentLevels = Self.bandCenters.map { frequency in
            guard frequency < nyquist else { return 0 }
            let normalized = goertzelMagnitude(frequency) * 18
            return min(1, max(0, log(1 + normalized) / log(19)))
        }.map(Swift.Float.init)
    }

    private func goertzelMagnitude(_ frequency: Double) -> Double {
        let omega = 2 * Double.pi * frequency / sampleRate
        let coefficient = 2 * cos(omega)
        var previous = 0.0
        var previousPrevious = 0.0
        for (index, sample) in samples.enumerated() {
            let hann = 0.5 - 0.5 * cos(2 * Double.pi * Double(index) / Double(Self.windowSize - 1))
            let current = Double(sample) * hann + coefficient * previous - previousPrevious
            previousPrevious = previous
            previous = current
        }
        let power = previousPrevious * previousPrevious + previous * previous - coefficient * previous * previousPrevious
        return sqrt(max(0, power)) / Double(Self.windowSize)
    }

    private func clearLocked() {
        samples = Array(repeating: 0, count: Self.windowSize)
        sampleIndex = 0
        lastPublishedAt = .distantPast
        currentLevels = []
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

final class IOSDownloadOutput: NSObject, IosDownloadOutput {
    static let shared = IOSDownloadOutput()

    func download(
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
        URLSession.shared.downloadTask(with: request) { temporaryURL, _, error in
            if let error {
                DispatchQueue.main.async { completionHandler(nil, error.localizedDescription) }
                return
            }
            guard let temporaryURL else {
                DispatchQueue.main.async { completionHandler(nil, "下载文件为空") }
                return
            }
            do {
                let directory = try self.downloadDirectory()
                let target = directory.appendingPathComponent(fileName)
                if FileManager.default.fileExists(atPath: target.path) {
                    try FileManager.default.removeItem(at: target)
                }
                try FileManager.default.moveItem(at: temporaryURL, to: target)
                if let lyrics, !lyrics.isEmpty {
                    let lyricsURL = target.deletingPathExtension().appendingPathExtension("lrc")
                    try? lyrics.write(to: lyricsURL, atomically: true, encoding: .utf8)
                }
                DispatchQueue.main.async { completionHandler(target.absoluteString, nil) }
            } catch {
                DispatchQueue.main.async { completionHandler(nil, error.localizedDescription) }
            }
        }.resume()
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

final class IOSShareOutput: NSObject, IosShareOutput {
    static let shared = IOSShareOutput()

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
