import Foundation
import XCTest
@testable import Narratify

final class BookCatalogTests: XCTestCase {
    func testParsesDownloadableGutendexEPUB() throws {
        let data = Data(#"{"results":[{"id":84,"title":"Frankenstein","authors":[{"name":"Mary Shelley"}],"formats":{"application/epub+zip":"https://www.gutenberg.org/ebooks/84.epub3.images","image/jpeg":"https://example.test/cover.jpg"}}]}"#.utf8)

        let book = try CatalogParsers.gutendex(data).first

        XCTAssertEqual(book?.title, "Frankenstein")
        XCTAssertEqual(book?.author, "Mary Shelley")
        XCTAssertEqual(book?.epubURL?.scheme, "https")
    }

    func testMergePrefersDownloadableDuplicate() {
        let metadata = CatalogBook(
            id: "open:1", title: "Frankenstein", author: "Mary Shelley",
            sources: "Open Library", detailURL: URL(string: "https://openlibrary.org/works/1")!,
            coverURL: nil, epubURL: nil, note: nil
        )
        let download = CatalogBook(
            id: "gutenberg:84", title: "Frankenstein", author: "Mary Shelley",
            sources: "Project Gutenberg", detailURL: URL(string: "https://gutenberg.org/84")!,
            coverURL: nil, epubURL: URL(string: "https://gutenberg.org/84.epub")!, note: nil
        )

        let merged = CatalogParsers.merge([[metadata], [download]])

        XCTAssertEqual(merged.count, 1)
        XCTAssertNotNil(merged[0].epubURL)
        XCTAssertTrue(merged[0].sources.contains("Open Library"))
        XCTAssertTrue(merged[0].sources.contains("Project Gutenberg"))
    }

    func testProviderWithoutItemsReturnsEmptyList() throws {
        XCTAssertTrue(try CatalogParsers.googleBooks(Data(#"{"totalItems":0}"#.utf8)).isEmpty)
    }

    func testMergeRanksExactAndPartialTitleMatchesAheadOfUnrelatedResults() {
        let unrelated = CatalogBook(
            id: "archive:1", title: "Alien Art", author: "Gordon Dickson",
            sources: "Internet Archive", detailURL: URL(string: "https://archive.org/1")!,
            coverURL: nil, epubURL: nil, note: nil
        )
        let partial = CatalogBook(
            id: "open:2", title: "Cleopatra and Frankenstein", author: "Coco Mellors",
            sources: "Open Library", detailURL: URL(string: "https://openlibrary.org/2")!,
            coverURL: nil, epubURL: nil, note: nil
        )
        let exact = CatalogBook(
            id: "open:3", title: "Frankenstein", author: "Mary Shelley",
            sources: "Open Library", detailURL: URL(string: "https://openlibrary.org/3")!,
            coverURL: nil, epubURL: nil, note: nil
        )

        let merged = CatalogParsers.merge([[unrelated, partial, exact]], matching: "Frankenstein")

        XCTAssertEqual(merged.map(\.id), [exact.id, partial.id, unrelated.id])
    }
}
