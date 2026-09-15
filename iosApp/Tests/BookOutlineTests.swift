import XCTest
@testable import Narratify

final class BookOutlineTests: XCTestCase {
    private func link(_ title: String?, _ href: String, _ children: [OutlineLink] = []) -> OutlineLink {
        OutlineLink(title: title, href: href, children: children)
    }

    // MARK: - Plain text

    func testChapterLinesBecomeOutlineEntries() {
        let text = "Preface words.\n\nCHAPTER I\n\nFirst body.\n\nCHAPTER II\n\nSecond body.\n"

        let outline = BookOutline.plainText(text)

        XCTAssertEqual(outline.map(\.title), ["CHAPTER I", "CHAPTER II"])
        XCTAssertTrue(outline.allSatisfy { $0.depth == 0 })
        XCTAssertEqual(outline[1].target, .offset((text as NSString).range(of: "CHAPTER II").location))
    }

    func testASingleChapterLineIsNotEnoughToReportAnOutline() {
        let text = "A Short Work\n\nCHAPTER I\n\nOnly one qualifying line appears here.\n"

        XCTAssertTrue(BookOutline.plainText(text).isEmpty)
    }

    func testProseThatMerelyMentionsAChapterHasNoOutline() {
        let text = "It mentions a chapter of history in passing.\n\nBut never as a heading.\n"

        XCTAssertTrue(BookOutline.plainText(text).isEmpty)
    }

    func testAChapterLineNeedsBlankLinesAroundIt() {
        let text = "CHAPTER I\nruns straight into the body text.\nCHAPTER II\nalso does.\n"

        XCTAssertTrue(BookOutline.plainText(text).isEmpty)
    }

    func testBareNumeralsCountAsChapterLines() {
        let text = "Opening.\n\n1\n\nFirst body.\n\n2\n\nSecond body.\n"

        XCTAssertEqual(BookOutline.plainText(text).map(\.title), ["1", "2"])
    }

    func testAnOverLongLineIsNotAChapterHeading() {
        let long = "CHAPTER " + String(repeating: "x", count: 80)
        let text = "Opening.\n\n\(long)\n\nBody.\n\nCHAPTER II\n\nMore.\n"

        XCTAssertTrue(BookOutline.plainText(text).isEmpty)
    }

    // MARK: - Markdown

    func testMarkdownHeadingLevelBecomesDepth() {
        let outline = BookOutline.markdown("# Top\n\nBody.\n\n### Deep\n\nBody.\n")

        XCTAssertEqual(outline.map(\.title), ["Top", "Deep"])
        XCTAssertEqual(outline.map(\.depth), [0, 2])
    }

    func testHeadingsInsideFencedCodeAreIgnored() {
        let outline = BookOutline.markdown("# Real\n\n```\n# fake\n```\n\n## Also real\n")

        XCTAssertEqual(outline.map(\.title), ["Real", "Also real"])
    }

    func testSetextUnderlinesAreNotHeadings() {
        let outline = BookOutline.markdown("# Real\n\nSetext Style\n---\n\nBody.\n")

        XCTAssertEqual(outline.map(\.title), ["Real"])
    }

    func testHeadingTitlesDropInlineEmphasisMarkers() {
        XCTAssertEqual(BookOutline.markdown("## A **Bold** Chapter\n").map(\.title), ["A Bold Chapter"])
    }

    func testMarkdownOffsetsAddressTheSourceTheReaderDisplays() {
        let source = "# Top\n\nIntro.\n\n## A **Bold** Chapter\n\nBody.\n"

        let chapter = BookOutline.markdown(source)[1]

        guard case let .offset(offset) = chapter.target else { return XCTFail("expected an offset target") }
        XCTAssertEqual(offset, (source as NSString).range(of: "## A **Bold** Chapter").location)
    }

    // MARK: - EPUB

    func testTableOfContentsNestingBecomesDepth() {
        let toc = [
            link("Part One", "p1.html", [link("Chapter 1", "c1.html", [link("Scene", "c1.html#s")])]),
            link("Part Two", "p2.html"),
        ]

        let entries = BookOutline.fromTableOfContents(toc, readingOrder: [])

        XCTAssertEqual(entries.map(\.title), ["Part One", "Chapter 1", "Scene", "Part Two"])
        XCTAssertEqual(entries.map(\.depth), [0, 1, 2, 0])
        XCTAssertEqual(entries[2].target, .resource("c1.html#s"))
    }

    func testAnEmptyTableOfContentsFallsBackToTheReadingOrder() {
        let spine = [link("Cover", "cover.html"), link("Chapter 1", "c1.html")]

        let entries = BookOutline.fromTableOfContents([], readingOrder: spine)

        XCTAssertEqual(entries.map(\.title), ["Cover", "Chapter 1"])
        XCTAssertEqual(entries.map(\.depth), [0, 0])
    }

    func testUntitledReadingOrderResourcesAreNumbered() {
        let entries = BookOutline.fromTableOfContents([], readingOrder: [link(nil, "a.html"), link("  ", "b.html")])

        XCTAssertEqual(entries.map(\.title), ["Section 1", "Section 2"])
    }

    // MARK: - Current entry

    func testTheCurrentEntryIsTheLastOneAtOrBeforeTheReadingOffset() {
        let entries = BookOutline.plainText("Opening.\n\nCHAPTER I\n\nBody.\n\nCHAPTER II\n\nBody.\n")

        guard case let .offset(second) = entries[1].target else { return XCTFail("expected an offset target") }
        XCTAssertEqual(BookOutline.currentIndex(entries, offset: second), 1)
        XCTAssertEqual(BookOutline.currentIndex(entries, offset: second - 1), 0)
    }

    func testNothingIsCurrentBeforeTheFirstEntry() {
        let entries = BookOutline.plainText("Opening.\n\nCHAPTER I\n\nBody.\n\nCHAPTER II\n\nBody.\n")

        XCTAssertEqual(BookOutline.currentIndex(entries, offset: 0), -1)
    }

    func testTheCurrentEntryMatchesTheLocatorHrefIncludingItsFragment() {
        let entries = BookOutline.fromTableOfContents(
            [link("Start", "c1.html"), link("Scene", "c1.html#s"), link("Next", "c2.html")],
            readingOrder: []
        )

        XCTAssertEqual(BookOutline.currentIndex(entries, href: "c1.html#s"), 1)
        XCTAssertEqual(BookOutline.currentIndex(entries, href: "c2.html"), 2)
    }

    func testALocatorWithoutAFragmentMatchesTheLastEntryInThatResource() {
        let entries = BookOutline.fromTableOfContents(
            [link("Start", "c1.html"), link("Scene", "c1.html#s"), link("Next", "c2.html")],
            readingOrder: []
        )

        XCTAssertEqual(BookOutline.currentIndex(entries, href: "c1.html#unknown"), 1)
    }

    func testHrefsAreComparedByResourceNameSoALongerLocatorPathStillMatches() {
        let entries = BookOutline.fromTableOfContents(
            [link("Start", "OEBPS/c1.xhtml"), link("Next", "OEBPS/c2.xhtml")],
            readingOrder: []
        )

        XCTAssertEqual(BookOutline.currentIndex(entries, href: "/book/OEBPS/c2.xhtml"), 1)
    }

    func testAnUnknownHrefMarksNothingCurrent() {
        let entries = BookOutline.fromTableOfContents([link("Start", "c1.html")], readingOrder: [])

        XCTAssertEqual(BookOutline.currentIndex(entries, href: "gone.html"), -1)
    }

    func testAOneEntryOutlineIsNotWorthShowing() {
        XCTAssertFalse(BookOutline.isWorthShowing(BookOutline.fromTableOfContents([link("Only", "a.html")], readingOrder: [])))
        XCTAssertTrue(
            BookOutline.isWorthShowing(
                BookOutline.fromTableOfContents([link("One", "a.html"), link("Two", "b.html")], readingOrder: [])
            )
        )
    }
}
