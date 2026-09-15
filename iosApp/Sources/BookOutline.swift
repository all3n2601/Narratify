import Foundation

/// Readium's link tree reduced to what an outline needs, so the logic stays testable without a
/// publication.
struct OutlineLink {
    let title: String?
    let href: String
    var children: [OutlineLink] = []
}

/// What an outline row points at. Text documents address offsets, EPUBs address resources.
enum OutlineTarget: Equatable {
    /// A UTF-16 offset into the string the reader displays, matching the reader's block offsets.
    case offset(Int)
    case resource(String)
}

/// One row in the reader outline.
struct OutlineItem: Equatable {
    let title: String
    let depth: Int
    let target: OutlineTarget
}

/// Builds and resolves reader outlines.
///
/// Offsets index the raw string this app displays. Android renders Markdown down to plain text
/// before displaying it and reports offsets into that instead, so only titles, depths and order
/// are comparable across the two platforms.
enum BookOutline {
    /// Longer isolated lines read as prose, not as chapter headings.
    private static let maxHeadingLength = 60

    /// Plain text has no markup, so a lone match is more likely a coincidence than a structure.
    private static let minimumPlainTextEntries = 2

    /// A single row tells the reader nothing the title bar has not already told them.
    private static let minimumEntries = 2

    private static let chapterKeyword = regex("^(chapter|part|book|section|prologue|epilogue|act)\\b.*$", caseInsensitive: true)
    /// Roman numerals stay case-sensitive: lowercase words like "civil" are all roman letters.
    private static let bareNumeral = regex("^([IVXLCDM]+|[0-9]{1,4})\\.?$")
    private static let atxHeading = regex("^(#{1,6})\\s+\\S.*$")
    private static let atxPrefix = regex("^#{1,6}\\s+")
    private static let atxSuffix = regex("\\s+#+$")
    private static let inlineMarkers = regex("[`*_~]{1,3}")

    static func isWorthShowing(_ items: [OutlineItem]) -> Bool { items.count >= minimumEntries }

    /// Chapter-like lines in a plain text document.
    static func plainText(_ text: String) -> [OutlineItem] {
        let lines = lineSpans(text as NSString)
        let items: [OutlineItem] = lines.enumerated().compactMap { index, span in
            let trimmed = span.content.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, trimmed.count <= maxHeadingLength else { return nil }
            guard isIsolated(lines, index) else { return nil }
            guard matches(chapterKeyword, trimmed) || matches(bareNumeral, trimmed) else { return nil }
            return OutlineItem(title: trimmed, depth: 0, target: .offset(span.textStart))
        }
        return items.count < minimumPlainTextEntries ? [] : items
    }

    /// ATX headings in a Markdown document.
    ///
    /// Setext underlines are deliberately not recognised. A `---` line is equally a horizontal
    /// rule, and promoting rules into the outline is worse than missing a rare heading style.
    static func markdown(_ source: String) -> [OutlineItem] {
        var items: [OutlineItem] = []
        var fenced = false
        for span in lineSpans(source as NSString) {
            let trimmed = span.content.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                fenced.toggle()
                continue
            }
            guard !fenced, matches(atxHeading, trimmed) else { continue }
            let title = headingTitle(of: span.content)
            guard !title.isEmpty else { continue }
            items.append(
                OutlineItem(
                    title: title,
                    depth: trimmed.prefix { $0 == "#" }.count - 1,
                    target: .offset(span.textStart)
                )
            )
        }
        return items
    }

    /// Flattens an EPUB table of contents depth first. Publications that ship no table of
    /// contents — common in scanned public-domain titles — fall back to their reading order.
    static func fromTableOfContents(_ tableOfContents: [OutlineLink], readingOrder: [OutlineLink]) -> [OutlineItem] {
        var items: [OutlineItem] = []

        func walk(_ links: [OutlineLink], depth: Int) {
            for link in links {
                items.append(
                    OutlineItem(
                        title: name(of: link, position: items.count),
                        depth: depth,
                        target: .resource(link.href)
                    )
                )
                walk(link.children, depth: depth + 1)
            }
        }

        if !tableOfContents.isEmpty {
            walk(tableOfContents, depth: 0)
            return items
        }
        return readingOrder.enumerated().map { index, link in
            OutlineItem(title: name(of: link, position: index), depth: 0, target: .resource(link.href))
        }
    }

    /// The last entry at or before `offset`, or -1 while the reader is still ahead of the first.
    static func currentIndex(_ items: [OutlineItem], offset: Int) -> Int {
        items.lastIndex { item in
            guard case let .offset(start) = item.target else { return false }
            return start <= offset
        } ?? -1
    }

    /// The entry for `href`. An exact match wins; otherwise the last entry inside the same
    /// resource does, since a locator deeper in a chapter than any of its listed anchors still
    /// belongs to the last anchor above it.
    ///
    /// Comparison is by resource name, because a navigator reports a fully resolved location
    /// while a table of contents lists whatever relative path the publication authored.
    static func currentIndex(_ items: [OutlineItem], href: String) -> Int {
        let wanted = resourceName(href)
        if let exact = items.firstIndex(where: { resourceName(of: $0) == wanted }) { return exact }
        let resource = wanted.components(separatedBy: "#")[0]
        return items.lastIndex { item in
            guard let candidate = resourceName(of: item) else { return false }
            return candidate.components(separatedBy: "#")[0] == resource
        } ?? -1
    }

    private static func resourceName(_ href: String) -> String {
        href.components(separatedBy: "/").last ?? href
    }

    private static func resourceName(of item: OutlineItem) -> String? {
        guard case let .resource(href) = item.target else { return nil }
        return resourceName(href)
    }

    /// Android's Markdown rendering strips these before the outline is built; this app reports
    /// headings straight from the source, so stripping here keeps both titles the same.
    static func headingTitle(of line: String) -> String {
        var title = line.trimmingCharacters(in: .whitespaces)
        title = replacing(atxPrefix, in: title)
        title = replacing(atxSuffix, in: title)
        title = replacing(inlineMarkers, in: title)
        return title.trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Internals

    private static func name(of link: OutlineLink, position: Int) -> String {
        let title = link.title?.trimmingCharacters(in: .whitespaces) ?? ""
        return title.isEmpty ? "Section \(position + 1)" : title
    }

    /// A heading stands alone: blank space above and below, or the edge of the document.
    private static func isIsolated(_ lines: [LineSpan], _ index: Int) -> Bool {
        let before = index == 0 || lines[index - 1].isBlank
        let after = index == lines.count - 1 || lines[index + 1].isBlank
        return before && after
    }

    private struct LineSpan {
        let start: Int
        let content: String

        var isBlank: Bool { content.trimmingCharacters(in: .whitespaces).isEmpty }

        /// Offset of the first non-blank character, so an indented heading still points at its text.
        var textStart: Int {
            let leading = content.prefix { $0.isWhitespace }
            return start + (String(leading) as NSString).length
        }
    }

    private static func lineSpans(_ text: NSString) -> [LineSpan] {
        var spans: [LineSpan] = []
        var start = 0
        while true {
            let remaining = NSRange(location: start, length: text.length - start)
            let newline = text.range(of: "\n", range: remaining)
            guard newline.location != NSNotFound else {
                spans.append(LineSpan(start: start, content: text.substring(from: start)))
                return spans
            }
            let line = NSRange(location: start, length: newline.location - start)
            spans.append(LineSpan(start: start, content: text.substring(with: line)))
            start = newline.location + 1
        }
    }

    private static func regex(_ pattern: String, caseInsensitive: Bool = false) -> NSRegularExpression {
        // The patterns are literals in this file: a failure here is a programming error, not input.
        try! NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : [])
    }

    private static func matches(_ expression: NSRegularExpression, _ value: String) -> Bool {
        let range = NSRange(location: 0, length: (value as NSString).length)
        return expression.firstMatch(in: value, range: range) != nil
    }

    private static func replacing(_ expression: NSRegularExpression, in value: String) -> String {
        let range = NSRange(location: 0, length: (value as NSString).length)
        return expression.stringByReplacingMatches(in: value, range: range, withTemplate: "")
    }
}
