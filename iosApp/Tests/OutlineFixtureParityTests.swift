import XCTest
@testable import Narratify

/// Locks the outline heuristics against the fixtures the Kotlin tests read as well. Only titles,
/// depths and order are shared: Android reports offsets into rendered text and iOS into the
/// source, so each platform asserts its own offsets. See
/// `docs/superpowers/specs/2026-09-10-reader-outline-sidebar-design.md`.
final class OutlineFixtureParityTests: XCTestCase {
    private struct ExpectedItem: Decodable {
        let title: String
        let depth: Int
    }

    func testEveryFixtureProducesTheSharedOutlineStructure() throws {
        let root = try XCTUnwrap(
            Bundle(for: Self.self).url(forResource: "outline", withExtension: nil),
            "outline fixtures are not bundled with the test target"
        )
        let documents = try FileManager.default
            .contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
            .filter { ["txt", "md"].contains($0.pathExtension) }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        XCTAssertFalse(documents.isEmpty, "no fixtures found in \(root.path)")

        for document in documents {
            let name = document.lastPathComponent
            let expectedURL = root.appendingPathComponent("expected/\(name).json")
            let expected = try JSONDecoder().decode(
                [ExpectedItem].self,
                from: Data(contentsOf: expectedURL)
            )
            let source = try String(contentsOf: document, encoding: .utf8)
            let outline = document.pathExtension == "md"
                ? BookOutline.markdown(source)
                : BookOutline.plainText(source)

            XCTAssertEqual(outline.map(\.title), expected.map(\.title), "titles differ for \(name)")
            XCTAssertEqual(outline.map(\.depth), expected.map(\.depth), "depths differ for \(name)")

            for item in outline {
                guard case let .offset(offset) = item.target else {
                    return XCTFail("\(name): expected an offset target")
                }
                let line = (source as NSString)
                    .substring(from: offset)
                    .components(separatedBy: "\n")[0]
                XCTAssertTrue(
                    line.contains(item.title) || BookOutline.headingTitle(of: line) == item.title,
                    "\(name): offset \(offset) does not land on the line for '\(item.title)'"
                )
            }
        }
    }
}
