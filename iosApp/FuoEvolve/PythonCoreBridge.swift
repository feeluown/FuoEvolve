import Foundation

final class PythonCoreBridge: FuoCoreBridge {
    func initialize() async throws {
        // The shared Compose app now owns provider calls through IosFuoCoreBridge.
        // This legacy SwiftUI bridge is kept for local experiments until the
        // Python.xcframework cinterop is wired into shared/src/iosMain.
    }

    func search(keyword: String) async throws -> [FuoTrack] {
        throw BridgeError.pythonRuntimeNotLinked
    }

    func play(trackId: String) async throws -> PlaybackPayload {
        throw BridgeError.pythonRuntimeNotLinked
    }

    func next() async throws -> PlaybackPayload? {
        throw BridgeError.pythonRuntimeNotLinked
    }

    func previous() async throws -> PlaybackPayload? {
        throw BridgeError.pythonRuntimeNotLinked
    }
}

enum BridgeError: LocalizedError {
    case pythonRuntimeNotLinked

    var errorDescription: String? {
        "Python Apple Support is not linked yet"
    }
}
