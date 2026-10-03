import Foundation
import XCTest
@testable import Narratify

final class LibraryStoreTests: XCTestCase {
    @MainActor
    func testMarkdownImportCopiesAndRestoresLibrary() async throws {
        let root = try makeRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("outside.md")
        try Data("# A Local Book\n\nFirst paragraph.\n\nSecond paragraph.".utf8).write(to: source)
        let storage = root.appendingPathComponent("storage", isDirectory: true)
        let store = LibraryStore(storageRoot: storage)

        let imported = try await store.importDocument(from: source)
        XCTAssertEqual(imported.title, "A Local Book")
        XCTAssertEqual(imported.format, .markdown)
        XCTAssertEqual(try store.text(for: imported), "# A Local Book\n\nFirst paragraph.\n\nSecond paragraph.")

        let restored = LibraryStore(storageRoot: storage)
        XCTAssertEqual(restored.books.map(\.id), [imported.id])
        XCTAssertEqual(restored.books.first?.title, imported.title)
    }

    @MainActor
    func testDuplicateQuoteUsesPrefixAndSuffixContext() throws {
        let root = try makeRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let text = "First repeated phrase alpha. Later repeated phrase omega."
        let desired = (text as NSString).range(of: "repeated phrase", options: [], range: NSRange(location: 20, length: (text as NSString).length - 20)).location
        let book = LocalBook(
            id: "test", title: "Test", storedFilename: "test.txt",
            originalFilename: "test.txt", format: .text, addedAt: Date()
        )
        let store = LibraryStore(storageRoot: root)
        store.savePosition(LibraryStore.makePosition(offset: desired, in: text), for: book)

        XCTAssertEqual(store.resolvedOffset(for: book, in: text), desired)
    }

    @MainActor
    func testImportRejectsEmptyDocument() async throws {
        let root = try makeRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("empty.txt")
        try Data("  \n".utf8).write(to: source)

        do {
            _ = try await LibraryStore(storageRoot: root).importDocument(from: source)
            XCTFail("Expected empty document to be rejected")
        } catch {
            XCTAssertEqual(error as? LocalLibraryError, .emptyDocument)
        }
    }

    @MainActor
    func testImportRejectsInvalidUTF8() async throws {
        let root = try makeRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("legacy.txt")
        try Data([0x48, 0x80, 0x49]).write(to: source)

        do {
            _ = try await LibraryStore(storageRoot: root.appendingPathComponent("storage")).importDocument(from: source)
            XCTFail("Expected invalid UTF-8 to be rejected")
        } catch {
            XCTAssertEqual(error as? LocalLibraryError, .unreadableFile)
        }
    }

    @MainActor
    func testTextImportKeepsTwentyMegabyteLimit() async throws {
        let root = try makeRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("oversized.txt")
        try Data(repeating: 0x41, count: LibraryStore.maximumTextDocumentBytes + 1).write(to: source)

        do {
            _ = try await LibraryStore(storageRoot: root.appendingPathComponent("storage")).importDocument(from: source)
            XCTFail("Expected oversized text to be rejected")
        } catch {
            XCTAssertEqual(error as? LocalLibraryError, .documentTooLarge)
        }
    }

    @MainActor
    func testEPUBImportUsesItsOwnFiveHundredMegabyteLimit() async throws {
        let root = try makeRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("oversized.epub")
        XCTAssertTrue(FileManager.default.createFile(atPath: source.path, contents: nil))
        let handle = try FileHandle(forWritingTo: source)
        try handle.truncate(atOffset: UInt64(LibraryStore.maximumEPUBBytes + 1))
        try handle.close()

        do {
            _ = try await LibraryStore(storageRoot: root.appendingPathComponent("storage")).importDocument(from: source)
            XCTFail("Expected oversized EPUB to be rejected")
        } catch {
            XCTAssertEqual(error as? LocalLibraryError, .epubTooLarge)
        }
    }

    @MainActor
    func testReadiumImportsUnencryptedEPUBFixture() async throws {
        let fixture = try XCTUnwrap(
            Bundle(for: Self.self).url(
                forResource: "narratify-smoke", withExtension: "epub", subdirectory: "epub"
            ),
            "EPUB smoke fixture is not bundled with the test target"
        )
        let root = try makeRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LibraryStore(storageRoot: root)

        let imported = try await store.importDocument(from: fixture)

        XCTAssertEqual(imported.format, .epub)
        XCTAssertFalse(imported.title.isEmpty)
        let publication = try await store.openEPUB(for: imported)
        XCTAssertTrue(publication.conforms(to: .epub))
        XCTAssertFalse(publication.isRestricted)
    }

    @MainActor
    func testCorruptEPUBIsRejectedBeforePersistence() async throws {
        let root = try makeRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("broken.epub")
        try Data("not an epub".utf8).write(to: source)
        let store = LibraryStore(storageRoot: root.appendingPathComponent("storage"))

        do {
            _ = try await store.importDocument(from: source)
            XCTFail("Expected corrupt EPUB to be rejected")
        } catch {
            XCTAssertEqual(error as? LocalLibraryError, .invalidEPUB)
            XCTAssertTrue(store.books.isEmpty)
        }
    }

    private func makeRoot() throws -> URL {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("NarratifyTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }
}
