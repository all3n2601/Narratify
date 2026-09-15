import CryptoKit
import Foundation
@preconcurrency import ReadiumShared
@preconcurrency import ReadiumStreamer
import UniformTypeIdentifiers

enum LocalBookFormat: String, Codable {
    case text
    case markdown
    case epub

    var label: String {
        switch self {
        case .text: "Text"
        case .markdown: "Markdown"
        case .epub: "EPUB"
        }
    }
}

struct LocalBook: Codable, Hashable, Identifiable {
    let id: String
    var title: String
    let storedFilename: String
    let originalFilename: String
    let format: LocalBookFormat
    let addedAt: Date
}

struct StoredReadingPosition: Codable, Equatable {
    let characterOffset: Int
    let progression: Double
    let exact: String
    let prefix: String
    let suffix: String
    let updatedAt: Date
    let epubLocatorJSON: String?
}

enum LocalLibraryError: LocalizedError, Equatable, Sendable {
    case unsupportedFormat
    case unreadableFile
    case emptyDocument
    case documentTooLarge
    case epubTooLarge
    case protectedEPUB
    case invalidEPUB

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat: "Narratify currently imports TXT, Markdown, and EPUB files."
        case .unreadableFile: "The selected file could not be read."
        case .emptyDocument: "The selected document contains no readable text."
        case .documentTooLarge: "TXT and Markdown files are limited to 20 MB."
        case .epubTooLarge: "EPUB files are limited to 500 MB."
        case .protectedEPUB: "This EPUB is encrypted or DRM-protected. Narratify only opens unencrypted local books."
        case .invalidEPUB: "The selected file is not a readable EPUB."
        }
    }
}

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var books: [LocalBook] = []
    @Published private(set) var positions: [String: StoredReadingPosition] = [:]
    @Published private(set) var persistenceNotice: String?

    static let supportedContentTypes: [UTType] = [
        .plainText,
        UTType(filenameExtension: "md") ?? .plainText,
        UTType(filenameExtension: "markdown") ?? .plainText,
        UTType(filenameExtension: "epub") ?? .data,
    ]

    static let maximumTextDocumentBytes = 20 * 1_024 * 1_024
    static let maximumEPUBBytes = 500 * 1_024 * 1_024

    private struct LibraryIndex: Codable {
        var schemaVersion = 1
        var books: [LocalBook]
        var positions: [String: StoredReadingPosition]
    }

    private let fileManager: FileManager
    private let rootURL: URL
    private let booksURL: URL
    private let indexURL: URL
    private let epubService = EPUBService()

    init(fileManager: FileManager = .default, storageRoot: URL? = nil) {
        self.fileManager = fileManager
        let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        rootURL = storageRoot ?? support.appendingPathComponent("Narratify", isDirectory: true)
        booksURL = rootURL.appendingPathComponent("Books", isDirectory: true)
        indexURL = rootURL.appendingPathComponent("library-v1.json")
        load()
    }

    @discardableResult
    func importDocument(from sourceURL: URL) async throws -> LocalBook {
        let scoped = sourceURL.startAccessingSecurityScopedResource()
        defer { if scoped { sourceURL.stopAccessingSecurityScopedResource() } }

        guard let format = Self.format(for: sourceURL) else {
            throw LocalLibraryError.unsupportedFormat
        }
        let maximumBytes = format == .epub
            ? Self.maximumEPUBBytes
            : Self.maximumTextDocumentBytes
        let tooLargeError: LocalLibraryError = format == .epub ? .epubTooLarge : .documentTooLarge
        let values = try sourceURL.resourceValues(forKeys: [.fileSizeKey])
        if let size = values.fileSize, size > maximumBytes {
            throw tooLargeError
        }

        let data: Data?
        let text: String?
        let epubTitle: String?
        let id: String
        if format == .epub {
            epubTitle = try await epubService.title(url: sourceURL)
            id = try await Task.detached(priority: .userInitiated) {
                try Self.sha256(of: sourceURL, maximumBytes: maximumBytes, tooLargeError: tooLargeError)
            }.value
            data = nil
            text = nil
        } else {
            guard let documentData = try? Data(contentsOf: sourceURL, options: .mappedIfSafe) else {
                throw LocalLibraryError.unreadableFile
            }
            guard documentData.count <= maximumBytes else { throw tooLargeError }
            guard let decoded = Self.decode(documentData) else { throw LocalLibraryError.unreadableFile }
            guard !decoded.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw LocalLibraryError.emptyDocument
            }
            data = documentData
            text = decoded
            epubTitle = nil
            id = SHA256.hash(data: documentData).map { String(format: "%02x", $0) }.joined()
        }

        if let existing = books.first(where: { $0.id == id }) { return existing }

        try prepareDirectories()
        let ext: String
        switch format {
        case .text: ext = "txt"
        case .markdown: ext = "md"
        case .epub: ext = "epub"
        }
        let filename = "\(id).\(ext)"
        let destination = booksURL.appendingPathComponent(filename)
        if !fileManager.fileExists(atPath: destination.path) {
            do {
                if let data {
                    try data.write(to: destination, options: .atomic)
                } else {
                    try await Task.detached(priority: .userInitiated) {
                        try FileManager.default.copyItem(at: sourceURL, to: destination)
                    }.value
                }
            } catch {
                try? fileManager.removeItem(at: destination)
                throw LocalLibraryError.unreadableFile
            }
        }

        let filenameTitle = sourceURL.deletingPathExtension().lastPathComponent
        let documentTitle = epubTitle ?? text.flatMap { Self.title(in: $0, format: format) } ?? filenameTitle
        let book = LocalBook(
            id: id,
            title: documentTitle,
            storedFilename: filename,
            originalFilename: sourceURL.lastPathComponent,
            format: format,
            addedAt: Date()
        )
        books.insert(book, at: 0)
        do {
            try persist()
        } catch {
            books.removeAll { $0.id == book.id }
            try? fileManager.removeItem(at: destination)
            throw error
        }
        return book
    }

    @discardableResult
    func importRemoteEPUB(from sourceURL: URL, suggestedTitle: String) async throws -> LocalBook {
        guard sourceURL.scheme?.lowercased() == "https" else { throw LocalLibraryError.unreadableFile }
        var request = URLRequest(url: sourceURL)
        request.timeoutInterval = 30
        request.setValue("Narratify/1.0 (book import)", forHTTPHeaderField: "User-Agent")
        let (downloadURL, response) = try await URLSession.shared.download(for: request)
        guard let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode),
              http.url?.scheme?.lowercased() == "https" else { throw LocalLibraryError.unreadableFile }
        if response.expectedContentLength > Int64(Self.maximumEPUBBytes) { throw LocalLibraryError.epubTooLarge }

        let safeTitle = suggestedTitle
            .components(separatedBy: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: " ._-")).inverted)
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let temporaryURL = fileManager.temporaryDirectory
            .appendingPathComponent("\(safeTitle.isEmpty ? "book" : String(safeTitle.prefix(80)))-\(UUID().uuidString).epub")
        defer { try? fileManager.removeItem(at: temporaryURL) }
        do {
            try fileManager.copyItem(at: downloadURL, to: temporaryURL)
        } catch {
            throw LocalLibraryError.unreadableFile
        }
        return try await importDocument(from: temporaryURL)
    }

    func text(for book: LocalBook) throws -> String {
        guard book.format != .epub else { throw LocalLibraryError.unsupportedFormat }
        let url = booksURL.appendingPathComponent(book.storedFilename)
        guard let data = try? Data(contentsOf: url), let text = Self.decode(data) else {
            throw LocalLibraryError.unreadableFile
        }
        return text
    }

    func position(for book: LocalBook) -> StoredReadingPosition? { positions[book.id] }

    func savePosition(_ position: StoredReadingPosition, for book: LocalBook) {
        positions[book.id] = position
        do {
            try persist()
        } catch {
            persistenceNotice = "Your place could not be saved. \(error.localizedDescription)"
        }
    }

    func clearPersistenceNotice() { persistenceNotice = nil }

    func saveEPUBLocator(_ locator: Locator, for book: LocalBook) {
        savePosition(
            StoredReadingPosition(
                characterOffset: 0,
                progression: locator.locations.totalProgression ?? locator.locations.progression ?? 0,
                exact: locator.text.highlight ?? "",
                prefix: locator.text.before ?? "",
                suffix: locator.text.after ?? "",
                updatedAt: Date(),
                epubLocatorJSON: try? locator.jsonString()
            ),
            for: book
        )
    }

    func openEPUB(for book: LocalBook) async throws -> Publication {
        guard book.format == .epub else { throw LocalLibraryError.invalidEPUB }
        return try await epubService.open(url: booksURL.appendingPathComponent(book.storedFilename))
    }

    func epubLocator(for book: LocalBook) -> Locator? {
        guard let string = positions[book.id]?.epubLocatorJSON,
              let json = try? JSONValue(jsonString: string) else { return nil }
        return try? Locator(json: json, warnings: nil)
    }

    func resolvedOffset(for book: LocalBook, in text: String) -> Int {
        guard let saved = position(for: book), !text.isEmpty else { return 0 }
        let source = text as NSString

        if !saved.exact.isEmpty {
            var candidates: [Int] = []
            var searchRange = NSRange(location: 0, length: source.length)
            while searchRange.length > 0 {
                let found = source.range(of: saved.exact, range: searchRange)
                guard found.location != NSNotFound else { break }
                candidates.append(found.location)
                let next = NSMaxRange(found)
                searchRange = NSRange(location: next, length: source.length - next)
            }
            if let best = candidates.max(by: {
                restorationScore(offset: $0, saved: saved, source: source)
                    < restorationScore(offset: $1, saved: saved, source: source)
            }) {
                return best
            }
        }
        if saved.characterOffset <= source.length { return max(0, saved.characterOffset) }
        return min(source.length, max(0, Int(saved.progression * Double(source.length))))
    }

    static func makePosition(offset: Int, in text: String) -> StoredReadingPosition {
        let units = Array(text.utf16)
        let safeOffset = min(max(offset, 0), units.count)
        let exactEnd = min(units.count, safeOffset + 96)
        let prefixStart = max(0, safeOffset - 32)
        let suffixEnd = min(units.count, exactEnd + 32)
        return StoredReadingPosition(
            characterOffset: safeOffset,
            progression: units.isEmpty ? 0 : Double(safeOffset) / Double(units.count),
            exact: String(decoding: units[safeOffset..<exactEnd], as: UTF16.self),
            prefix: String(decoding: units[prefixStart..<safeOffset], as: UTF16.self),
            suffix: String(decoding: units[exactEnd..<suffixEnd], as: UTF16.self),
            updatedAt: Date(),
            epubLocatorJSON: nil
        )
    }

    private func load() {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        guard let data = try? Data(contentsOf: indexURL),
              let index = try? decoder.decode(LibraryIndex.self, from: data) else { return }
        books = index.books.filter { fileManager.fileExists(atPath: booksURL.appendingPathComponent($0.storedFilename).path) }
        positions = index.positions.filter { id, _ in books.contains(where: { $0.id == id }) }
    }

    private func persist() throws {
        try prepareDirectories()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .millisecondsSince1970
        let data = try encoder.encode(LibraryIndex(books: books, positions: positions))
        try data.write(to: indexURL, options: .atomic)
    }

    private func prepareDirectories() throws {
        try fileManager.createDirectory(at: booksURL, withIntermediateDirectories: true)
    }

    private static func format(for url: URL) -> LocalBookFormat? {
        switch url.pathExtension.lowercased() {
        case "txt", "text": .text
        case "md", "markdown", "mdown", "mkd": .markdown
        case "epub": .epub
        default: nil
        }
    }

    private static func decode(_ data: Data) -> String? {
        String(data: data, encoding: .utf8)?.replacingOccurrences(of: "\u{FEFF}", with: "", options: [.anchored])
    }

    nonisolated private static func sha256(
        of url: URL,
        maximumBytes: Int,
        tooLargeError: LocalLibraryError
    ) throws -> String {
        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            throw LocalLibraryError.unreadableFile
        }
        defer { try? handle.close() }

        var hasher = SHA256()
        var byteCount = 0
        do {
            while let chunk = try handle.read(upToCount: 1_024 * 1_024), !chunk.isEmpty {
                byteCount += chunk.count
                guard byteCount <= maximumBytes else { throw tooLargeError }
                hasher.update(data: chunk)
            }
        } catch let error as LocalLibraryError {
            throw error
        } catch {
            throw LocalLibraryError.unreadableFile
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func title(in text: String, format: LocalBookFormat) -> String? {
        guard format == .markdown else { return nil }
        return text.split(whereSeparator: \.isNewline)
            .lazy
            .map(String.init)
            .first(where: { $0.hasPrefix("# ") })?
            .dropFirst(2)
            .description
            .trimmingCharacters(in: .whitespaces)
    }

    private func restorationScore(offset: Int, saved: StoredReadingPosition, source: NSString) -> Int {
        let prefixStart = max(0, offset - (saved.prefix as NSString).length)
        let actualPrefix = source.substring(with: NSRange(location: prefixStart, length: offset - prefixStart))
        let suffixStart = min(source.length, offset + (saved.exact as NSString).length)
        let suffixLength = min((saved.suffix as NSString).length, source.length - suffixStart)
        let actualSuffix = source.substring(with: NSRange(location: suffixStart, length: suffixLength))
        var score = -abs(offset - saved.characterOffset)
        if !saved.prefix.isEmpty && actualPrefix.hasSuffix(saved.prefix) { score += 1_000_000 }
        if !saved.suffix.isEmpty && actualSuffix.hasPrefix(saved.suffix) { score += 1_000_000 }
        return score
    }
}

@MainActor
private final class EPUBService {
    private let assetRetriever: AssetRetriever
    private let publicationOpener: PublicationOpener

    init() {
        let httpClient = DefaultHTTPClient()
        let retriever = AssetRetriever(httpClient: httpClient)
        assetRetriever = retriever
        publicationOpener = PublicationOpener(
            parser: DefaultPublicationParser(
                httpClient: httpClient,
                assetRetriever: retriever,
                pdfFactory: DefaultPDFDocumentFactory()
            )
        )
    }

    func open(url: URL) async throws -> Publication {
        guard let file = FileURL(url: url) else { throw LocalLibraryError.invalidEPUB }
        let asset: Asset
        do {
            asset = try await assetRetriever.retrieve(url: file).get()
        } catch {
            throw LocalLibraryError.invalidEPUB
        }
        do {
            let publication = try await publicationOpener.open(
                asset: asset,
                allowUserInteraction: false
            ).get()
            guard !publication.isRestricted else { throw LocalLibraryError.protectedEPUB }
            return publication
        } catch let error as LocalLibraryError {
            throw error
        } catch {
            throw LocalLibraryError.invalidEPUB
        }
    }

    /// Readium 3.11 deprecates explicit `close()` in favor of releasing the
    /// publication. Keeping metadata validation in this narrow scope ensures
    /// the archive resources are released before the managed copy is written.
    func title(url: URL) async throws -> String? {
        let publication = try await open(url: url)
        guard publication.conforms(to: .epub) else { throw LocalLibraryError.invalidEPUB }
        guard !publication.isRestricted else { throw LocalLibraryError.protectedEPUB }
        return publication.metadata.title
    }
}
