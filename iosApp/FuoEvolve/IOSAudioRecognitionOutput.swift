import AVFoundation
import Foundation
import Shared
import UIKit
import WebKit

final class IOSAudioRecognitionOutput: NSObject, IosAudioRecognitionOutput {
    static let shared = IOSAudioRecognitionOutput()

    private let audioEngine = AVAudioEngine()
    private let workQueue = DispatchQueue(label: "org.feeluown.mobile.audio-recognition")
    private let fingerprintRuntime = IOSFingerprintRuntime()
    private var sourceSamples: [Float] = []
    private var sourceSampleRate = 48_000.0
    private var latestWindow: [Float]?
    private var matching = false
    private var attempt = 1
    private var eventHandler: ((String, String, String) -> Void)?
    private var completionHandler: ((String?, String?) -> Void)?
    private var active = false
    private var sessionId: String?
    private var activeRequest: URLSessionDataTask?

    override init() {
        super.init()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppBackground),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
    }

    func hasPermission() -> Bool {
        AVAudioSession.sharedInstance().recordPermission == .granted
    }

    func requestPermission(completionHandler: @escaping @Sendable (KotlinBoolean) -> Void) {
        if hasPermission() {
            completionHandler(KotlinBoolean(bool: true))
            return
        }
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            DispatchQueue.main.async {
                completionHandler(KotlinBoolean(bool: granted))
            }
        }
    }

    func recognize(
        eventHandler: @escaping (String, String, String) -> Void,
        completionHandler: @escaping (String?, String?) -> Void
    ) {
        guard hasPermission() else {
            completionHandler(nil, "需要麦克风权限")
            return
        }
        cancel()
        workQueue.sync {
            self.eventHandler = eventHandler
            self.completionHandler = completionHandler
            active = true
            sessionId = UUID().uuidString
            attempt = 1
            sourceSamples.removeAll(keepingCapacity: true)
            latestWindow = nil
            matching = false
        }

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: [])
            try session.setActive(true, options: [])
            let input = audioEngine.inputNode
            let inputFormat = input.outputFormat(forBus: 0)
            guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
                throw RecognitionError.message("麦克风初始化失败")
            }
            sourceSampleRate = inputFormat.sampleRate
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 2_048, format: inputFormat) { [weak self] buffer, _ in
                guard
                    let self,
                    let channel = buffer.floatChannelData?[0]
                else { return }
                let copied = Array(
                    UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength))
                )
                self.workQueue.async {
                    self.appendSamples(copied)
                }
            }
            audioEngine.prepare()
            try audioEngine.start()
            emit("capturing", attempt: 1, capturedMs: 0)
        } catch {
            let message = readableError(error)
            workQueue.async {
                self.finish(error: message)
            }
        }
    }

    func cancel() {
        workQueue.sync {
            active = false
            sessionId = nil
            sourceSamples.removeAll(keepingCapacity: false)
            latestWindow = nil
            matching = false
            activeRequest?.cancel()
            activeRequest = nil
        }
        stopAudio()
        eventHandler = nil
        completionHandler = nil
    }

    private func appendSamples(_ samples: [Float]) {
        guard active else { return }
        sourceSamples.append(contentsOf: samples)
        let required = max(1, Int(sourceSampleRate * 6.0))
        let capturedMs = min(6_000, Int64(Double(sourceSamples.count) * 1_000 / sourceSampleRate))
        if !matching {
            emit("capturing", attempt: attempt, capturedMs: capturedMs)
        }
        while sourceSamples.count >= required {
            let sourceWindow = Array(sourceSamples.prefix(required))
            sourceSamples.removeFirst(required)
            let fingerprintSamples = downsampleForFingerprint(sourceWindow, sourceRate: sourceSampleRate)
            if matching {
                latestWindow = fingerprintSamples
            } else {
                startMatching(fingerprintSamples)
            }
        }
    }

    private func startMatching(_ samples: [Float]) {
        guard active else { return }
        matching = true
        guard let recognitionSessionId = sessionId else { return }
        let currentAttempt = attempt
        emit("matching", attempt: currentAttempt, capturedMs: 6_000)
        fingerprintRuntime.generate(samples: samples) { [weak self] result in
            self?.workQueue.async {
                guard
                    let self,
                    self.active,
                    self.sessionId == recognitionSessionId
                else { return }
                switch result {
                case .success(let fingerprint):
                    self.requestMatches(
                        sessionId: recognitionSessionId,
                        fingerprint: fingerprint
                    ) { requestResult in
                        self.workQueue.async {
                            guard
                                self.active,
                                self.sessionId == recognitionSessionId
                            else { return }
                            switch requestResult {
                            case .success(let matches):
                                if matches.isEmpty {
                                    self.emit("no_match", attempt: currentAttempt, capturedMs: 0)
                                    self.attempt += 1
                                    self.matching = false
                                    if let next = self.latestWindow {
                                        self.latestWindow = nil
                                        self.startMatching(next)
                                    }
                                } else {
                                    self.finish(matches: matches)
                                }
                            case .failure(let error):
                                self.finish(error: self.readableError(error))
                            }
                        }
                    }
                case .failure(let error):
                    self.finish(error: self.readableError(error))
                }
            }
        }
    }

    private func requestMatches(
        sessionId: String,
        fingerprint: String,
        completion: @escaping (Result<[[String: Any]], Error>) -> Void
    ) {
        guard let url = URL(string: "https://interface.music.163.com/api/music/audio/match") else {
            completion(.failure(RecognitionError.message("识别接口地址无效")))
            return
        }
        let fields = [
            "sessionId": sessionId,
            "algorithmCode": "shazam_v2",
            "duration": "6",
            "rawdata": fingerprint,
            "times": "2",
            "decrypt": "1",
        ]
        let body = fields.map { key, value in
            "\(key.formEncoded())=\(value.formEncoded())"
        }.joined(separator: "&")
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.httpBody = body.data(using: .utf8)
        request.setValue(
            "application/x-www-form-urlencoded; charset=UTF-8",
            forHTTPHeaderField: "Content-Type"
        )
        request.setValue(
            "chrome-extension://pgphbbekcgpfaekhcbjamjjkegcclhhd",
            forHTTPHeaderField: "Origin"
        )
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(error))
                return
            }
            guard
                let http = response as? HTTPURLResponse,
                (200...299).contains(http.statusCode),
                let data
            else {
                completion(.failure(RecognitionError.message("识别接口请求失败")))
                return
            }
            do {
                guard
                    let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                    (root["code"] as? NSNumber)?.intValue == 200,
                    let payload = root["data"] as? [String: Any]
                else {
                    throw RecognitionError.message("识别接口返回错误")
                }
                let resultValue = payload["result"]
                let results: [[String: Any]]
                if resultValue == nil || resultValue is NSNull {
                    results = []
                } else if let values = resultValue as? [[String: Any]] {
                    results = values
                } else {
                    throw RecognitionError.message("识别接口 result 格式错误")
                }
                let matches = results.compactMap(Self.normalizedMatch)
                completion(.success(matches))
            } catch {
                completion(.failure(error))
            }
        }
        activeRequest = task
        task.resume()
    }

    private static func normalizedMatch(_ match: [String: Any]) -> [String: Any]? {
        guard
            let song = match["song"] as? [String: Any],
            let title = song["name"] as? String,
            !title.isEmpty
        else {
            return nil
        }
        let artistValues = (song["artists"] as? [[String: Any]])
            ?? (song["ar"] as? [[String: Any]])
            ?? []
        let album = (song["album"] as? [String: Any])
            ?? (song["al"] as? [String: Any])
            ?? [:]
        var result: [String: Any] = [
            "title": title,
            "artists": artistValues.compactMap { $0["name"] as? String },
            "album": album["name"] as? String ?? "",
        ]
        if let id = song["id"] {
            result["netease_song_id"] = String(describing: id)
        }
        if let cover = album["picUrl"] as? String, !cover.isEmpty {
            result["cover_url"] = cover
        }
        if let startTime = match["startTime"] as? NSNumber {
            result["match_start_time_ms"] = startTime.int64Value
        }
        return result
    }

    private func finish(matches: [[String: Any]]) {
        guard active else { return }
        active = false
        sessionId = nil
        activeRequest = nil
        let completion = completionHandler
        eventHandler = nil
        completionHandler = nil
        let json = (try? JSONSerialization.data(withJSONObject: matches))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
        DispatchQueue.main.async {
            self.stopAudio()
            completion?(json, nil)
        }
    }

    private func finish(error: String) {
        guard active else { return }
        active = false
        sessionId = nil
        activeRequest?.cancel()
        activeRequest = nil
        let completion = completionHandler
        eventHandler = nil
        completionHandler = nil
        DispatchQueue.main.async {
            self.stopAudio()
            completion?(nil, error)
        }
    }

    private func stopAudio() {
        let action = {
            self.audioEngine.inputNode.removeTap(onBus: 0)
            self.audioEngine.stop()
            try? AVAudioSession.sharedInstance().setActive(
                false,
                options: .notifyOthersOnDeactivation
            )
        }
        if Thread.isMainThread {
            action()
        } else {
            DispatchQueue.main.sync(execute: action)
        }
    }

    private func emit(_ type: String, attempt: Int, capturedMs: Int64) {
        let handler = eventHandler
        DispatchQueue.main.async {
            handler?(type, String(attempt), String(capturedMs))
        }
    }

    private func downsampleForFingerprint(_ source: [Float], sourceRate: Double) -> [Float] {
        let targetCount = 288_000
        let resampled: [Float]
        if abs(sourceRate - 48_000) < 1, source.count >= targetCount {
            resampled = Array(source.prefix(targetCount))
        } else {
            let scale = sourceRate / 48_000
            resampled = (0..<targetCount).map { index in
                let position = Double(index) * scale
                let lower = min(Int(position), source.count - 1)
                let upper = min(lower + 1, source.count - 1)
                let fraction = Float(position - Double(lower))
                return source[lower] + (source[upper] - source[lower]) * fraction
            }
        }
        return (0..<48_000).map { resampled[$0 * 6] }
    }

    private func readableError(_ error: Error) -> String {
        if let error = error as? RecognitionError {
            return error.description
        }
        return error.localizedDescription.isEmpty ? "听歌识曲失败" : error.localizedDescription
    }

    @objc private func handleInterruption(_ notification: Notification) {
        workQueue.async {
            self.finish(error: "录音被系统中断，请重试")
        }
    }

    @objc private func handleAppBackground() {
        workQueue.async {
            self.finish(error: "应用进入后台，识曲已停止")
        }
    }
}

private final class IOSFingerprintRuntime: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
    private var webView: WKWebView?
    private var ready = false
    private var waiting: [() -> Void] = []
    private var completions: [String: (Result<String, Error>) -> Void] = [:]

    func generate(
        samples: [Float],
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        DispatchQueue.main.async {
            self.ensureWebView()
            let requestId = UUID().uuidString
            self.completions[requestId] = completion
            let data = samples.withUnsafeBufferPointer { buffer in
                Data(buffer: buffer)
            }
            let base64 = data.base64EncodedString()
            let action = {
                let script = "globalThis.fuoFingerprint.generate(" +
                    "\(requestId.jsQuoted),\(base64.jsQuoted))"
                self.webView?.evaluateJavaScript(script) { _, error in
                    if let error, let completion = self.completions.removeValue(forKey: requestId) {
                        completion(.failure(error))
                    }
                }
            }
            if self.ready {
                action()
            } else {
                self.waiting.append(action)
            }
        }
    }

    private func ensureWebView() {
        guard webView == nil else { return }
        let controller = WKUserContentController()
        controller.add(self, name: "FuoFingerprintBridge")
        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        webView = view
        guard let url = Bundle.main.url(
            forResource: "runtime",
            withExtension: "html",
            subdirectory: "audio_recognition"
        ) else {
            failWaiting(RecognitionError.message("缺少音频指纹资源"))
            return
        }
        view.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        webView.evaluateJavaScript("void globalThis.fuoFingerprint.verifyRuntime()") { _, error in
            if let error {
                self.failWaiting(error)
            }
        }
    }

    func webView(
        _ webView: WKWebView,
        didFail navigation: WKNavigation!,
        withError error: Error
    ) {
        failWaiting(error)
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        failWaiting(error)
    }

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard let body = message.body as? [String: Any] else { return }
        if body["runtimeReady"] as? Bool == true {
            let error = body["error"] as? String ?? ""
            if error.isEmpty {
                ready = true
                let actions = waiting
                waiting.removeAll()
                actions.forEach { $0() }
            } else {
                failWaiting(RecognitionError.message("音频指纹运行时初始化失败：\(error)"))
            }
            return
        }
        guard
            let requestId = body["requestId"] as? String,
            let completion = completions.removeValue(forKey: requestId)
        else { return }
        let error = body["error"] as? String ?? ""
        if error.isEmpty {
            completion(.success(body["fingerprint"] as? String ?? ""))
        } else {
            completion(.failure(RecognitionError.message("音频指纹生成失败：\(error)")))
        }
    }

    private func failWaiting(_ error: Error) {
        let values = completions.values
        completions.removeAll()
        waiting.removeAll()
        values.forEach { $0(.failure(error)) }
    }
}

private enum RecognitionError: Error {
    case message(String)

    var description: String {
        switch self {
        case .message(let value):
            return value
        }
    }
}

private extension String {
    func formEncoded() -> String {
        addingPercentEncoding(
            withAllowedCharacters: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        ) ?? self
    }

    var jsQuoted: String {
        guard
            let data = try? JSONSerialization.data(withJSONObject: [self]),
            let json = String(data: data, encoding: .utf8)
        else {
            return "\"\""
        }
        return String(json.dropFirst().dropLast())
    }
}
