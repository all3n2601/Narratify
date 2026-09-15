import AVFoundation
import ReadiumNavigator
import ReadiumShared
import SwiftUI

@MainActor
final class EPUBNarrationController: NSObject, ObservableObject {
    enum PlaybackState: Equatable {
        case stopped
        case playing
        case paused
    }

    @Published private(set) var playbackState: PlaybackState = .stopped
    @Published private(set) var errorMessage: String?
    var rateMultiplier: Double = 1 {
        didSet { engineDelegate?.rateMultiplier = rateMultiplier }
    }

    private var synthesizer: PublicationSpeechSynthesizer?
    private var navigator: Navigator?
    private var engineDelegate: NarrationEngineDelegate?

    var buttonLabel: String {
        switch playbackState {
        case .stopped: "Read aloud"
        case .playing: "Pause narration"
        case .paused: "Resume narration"
        }
    }

    var buttonIcon: String {
        playbackState == .playing ? "pause.fill" : "speaker.wave.2.fill"
    }

    func configure(
        publication: Publication,
        navigator: Navigator,
        preferredVoiceID: String?,
        rateMultiplier: Double
    ) {
        stop()
        self.navigator = navigator
        self.rateMultiplier = rateMultiplier
        let engineDelegate = NarrationEngineDelegate(rateMultiplier: rateMultiplier)
        self.engineDelegate = engineDelegate
        synthesizer = PublicationSpeechSynthesizer(
            publication: publication,
            config: .init(voiceIdentifier: preferredVoiceID),
            engineFactory: makeNarrationEngineFactory(delegate: engineDelegate),
            delegate: self
        )
        if synthesizer == nil {
            errorMessage = "This EPUB does not expose readable text for narration."
        }
    }

    func toggle() {
        guard let synthesizer else {
            errorMessage = "Narration is unavailable for this EPUB."
            return
        }
        switch playbackState {
        case .stopped:
            synthesizer.start(from: navigator?.currentLocation)
        case .playing:
            playbackState = .paused
            synthesizer.pause()
        case .paused:
            playbackState = .playing
            synthesizer.resume()
        }
    }

    func stop() {
        synthesizer?.stop()
        synthesizer = nil
        navigator = nil
        engineDelegate = nil
        playbackState = .stopped
    }

    func clearError() { errorMessage = nil }
}

private final class NarrationEngineDelegate: NSObject, AVTTSEngineDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var storedRateMultiplier: Double

    init(rateMultiplier: Double) {
        storedRateMultiplier = rateMultiplier
    }

    var rateMultiplier: Double {
        get { lock.withLock { storedRateMultiplier } }
        set { lock.withLock { storedRateMultiplier = newValue } }
    }

    func avTTSEngine(_ engine: AVTTSEngine, didCreateUtterance utterance: AVSpeechUtterance) {
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * Float(rateMultiplier.clamped(to: 0.5...2))
    }
}

private func makeNarrationEngineFactory(
    delegate: NarrationEngineDelegate
) -> PublicationSpeechSynthesizer.EngineFactory {
    { AVTTSEngine(delegate: delegate) }
}

@MainActor
extension EPUBNarrationController: PublicationSpeechSynthesizerDelegate {
    func publicationSpeechSynthesizer(
        _ synthesizer: PublicationSpeechSynthesizer,
        stateDidChange state: PublicationSpeechSynthesizer.State
    ) {
        let locator: Locator?
        switch state {
        case .stopped:
            playbackState = .stopped
            locator = nil
        case let .paused(utterance):
            playbackState = .paused
            locator = utterance.locator
        case let .playing(utterance, range):
            playbackState = .playing
            locator = range ?? utterance.locator
        }
        guard let locator else { return }
        if let decorable = navigator as? DecorableNavigator {
            decorable.apply(
                decorations: [Decoration(id: "narratify-tts", locator: locator, style: .highlight(tint: .systemYellow))],
                in: "narratify-tts"
            )
        }
    }

    func publicationSpeechSynthesizer(
        _ synthesizer: PublicationSpeechSynthesizer,
        utterance: PublicationSpeechSynthesizer.Utterance,
        didFailWithError error: PublicationSpeechSynthesizer.Error
    ) {
        playbackState = .paused
        errorMessage = "The selected on-device voice could not read this passage."
    }
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
