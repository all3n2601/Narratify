import Foundation

struct CatalogBook: Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let author: String
    var sources: String
    let detailURL: URL
    let coverURL: URL?
    let epubURL: URL?
    let note: String?
}

enum CatalogProvider: String, CaseIterable, Sendable {
    case gutenberg
    case openLibrary
    case googleBooks
    case internetArchive
    case librivox

    func search(_ query: String) async throws -> [CatalogBook] {
        let url = try endpoint(query)
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        request.setValue("Narratify/1.0 (book catalog)", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw CatalogError.unavailable
        }
        switch self {
        case .gutenberg: return try CatalogParsers.gutendex(data)
        case .openLibrary: return try CatalogParsers.openLibrary(data)
        case .googleBooks: return try CatalogParsers.googleBooks(data)
        case .internetArchive: return try CatalogParsers.internetArchive(data)
        case .librivox: return try CatalogParsers.librivox(data)
        }
    }

    private func endpoint(_ query: String) throws -> URL {
        var components: URLComponents
        switch self {
        case .gutenberg:
            components = URLComponents(string: "https://gutendex.com/books")!
            components.queryItems = [.init(name: "search", value: query)]
        case .openLibrary:
            components = URLComponents(string: "https://openlibrary.org/search.json")!
            components.queryItems = [
                .init(name: "q", value: query),
                .init(name: "limit", value: "12"),
                .init(name: "fields", value: "key,title,author_name,first_publish_year,cover_i,ebook_access"),
            ]
        case .googleBooks:
            components = URLComponents(string: "https://www.googleapis.com/books/v1/volumes")!
            components.queryItems = [
                .init(name: "q", value: query),
                .init(name: "maxResults", value: "12"),
                .init(name: "printType", value: "books"),
            ]
        case .internetArchive:
            components = URLComponents(string: "https://archive.org/advancedsearch.php")!
            components.queryItems = [
                .init(name: "q", value: "\(query) AND mediatype:texts"),
                .init(name: "fl[]", value: "identifier"),
                .init(name: "fl[]", value: "title"),
                .init(name: "fl[]", value: "creator"),
                .init(name: "rows", value: "12"),
                .init(name: "page", value: "1"),
                .init(name: "output", value: "json"),
            ]
        case .librivox:
            components = URLComponents(string: "https://librivox.org/api/feed/audiobooks")!
            components.queryItems = [
                .init(name: "title", value: "^\(query)"),
                .init(name: "format", value: "json"),
                .init(name: "extended", value: "1"),
                .init(name: "limit", value: "12"),
            ]
        }
        guard let url = components.url else { throw CatalogError.invalidQuery }
        return url
    }
}

enum CatalogError: LocalizedError, Sendable {
    case invalidQuery
    case invalidResponse
    case unavailable

    var errorDescription: String? {
        switch self {
        case .invalidQuery: "That search could not be encoded."
        case .invalidResponse: "A catalog returned an unreadable response."
        case .unavailable: "A catalog is temporarily unavailable."
        }
    }
}

enum CatalogParsers {
    static func gutendex(_ data: Data) throws -> [CatalogBook] {
        let root = try dictionary(data)
        let items = root["results"] as? [[String: Any]] ?? []
        return items.compactMap { item -> CatalogBook? in
            guard let id = item["id"] as? Int,
                  let title = nonempty(item["title"]),
                  let detail = URL(string: "https://www.gutenberg.org/ebooks/\(id)") else { return nil }
            let authors = item["authors"] as? [[String: Any]]
            let formats = item["formats"] as? [String: Any]
            return CatalogBook(
                id: "gutenberg:\(id)",
                title: title,
                author: nonempty(authors?.first?["name"]) ?? "Unknown author",
                sources: "Project Gutenberg",
                detailURL: detail,
                coverURL: url(formats?["image/jpeg"]),
                epubURL: url(formats?["application/epub+zip"]),
                note: "Public-domain availability depends on your country."
            )
        }
    }

    static func openLibrary(_ data: Data) throws -> [CatalogBook] {
        let items = try dictionary(data)["docs"] as? [[String: Any]] ?? []
        return items.compactMap { item -> CatalogBook? in
            guard let key = nonempty(item["key"]),
                  let title = nonempty(item["title"]),
                  let detail = URL(string: "https://openlibrary.org\(key)") else { return nil }
            let authors = item["author_name"] as? [String]
            let coverID = (item["cover_i"] as? NSNumber)?.int64Value
            let cover = coverID.flatMap { URL(string: "https://covers.openlibrary.org/b/id/\($0)-M.jpg") }
            let year = (item["first_publish_year"] as? NSNumber)?.intValue
            return CatalogBook(
                id: "openlibrary:\(key)", title: title,
                author: authors?.first ?? "Unknown author", sources: "Open Library",
                detailURL: detail, coverURL: cover, epubURL: nil,
                note: year.map { "First published \($0)" }
            )
        }
    }

    static func googleBooks(_ data: Data) throws -> [CatalogBook] {
        let items = try dictionary(data)["items"] as? [[String: Any]] ?? []
        return items.compactMap { item -> CatalogBook? in
            guard let id = nonempty(item["id"]),
                  let info = item["volumeInfo"] as? [String: Any],
                  let title = nonempty(info["title"]),
                  let detail = url(info["infoLink"]) ?? URL(string: "https://books.google.com/books?id=\(id)") else { return nil }
            let images = info["imageLinks"] as? [String: Any]
            let authors = info["authors"] as? [String]
            return CatalogBook(
                id: "google:\(id)", title: title,
                author: authors?.first ?? "Unknown author", sources: "Google Books",
                detailURL: detail, coverURL: secureURL(images?["thumbnail"]), epubURL: nil,
                note: nonempty(info["publishedDate"]).map { "Published \($0)" }
            )
        }
    }

    static func internetArchive(_ data: Data) throws -> [CatalogBook] {
        let root = try dictionary(data)
        let items = (root["response"] as? [String: Any])?["docs"] as? [[String: Any]] ?? []
        return items.compactMap { item -> CatalogBook? in
            guard let id = nonempty(item["identifier"]),
                  let title = nonempty(item["title"]),
                  let detail = URL(string: "https://archive.org/details/\(id)") else { return nil }
            let author = nonempty(item["creator"])
                ?? (item["creator"] as? [String])?.first
                ?? "Unknown author"
            return CatalogBook(
                id: "archive:\(id)", title: title, author: author,
                sources: "Internet Archive", detailURL: detail,
                coverURL: URL(string: "https://archive.org/services/img/\(id)"), epubURL: nil,
                note: "Availability and borrowing terms vary by item."
            )
        }
    }

    static func librivox(_ data: Data) throws -> [CatalogBook] {
        let items = try dictionary(data)["books"] as? [[String: Any]] ?? []
        return items.compactMap { item -> CatalogBook? in
            guard let id = nonempty(item["id"]),
                  let title = nonempty(item["title"]),
                  let detail = url(item["url_librivox"]) else { return nil }
            let authorObject = (item["authors"] as? [[String: Any]])?.first
            let author = [nonempty(authorObject?["first_name"]), nonempty(authorObject?["last_name"])]
                .compactMap { $0 }.joined(separator: " ")
            return CatalogBook(
                id: "librivox:\(id)", title: title,
                author: author.isEmpty ? "Unknown author" : author,
                sources: "LibriVox", detailURL: detail, coverURL: nil, epubURL: nil,
                note: "Human-read public-domain audiobook."
            )
        }
    }

    static func merge(_ groups: [[CatalogBook]]) -> [CatalogBook] {
        var merged: [String: CatalogBook] = [:]
        for candidate in groups.flatMap({ $0 }) {
            let key = normalize(candidate.title) + "|" + normalize(candidate.author)
            if let current = merged[key] {
                var preferred = current.epubURL == nil && candidate.epubURL != nil ? candidate : current
                preferred.sources = Array(Set(
                    current.sources.components(separatedBy: " · ")
                        + candidate.sources.components(separatedBy: " · ")
                )).sorted().joined(separator: " · ")
                merged[key] = preferred
            } else {
                merged[key] = candidate
            }
        }
        return merged.values.sorted {
            if ($0.epubURL != nil) != ($1.epubURL != nil) { return $0.epubURL != nil }
            return $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
        }
    }

    private static func dictionary(_ data: Data) throws -> [String: Any] {
        guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CatalogError.invalidResponse
        }
        return value
    }

    private static func nonempty(_ value: Any?) -> String? {
        (value as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    private static func url(_ value: Any?) -> URL? { nonempty(value).flatMap(URL.init(string:)) }

    private static func secureURL(_ value: Any?) -> URL? {
        nonempty(value).flatMap { URL(string: $0.replacingOccurrences(of: "http://", with: "https://")) }
    }

    private static func normalize(_ value: String) -> String {
        value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .filter { $0.isLetter || $0.isNumber }
    }
}

struct CatalogSearchResult: Sendable {
    let books: [CatalogBook]
    let failedProviderCount: Int
}

enum BookCatalogService {
    static func search(_ query: String) async -> CatalogSearchResult {
        await withTaskGroup(of: Result<[CatalogBook], Error>.self) { group in
            for provider in CatalogProvider.allCases {
                group.addTask {
                    do { return .success(try await provider.search(query)) }
                    catch { return .failure(error) }
                }
            }
            var successful: [[CatalogBook]] = []
            var failures = 0
            for await result in group {
                switch result {
                case let .success(books): successful.append(books)
                case .failure: failures += 1
                }
            }
            return CatalogSearchResult(books: CatalogParsers.merge(successful), failedProviderCount: failures)
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
