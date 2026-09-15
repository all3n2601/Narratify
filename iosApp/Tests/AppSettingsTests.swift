import XCTest
@testable import Narratify

@MainActor
final class AppSettingsTests: XCTestCase {
    func testPreferencesPersistAndResetWithoutACloudAccount() {
        let suite = "NarratifyTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let settings = AppSettings(defaults: defaults)
        settings.readingTheme = .black
        settings.fontSize = 26
        settings.speechRate = 1.35
        settings.compactLibrary = true
        settings.readerOutlineOpen = true
        settings.preferredVoiceID = "test.voice"

        let restored = AppSettings(defaults: defaults)
        XCTAssertEqual(restored.readingTheme, .black)
        XCTAssertEqual(restored.fontSize, 26)
        XCTAssertTrue(restored.readerOutlineOpen)
        XCTAssertEqual(restored.speechRate, 1.35, accuracy: 0.001)
        XCTAssertTrue(restored.compactLibrary)
        XCTAssertEqual(restored.preferredVoiceID, "test.voice")

        restored.reset()
        XCTAssertEqual(restored.readingTheme, .sepia)
        XCTAssertEqual(restored.fontSize, 17)
        XCTAssertNil(restored.preferredVoiceID)
    }
}
