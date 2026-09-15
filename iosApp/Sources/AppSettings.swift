import SwiftUI

enum AppSection: String, CaseIterable, Identifiable {
    case library
    case discover
    case voices
    case settings

    var id: Self { self }
    var title: String { rawValue.capitalized }
    var icon: String {
        switch self {
        case .library: "books.vertical.fill"
        case .discover: "magnifyingglass"
        case .voices: "waveform"
        case .settings: "gearshape"
        }
    }
}

enum ReadingTheme: String, CaseIterable, Identifiable {
    case light
    case sepia
    case dark
    case black

    var id: Self { self }
    var label: String { self == .black ? "True Black" : rawValue.capitalized }
    var background: Color {
        switch self {
        case .light: .white
        case .sepia: Palette.canvas
        case .dark: Color(red: 0.14, green: 0.13, blue: 0.16)
        case .black: .black
        }
    }
    var text: Color {
        switch self {
        case .light, .sepia: Palette.ink
        case .dark, .black: Color(red: 0.94, green: 0.92, blue: 0.96)
        }
    }
}

@MainActor
final class AppSettings: ObservableObject {
    @Published var readingTheme: ReadingTheme { didSet { defaults.set(readingTheme.rawValue, forKey: Keys.theme) } }
    @Published var fontSize: Double { didSet { defaults.set(fontSize, forKey: Keys.fontSize) } }
    @Published var lineSpacing: Double { didSet { defaults.set(lineSpacing, forKey: Keys.lineSpacing) } }
    @Published var speechRate: Double { didSet { defaults.set(speechRate, forKey: Keys.speechRate) } }
    @Published var highlightWords: Bool { didSet { defaults.set(highlightWords, forKey: Keys.highlightWords) } }
    @Published var keepScreenAwake: Bool { didSet { defaults.set(keepScreenAwake, forKey: Keys.keepScreenAwake) } }
    @Published var compactLibrary: Bool { didSet { defaults.set(compactLibrary, forKey: Keys.compactLibrary) } }
    /// Whether the landscape reader keeps its outline column open. Portrait opens the outline as
    /// a sheet on demand and deliberately does not read this.
    @Published var readerOutlineOpen: Bool { didSet { defaults.set(readerOutlineOpen, forKey: Keys.readerOutlineOpen) } }
    @Published var newestFirst: Bool { didSet { defaults.set(newestFirst, forKey: Keys.newestFirst) } }
    @Published var preferredVoiceID: String? {
        didSet {
            if let preferredVoiceID { defaults.set(preferredVoiceID, forKey: Keys.preferredVoiceID) }
            else { defaults.removeObject(forKey: Keys.preferredVoiceID) }
        }
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        readingTheme = ReadingTheme(rawValue: defaults.string(forKey: Keys.theme) ?? "") ?? .sepia
        fontSize = defaults.object(forKey: Keys.fontSize) == nil ? 17 : defaults.double(forKey: Keys.fontSize).clamped(to: 15...32)
        lineSpacing = defaults.object(forKey: Keys.lineSpacing) == nil ? 8 : defaults.double(forKey: Keys.lineSpacing).clamped(to: 2...18)
        speechRate = defaults.object(forKey: Keys.speechRate) == nil ? 1 : defaults.double(forKey: Keys.speechRate).clamped(to: 0.5...2)
        highlightWords = defaults.object(forKey: Keys.highlightWords) == nil ? true : defaults.bool(forKey: Keys.highlightWords)
        keepScreenAwake = defaults.bool(forKey: Keys.keepScreenAwake)
        compactLibrary = defaults.bool(forKey: Keys.compactLibrary)
        readerOutlineOpen = defaults.bool(forKey: Keys.readerOutlineOpen)
        newestFirst = defaults.object(forKey: Keys.newestFirst) == nil ? true : defaults.bool(forKey: Keys.newestFirst)
        preferredVoiceID = defaults.string(forKey: Keys.preferredVoiceID)
    }

    func reset() {
        readingTheme = .sepia
        fontSize = 17
        lineSpacing = 8
        speechRate = 1
        highlightWords = true
        keepScreenAwake = false
        compactLibrary = false
        readerOutlineOpen = false
        newestFirst = true
        preferredVoiceID = nil
    }

    private enum Keys {
        static let theme = "settings.readerTheme"
        static let fontSize = "settings.fontSize"
        static let lineSpacing = "settings.lineSpacing"
        static let speechRate = "settings.speechRate"
        static let highlightWords = "settings.highlightWords"
        static let keepScreenAwake = "settings.keepScreenAwake"
        static let compactLibrary = "settings.compactLibrary"
        static let readerOutlineOpen = "settings.readerOutlineOpen"
        static let newestFirst = "settings.newestFirst"
        static let preferredVoiceID = "settings.preferredVoiceID"
    }
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double { min(max(self, range.lowerBound), range.upperBound) }
}
