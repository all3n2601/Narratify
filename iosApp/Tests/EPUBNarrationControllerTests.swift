import ReadiumNavigator
import XCTest
@testable import Narratify

final class EPUBNarrationControllerTests: XCTestCase {
    @MainActor
    func testEPUBNarrationStartsAndPausesWithSystemVoice() async throws {
        let fixture = try XCTUnwrap(
            Bundle(for: Self.self).url(
                forResource: "narratify-smoke", withExtension: "epub", subdirectory: "epub"
            ),
            "EPUB smoke fixture is not bundled with the test target"
        )

        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("NarratifyNarrationTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let store = LibraryStore(storageRoot: root)
        let book = try await store.importDocument(from: fixture)
        let publication = try await store.openEPUB(for: book)
        let navigator = try EPUBNavigatorViewController(publication: publication, initialLocation: nil)
        let controller = EPUBNarrationController()
        controller.configure(publication: publication, navigator: navigator, preferredVoiceID: nil, rateMultiplier: 1)

        XCTAssertNil(controller.errorMessage)
        controller.toggle()
        for _ in 0..<50 {
            if controller.playbackState == .playing { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(controller.playbackState, .playing)

        controller.toggle()
        for _ in 0..<20 {
            if controller.playbackState == .paused { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(controller.playbackState, .paused)
        controller.stop()
        XCTAssertEqual(controller.playbackState, .stopped)
    }
}
