import AVFoundation
import SwiftUI

struct AppRootScreen: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var section: AppSection = .library

    var body: some View {
        Group {
            if sizeClass == .regular {
                HStack(spacing: 0) {
                    Sidebar(selection: $section).frame(width: 112)
                    destination
                }
            } else {
                VStack(spacing: 0) {
                    destination
                    BottomBar(selection: $section)
                }
            }
        }
        .background(Backdrop().ignoresSafeArea())
        .animation(.easeInOut(duration: 0.18), value: section)
    }

    @ViewBuilder private var destination: some View {
        switch section {
        case .library: LibraryScreen()
        case .discover: CatalogScreen()
        case .voices: VoicesScreen()
        case .settings: SettingsScreen()
        }
    }
}

struct VoicesScreen: View {
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var previewer = VoicePreviewer()
    @StateObject private var neuralPacks = NeuralVoicePackLibrary()

    private var voices: [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices().sorted {
            if $0.language.hasPrefix(Locale.current.language.languageCode?.identifier ?? "en") != $1.language.hasPrefix(Locale.current.language.languageCode?.identifier ?? "en") {
                return $0.language.hasPrefix(Locale.current.language.languageCode?.identifier ?? "en")
            }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private var selectedVoiceID: String? {
        settings.preferredVoiceID ?? AVSpeechSynthesisVoice(language: Locale.current.identifier)?.identifier
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                PageHeader(eyebrow: "VOICE STUDIO", title: "Offline voices", detail: "Choose and preview a voice installed on this device. Narration remains private and works without an account.")
                NeuralVoicePackCard(library: neuralPacks)

                Text("INSTALLED VOICES")
                    .font(.caption.weight(.bold)).tracking(1.6).foregroundStyle(Palette.violet)
                ForEach(voices, id: \.identifier) { voice in
                    Button {
                        settings.preferredVoiceID = voice.identifier
                        previewer.speak(voice: voice, rate: settings.speechRate)
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: selectedVoiceID == voice.identifier ? "checkmark.circle.fill" : "circle")
                                .font(.title2).foregroundStyle(Palette.violet)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(voice.name).font(.headline).foregroundStyle(Palette.ink)
                                Text("\(Locale.current.localizedString(forIdentifier: voice.language) ?? voice.language) · \(qualityLabel(voice.quality))")
                                    .font(.subheadline).foregroundStyle(Palette.muted)
                            }
                            Spacer()
                            Image(systemName: "play.fill").foregroundStyle(Palette.violet)
                        }
                        .settingsCard()
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Selects and previews this voice")
                }
            }
            .padding(.horizontal, 22).padding(.vertical, 24)
            .frame(maxWidth: 820).frame(maxWidth: .infinity)
        }
        .scrollIndicators(.hidden)
    }

    private func qualityLabel(_ quality: AVSpeechSynthesisVoiceQuality) -> String {
        quality == .enhanced ? "Enhanced" : (quality == .premium ? "Premium" : "Installed")
    }
}

private struct NeuralVoicePackCard: View {
    @ObservedObject var library: NeuralVoicePackLibrary
    @State private var confirmsDownload = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(library.entry.displayName).font(.headline)
                    Text(library.entry.detail).font(.subheadline).foregroundStyle(Palette.muted)
                }
                Spacer()
                Image(systemName: statusIcon).foregroundStyle(Palette.violet)
            }

            Text(statusText)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(isFailure ? Color.red : Palette.violet)

            Button(actionTitle, action: performAction)
                .buttonStyle(.borderedProminent)
                .disabled(library.state == .checking || library.state == .locked)

            Text("The download contains model data only. Narratify verifies its approved size and SHA-256 before activation.")
                .font(.caption).foregroundStyle(Palette.muted)
        }
        .settingsCard()
        .confirmationDialog(
            "Download \(library.entry.displayName)?",
            isPresented: $confirmsDownload,
            titleVisibility: .visible
        ) {
            Button("Download \(formattedSize)") { library.install() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Wi-Fi is recommended. After installation, narration remains on this device and works offline.")
        }
    }

    private var statusText: String {
        switch library.state {
        case .checking: "Checking installed packs…"
        case .locked: "Awaiting licensing approval"
        case .available: "\(formattedSize) download · Wi-Fi recommended"
        case .downloading: "Downloading and verifying…"
        case .installed: "Downloaded · available offline"
        case let .failed(message): message
        }
    }

    private var actionTitle: String {
        switch library.state {
        case .checking: "Checking…"
        case .locked: "Download unavailable"
        case .available, .failed: "Download"
        case .downloading: "Cancel download"
        case .installed: "Remove voice pack"
        }
    }

    private var statusIcon: String {
        switch library.state {
        case .installed: "checkmark.seal.fill"
        case .downloading: "arrow.down.circle.fill"
        case .locked: "lock.fill"
        case .failed: "exclamationmark.triangle.fill"
        default: "waveform.circle.fill"
        }
    }

    private var isFailure: Bool {
        if case .failed = library.state { return true }
        return false
    }

    private var formattedSize: String {
        ByteCountFormatter.string(fromByteCount: library.entry.sizeBytes, countStyle: .file)
    }

    private func performAction() {
        switch library.state {
        case .available, .failed: confirmsDownload = true
        case .downloading: library.cancel()
        case .installed: library.remove()
        case .checking, .locked: break
        }
    }
}

@MainActor
private final class VoicePreviewer: ObservableObject {
    private let synthesizer = AVSpeechSynthesizer()

    func speak(voice: AVSpeechSynthesisVoice, rate: Double) {
        synthesizer.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: "Narratify reads privately, right on your device.")
        utterance.voice = voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * Float(rate)
        synthesizer.speak(utterance)
    }
}

struct SettingsScreen: View {
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                PageHeader(eyebrow: "PREFERENCES", title: "Settings", detail: "Tune reading, narration, and library behavior. Changes are stored only on this device.")

                SettingsSection("READING") {
                    Picker("Reading theme", selection: $settings.readingTheme) {
                        ForEach(ReadingTheme.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Divider()
                    ValueSlider(title: "Text size", value: $settings.fontSize, range: 15...32, suffix: " pt")
                    Divider()
                    ValueSlider(title: "Line spacing", value: $settings.lineSpacing, range: 2...18, suffix: " pt")
                    Divider()
                    Toggle("Keep screen awake while reading", isOn: $settings.keepScreenAwake)
                }

                SettingsSection("NARRATION") {
                    ValueSlider(title: "Default speed", value: $settings.speechRate, range: 0.5...2, suffix: "×", precision: 2)
                    Divider()
                    Toggle("Highlight spoken words", isOn: $settings.highlightWords)
                }

                SettingsSection("LIBRARY") {
                    Toggle("Compact bookshelf", isOn: $settings.compactLibrary)
                    Divider()
                    Toggle("Newest imports first", isOn: $settings.newestFirst)
                }

                SettingsSection("PRIVACY & STORAGE") {
                    Label("Local by design", systemImage: "lock.fill").font(.headline)
                    Text("Books, positions, preferences, and voice choices stay in Narratify's private storage. No account or cloud sync is used.")
                        .font(.subheadline).foregroundStyle(Palette.muted)
                }

                Button("Restore Default Settings", role: .destructive) { settings.reset() }
                    .frame(maxWidth: .infinity).padding(.vertical, 10)
                Text("Narratify 0.1 · private reader")
                    .font(.caption).foregroundStyle(Palette.muted).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 22).padding(.vertical, 24)
            .frame(maxWidth: 820).frame(maxWidth: .infinity)
        }
        .scrollIndicators(.hidden)
    }
}

struct ReaderQuickSettings: View {
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        NavigationStack {
            Form {
                Picker("Theme", selection: $settings.readingTheme) {
                    ForEach(ReadingTheme.allCases) { Text($0.label).tag($0) }
                }
                ValueSlider(title: "Text size", value: $settings.fontSize, range: 15...32, suffix: " pt")
                ValueSlider(title: "Line spacing", value: $settings.lineSpacing, range: 2...18, suffix: " pt")
                Toggle("Keep screen awake", isOn: $settings.keepScreenAwake)
            }
            .navigationTitle("Reading Settings")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}

struct PageHeader: View {
    let eyebrow: String
    let title: String
    let detail: String
    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 8) {
                BrandMark(size: 32)
                Text(eyebrow).font(.caption.weight(.heavy)).tracking(2).foregroundStyle(Palette.violet)
            }
            Text(title).font(.system(.largeTitle, design: .rounded, weight: .bold)).foregroundStyle(Palette.ink)
            Text(detail).foregroundStyle(Palette.muted)
        }
    }
}

private struct SettingsSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    init(_ title: String, @ViewBuilder content: () -> Content) { self.title = title; self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.caption.weight(.bold)).tracking(1.5).foregroundStyle(Palette.violet)
            VStack(alignment: .leading, spacing: 13) { content }.settingsCard()
        }
    }
}

private struct ValueSlider: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let suffix: String
    var precision = 0
    var body: some View {
        VStack(spacing: 8) {
            HStack { Text(title); Spacer(); Text("\(value, specifier: precision == 0 ? "%.0f" : "%.2f")\(suffix)").foregroundStyle(Palette.violet).monospacedDigit() }
            Slider(value: $value, in: range)
        }
    }
}

private extension View {
    func settingsCard() -> some View {
        padding(18)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(Palette.violet.opacity(0.1)))
    }
}
