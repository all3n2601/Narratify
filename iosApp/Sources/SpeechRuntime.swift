import AVFoundation
import Foundation

/// The source-text coordinate system used by both the native fallback and neural engines.
/// Ranges are UTF-16 so they can be applied directly to `NSString` and `NSAttributedString`.
struct SpeechSourceRange: Codable, Equatable, Hashable, Sendable {
    let location: Int
    let length: Int

    init(location: Int, length: Int) {
        precondition(location >= 0 && length >= 0)
        self.location = location
        self.length = length
    }

    init(_ range: NSRange) {
        self.init(location: range.location, length: range.length)
    }

    var nsRange: NSRange { NSRange(location: location, length: length) }
}

struct SpeechRequest: Equatable, Sendable {
    let id: UUID
    let text: String
    let language: String
    let voiceID: String?
    let rate: Float
    let pitch: Float

    init(
        id: UUID = UUID(),
        text: String,
        language: String = "en-US",
        voiceID: String? = nil,
        rate: Float = 1,
        pitch: Float = 1
    ) {
        self.id = id
        self.text = text
        self.language = language
        self.voiceID = voiceID
        self.rate = min(max(rate, 0.5), 2)
        self.pitch = min(max(pitch, 0.5), 2)
    }
}

enum SpeechPlaybackEvent: Equatable, Sendable {
    case preparing(UUID)
    case started(UUID)
    case boundary(UUID, SpeechSourceRange)
    case paused(UUID)
    case resumed(UUID)
    case finished(UUID)
    case stopped(UUID)
    case failed(UUID, String)
}

@MainActor
protocol SpeechPlaybackRuntime: AnyObject {
    var eventHandler: (@MainActor @Sendable (SpeechPlaybackEvent) -> Void)? { get set }
    func speak(_ request: SpeechRequest)
    func pause()
    func resume()
    func stop()
}

// MARK: - Replaceable neural inference boundary

struct NeuralModelManifest: Codable, Equatable, Sendable {
    let modelID: String
    let displayName: String
    let version: String
    let licenseSPDX: String
    let commercialUseApproved: Bool
    let languages: [String]
    let sampleRate: Double
    /// App-relative asset path to lowercase SHA-256. The engine must compute these
    /// from the installed files; values copied from a pack-authored manifest are untrusted.
    let assetSHA256: [String: String]

    init(
        modelID: String,
        displayName: String,
        version: String,
        licenseSPDX: String,
        commercialUseApproved: Bool,
        languages: [String],
        sampleRate: Double,
        assetSHA256: [String: String] = [:]
    ) {
        self.modelID = modelID
        self.displayName = displayName
        self.version = version
        self.licenseSPDX = licenseSPDX
        self.commercialUseApproved = commercialUseApproved
        self.languages = languages
        self.sampleRate = sampleRate
        self.assetSHA256 = assetSHA256
    }

    func validateForDistribution() throws {
        guard commercialUseApproved else { throw NeuralTTSError.unapprovedLicense }
        guard !modelID.isEmpty, !version.isEmpty, !licenseSPDX.isEmpty else {
            throw NeuralTTSError.invalidManifest
        }
        guard sampleRate >= 8_000, sampleRate <= 96_000 else {
            throw NeuralTTSError.invalidManifest
        }
        guard !assetSHA256.isEmpty, assetSHA256.allSatisfy({ path, hash in
            !path.isEmpty && !path.hasPrefix("/") &&
                !path.split(separator: "/").contains("..") &&
                hash.count == 64 && hash.allSatisfy(\.isHexDigit)
        }) else {
            throw NeuralTTSError.invalidManifest
        }
    }
}

/// A review record owned by the app build. A downloaded manifest cannot approve itself.
struct ApprovedNeuralModel: Equatable, Sendable {
    let modelID: String
    let version: String
    let licenseSPDX: String
    let sampleRate: Double
    let assetSHA256: [String: String]

    func matches(_ manifest: NeuralModelManifest) -> Bool {
        manifest.commercialUseApproved &&
            manifest.modelID == modelID && manifest.version == version &&
            manifest.licenseSPDX == licenseSPDX && manifest.sampleRate == sampleRate &&
            !assetSHA256.isEmpty && manifest.assetSHA256 == assetSHA256
    }
}

struct NeuralWordTiming: Equatable, Sendable {
    let sourceRange: SpeechSourceRange
    /// Absolute frame offsets from the beginning of this request's synthesized audio.
    let startFrame: Int64
    let endFrame: Int64

    init(sourceRange: SpeechSourceRange, startFrame: Int64, endFrame: Int64) {
        precondition(startFrame >= 0 && endFrame >= startFrame)
        self.sourceRange = sourceRange
        self.startFrame = startFrame
        self.endFrame = endFrame
    }
}

/// One mono Float32 PCM segment. `startFrame` is absolute within the request and chunks
/// must be contiguous. Keeping AVFoundation out of this value lets an ONNX/Core ML/GGML
/// implementation synthesize on its own actor without crossing the main actor unsafely.
struct NeuralAudioChunk: Equatable, Sendable {
    let requestID: UUID
    let startFrame: Int64
    let sampleRate: Double
    let samples: [Float]
    let wordTimings: [NeuralWordTiming]
    let isFinal: Bool
}

enum NeuralTTSError: Error, Equatable, LocalizedError, Sendable {
    case modelNotInstalled
    case unapprovedLicense
    case untrustedModel
    case invalidManifest
    case emptyAudioChunk
    case discontinuousAudio(expected: Int64, actual: Int64)
    case sampleRateChanged
    case audioBufferAllocationFailed

    var errorDescription: String? {
        switch self {
        case .modelNotInstalled: "No approved offline neural voice is installed."
        case .unapprovedLicense: "This voice is not approved for commercial distribution."
        case .untrustedModel: "This neural voice does not match an app-reviewed model and checksum set."
        case .invalidManifest: "The neural voice manifest is invalid."
        case .emptyAudioChunk: "The neural engine returned an empty audio chunk."
        case let .discontinuousAudio(expected, actual):
            "The neural audio stream is discontinuous (expected frame \(expected), received \(actual))."
        case .sampleRateChanged: "The neural engine changed sample rate during an utterance."
        case .audioBufferAllocationFailed: "An audio playback buffer could not be allocated."
        }
    }
}

/// Implement this protocol in a separate adapter for the selected inference runtime.
/// The app target intentionally ships no model or third-party runtime until model quality,
/// performance, and commercial licensing pass the device benchmark gate.
protocol LocalNeuralTTSEngine: Sendable {
    func prepare() async throws -> NeuralModelManifest
    func synthesize(_ request: SpeechRequest) async -> AsyncThrowingStream<NeuralAudioChunk, Error>
    func cancel(requestID: UUID) async
}

actor UnavailableNeuralTTSEngine: LocalNeuralTTSEngine {
    func prepare() async throws -> NeuralModelManifest { throw NeuralTTSError.modelNotInstalled }

    func synthesize(_ request: SpeechRequest) async -> AsyncThrowingStream<NeuralAudioChunk, Error> {
        AsyncThrowingStream { continuation in
            continuation.finish(throwing: NeuralTTSError.modelNotInstalled)
        }
    }

    func cancel(requestID: UUID) async {}
}

/// Pure sample-clock state, separated from AVAudioEngine so boundary behavior is testable.
struct NeuralSampleTimeline: Equatable, Sendable {
    private(set) var nextFrame: Int64 = 0
    private(set) var pending: [NeuralWordTiming] = []

    mutating func append(_ chunk: NeuralAudioChunk) throws {
        guard !chunk.samples.isEmpty else { throw NeuralTTSError.emptyAudioChunk }
        guard chunk.startFrame == nextFrame else {
            throw NeuralTTSError.discontinuousAudio(expected: nextFrame, actual: chunk.startFrame)
        }
        nextFrame += Int64(chunk.samples.count)
        pending.append(contentsOf: chunk.wordTimings)
        pending.sort { $0.startFrame < $1.startFrame }
    }

    mutating func consumeBoundaries(through renderedFrame: Int64) -> [SpeechSourceRange] {
        let split = pending.firstIndex { $0.startFrame > renderedFrame } ?? pending.endIndex
        let ranges = pending[..<split].map(\.sourceRange)
        pending.removeSubrange(..<split)
        return ranges
    }
}

// MARK: - Neural audio playback

@MainActor
final class NeuralSpeechRuntime: SpeechPlaybackRuntime {
    var eventHandler: (@MainActor @Sendable (SpeechPlaybackEvent) -> Void)?

    private let neuralEngine: any LocalNeuralTTSEngine
    private let approvals: [ApprovedNeuralModel]
    private let audioEngine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let timePitch = AVAudioUnitTimePitch()
    private var synthesisTask: Task<Void, Never>?
    private var clockTimer: Timer?
    private var request: SpeechRequest?
    private var timeline = NeuralSampleTimeline()
    private var streamSampleRate: Double?
    private var didStart = false

    init(engine: any LocalNeuralTTSEngine, approvals: [ApprovedNeuralModel]) {
        precondition(!approvals.isEmpty, "A neural runtime requires an app-owned model approval")
        neuralEngine = engine
        self.approvals = approvals
        audioEngine.attach(player)
        audioEngine.attach(timePitch)
    }

    func speak(_ request: SpeechRequest) {
        stop()
        self.request = request
        timeline = NeuralSampleTimeline()
        streamSampleRate = nil
        didStart = false
        timePitch.rate = request.rate
        timePitch.pitch = 1_200 * log2(request.pitch)
        eventHandler?(.preparing(request.id))

        synthesisTask = Task { [weak self, neuralEngine] in
            do {
                let manifest = try await neuralEngine.prepare()
                try manifest.validateForDistribution()
                guard let self else { return }
                guard self.approvals.contains(where: { $0.matches(manifest) }) else {
                    throw NeuralTTSError.untrustedModel
                }
                let stream = await neuralEngine.synthesize(request)
                for try await chunk in stream {
                    try Task.checkCancellation()
                    try self.enqueue(chunk)
                }
            } catch is CancellationError {
                // `stop` owns the user-visible terminal event.
            } catch {
                guard let self, self.request?.id == request.id else { return }
                self.eventHandler?(.failed(request.id, error.localizedDescription))
                self.resetAudio()
            }
        }
    }

    func pause() {
        guard let request, player.isPlaying else { return }
        player.pause()
        eventHandler?(.paused(request.id))
    }

    func resume() {
        guard let request, !player.isPlaying else { return }
        do {
            if !audioEngine.isRunning { try audioEngine.start() }
            player.play()
            eventHandler?(.resumed(request.id))
        } catch {
            eventHandler?(.failed(request.id, error.localizedDescription))
            resetAudio()
        }
    }

    func stop() {
        guard let active = request else { return }
        synthesisTask?.cancel()
        Task { [neuralEngine] in await neuralEngine.cancel(requestID: active.id) }
        resetAudio()
        eventHandler?(.stopped(active.id))
    }

    private func enqueue(_ chunk: NeuralAudioChunk) throws {
        guard request?.id == chunk.requestID else { return }
        if let streamSampleRate, streamSampleRate != chunk.sampleRate {
            throw NeuralTTSError.sampleRateChanged
        }
        try timeline.append(chunk)

        if streamSampleRate == nil {
            streamSampleRate = chunk.sampleRate
            let format = AVAudioFormat(standardFormatWithSampleRate: chunk.sampleRate, channels: 1)!
            audioEngine.connect(player, to: timePitch, format: format)
            audioEngine.connect(timePitch, to: audioEngine.mainMixerNode, format: format)
            try configureAudioSession()
            audioEngine.prepare()
            try audioEngine.start()
        }

        let format = AVAudioFormat(standardFormatWithSampleRate: chunk.sampleRate, channels: 1)!
        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: AVAudioFrameCount(chunk.samples.count)
        ), let channel = buffer.floatChannelData?[0] else {
            throw NeuralTTSError.audioBufferAllocationFailed
        }
        buffer.frameLength = AVAudioFrameCount(chunk.samples.count)
        chunk.samples.withUnsafeBufferPointer { samples in
            channel.update(from: samples.baseAddress!, count: samples.count)
        }

        let requestID = chunk.requestID
        let isFinal = chunk.isFinal
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            guard isFinal else { return }
            Task { @MainActor in self?.finishIfCurrent(requestID) }
        }
        if !didStart {
            didStart = true
            player.play()
            startClock()
            eventHandler?(.started(requestID))
        }
    }

    private func configureAudioSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .spokenAudio, options: [.allowAirPlay, .allowBluetoothA2DP])
        try session.setActive(true)
    }

    private func startClock() {
        clockTimer?.invalidate()
        clockTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.publishBoundaries() }
        }
        clockTimer?.tolerance = 1.0 / 120.0
    }

    private func publishBoundaries() {
        guard let request,
              let nodeTime = player.lastRenderTime,
              let playerTime = player.playerTime(forNodeTime: nodeTime) else { return }
        for range in timeline.consumeBoundaries(through: playerTime.sampleTime) {
            eventHandler?(.boundary(request.id, range))
        }
    }

    private func finishIfCurrent(_ requestID: UUID) {
        guard request?.id == requestID else { return }
        publishBoundaries()
        resetAudio()
        eventHandler?(.finished(requestID))
    }

    private func resetAudio() {
        clockTimer?.invalidate()
        clockTimer = nil
        synthesisTask?.cancel()
        synthesisTask = nil
        player.stop()
        audioEngine.stop()
        audioEngine.disconnectNodeOutput(player)
        audioEngine.disconnectNodeOutput(timePitch)
        request = nil
        streamSampleRate = nil
        didStart = false
        timeline = NeuralSampleTimeline()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

// MARK: - Shipping system fallback

@MainActor
final class SystemSpeechRuntime: NSObject, SpeechPlaybackRuntime, AVSpeechSynthesizerDelegate {
    var eventHandler: (@MainActor @Sendable (SpeechPlaybackEvent) -> Void)?

    private let synthesizer = AVSpeechSynthesizer()
    private var request: SpeechRequest?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func speak(_ request: SpeechRequest) {
        stop()
        self.request = request
        let utterance = AVSpeechUtterance(string: request.text)
        utterance.voice = request.voiceID.flatMap(AVSpeechSynthesisVoice.init(identifier:))
            ?? AVSpeechSynthesisVoice(language: request.language)
        utterance.rate = min(
            AVSpeechUtteranceMaximumSpeechRate,
            max(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceDefaultSpeechRate * request.rate)
        )
        utterance.pitchMultiplier = request.pitch
        eventHandler?(.preparing(request.id))
        synthesizer.speak(utterance)
    }

    func pause() {
        guard let request, synthesizer.pauseSpeaking(at: .word) else { return }
        eventHandler?(.paused(request.id))
    }

    func resume() {
        guard let request, synthesizer.continueSpeaking() else { return }
        eventHandler?(.resumed(request.id))
    }

    func stop() {
        guard let request else { return }
        self.request = nil
        synthesizer.stopSpeaking(at: .immediate)
        eventHandler?(.stopped(request.id))
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, let request else { return }
            eventHandler?(.started(request.id))
        }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        willSpeakRangeOfSpeechString characterRange: NSRange,
        utterance: AVSpeechUtterance
    ) {
        Task { @MainActor [weak self] in
            guard let self, let request else { return }
            eventHandler?(.boundary(request.id, SpeechSourceRange(characterRange)))
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in
            guard let self, let request else { return }
            self.request = nil
            eventHandler?(.finished(request.id))
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor [weak self] in self?.request = nil }
    }
}
