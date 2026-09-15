import SwiftUI

private struct ReaderBlock: Identifiable {
    let offset: Int
    let source: String
    let rendered: AttributedString
    var id: Int { offset }
}

private struct BlockOffsetPreference: PreferenceKey {
    static let defaultValue: [Int: CGFloat] = [:]
    static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

struct ReaderScreen: View {
    let book: LocalBook

    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.scenePhase) private var scenePhase

    @State private var text = ""
    @State private var blocks: [ReaderBlock] = []
    @State private var activeOffset = 0
    @State private var restored = false
    @State private var errorMessage: String?
    @State private var saveTask: Task<Void, Never>?
    @State private var showingSettings = false
    @State private var outline: [OutlineItem] = []
    @State private var outlineColumnOpen = false
    @State private var showingOutlineSheet = false
    @State private var scrollRequest: Int?

    var body: some View {
        // Orientation, not size class: an iPhone stays horizontally compact in landscape, but it
        // still has the width for a column beside the page.
        GeometryReader { geometry in
            let landscape = geometry.size.width > geometry.size.height
            HStack(spacing: 0) {
                if landscape, outlineColumnOpen {
                    OutlineColumn(items: outline, currentIndex: currentOutlineIndex, onSelect: openOutlineEntry)
                        .frame(width: min(320, geometry.size.width * 0.32))
                    Divider()
                }
                content
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    OutlineToolbarButton { toggleOutline(landscape: landscape) }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingSettings = true } label: { Image(systemName: "textformat.size") }
                        .accessibilityLabel("Reading settings")
                }
            }
        }
        .background(settings.readingTheme.background.ignoresSafeArea())
        .navigationTitle(book.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .sheet(isPresented: $showingSettings) { ReaderQuickSettings() }
        .sheet(isPresented: $showingOutlineSheet) {
            OutlineSheet(items: outline, currentIndex: currentOutlineIndex, onSelect: openOutlineEntry)
        }
        .alert("Reading position not saved", isPresented: Binding(
            get: { library.persistenceNotice != nil },
            set: { if !$0 { library.clearPersistenceNotice() } }
        )) {
            Button("OK", role: .cancel) { library.clearPersistenceNotice() }
        } message: {
            Text(library.persistenceNotice ?? "Please try again.")
        }
        .onAppear { load(); updateIdleTimer(); outlineColumnOpen = settings.readerOutlineOpen }
        .onDisappear { flushPosition(); UIApplication.shared.isIdleTimerDisabled = false }
        .onChange(of: settings.keepScreenAwake) { _, _ in updateIdleTimer() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { flushPosition() }
        }
    }

    private var content: some View {
        Group {
            if let errorMessage {
                ContentUnavailableView(
                    "Unable to open document",
                    systemImage: "doc.text.magnifyingglass",
                    description: Text(errorMessage)
                )
            } else {
                reader
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var currentOutlineIndex: Int { BookOutline.currentIndex(outline, offset: activeOffset) }

    /// Landscape shows the outline beside the page; portrait has no width to spare and shows it
    /// over the page instead.
    private func toggleOutline(landscape: Bool) {
        if landscape {
            outlineColumnOpen.toggle()
            settings.readerOutlineOpen = outlineColumnOpen
        } else {
            showingOutlineSheet = true
        }
    }

    private func openOutlineEntry(_ index: Int) {
        guard case let .offset(offset) = outline[index].target else { return }
        scrollRequest = offset
    }

    private var reader: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 20) {
                    documentHeader
                    ForEach(blocks) { block in
                        Text(block.rendered)
                            .font(.system(size: settings.fontSize, design: .serif))
                            .lineSpacing(settings.lineSpacing)
                            .foregroundStyle(settings.readingTheme.text)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(block.id)
                            .background {
                                GeometryReader { geometry in
                                    Color.clear.preference(
                                        key: BlockOffsetPreference.self,
                                        value: [block.offset: geometry.frame(in: .named("readerScroll")).minY]
                                    )
                                }
                            }
                            .accessibilityLabel(block.source)
                    }
                    Color.clear.frame(height: 80)
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 28)
                .frame(maxWidth: 760)
                .frame(maxWidth: .infinity)
            }
            .scrollIndicators(.hidden)
            .coordinateSpace(name: "readerScroll")
            .onPreferenceChange(BlockOffsetPreference.self) { offsets in
                guard restored, let nearest = offsets.min(by: { abs($0.value) < abs($1.value) })?.key,
                      nearest != activeOffset else { return }
                activeOffset = nearest
                schedulePositionSave()
            }
            .onChange(of: scrollRequest) { _, request in
                guard let request, let target = blocks.last(where: { $0.offset <= request })?.id ?? blocks.first?.id
                else { return }
                withAnimation { proxy.scrollTo(target, anchor: .top) }
                activeOffset = target
                scrollRequest = nil
                schedulePositionSave()
            }
            .onChange(of: blocks.count) { _, count in
                guard count > 0, !restored else { return }
                let desired = library.resolvedOffset(for: book, in: text)
                let target = blocks.last(where: { $0.offset <= desired })?.id ?? blocks[0].id
                proxy.scrollTo(target, anchor: .top)
                activeOffset = target
                restored = true
            }
        }
    }

    private var documentHeader: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(book.format.label.uppercased())
                .font(.caption.weight(.bold))
                .tracking(1.5)
                .foregroundStyle(Palette.violet)
            Text(book.title)
                .font(.system(.largeTitle, design: .serif, weight: .bold))
                .foregroundStyle(Palette.ink)
            Label("Stored only on this device", systemImage: "lock.fill")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Palette.muted)
        }
        .padding(.bottom, 16)
        .accessibilityElement(children: .combine)
    }

    private func load() {
        guard blocks.isEmpty else { return }
        do {
            text = try library.text(for: book)
            blocks = Self.makeBlocks(text: text, format: book.format)
            outline = book.format == .markdown ? BookOutline.markdown(text) : BookOutline.plainText(text)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = settings.keepScreenAwake
    }

    private func schedulePositionSave() {
        saveTask?.cancel()
        let offset = activeOffset
        saveTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            library.savePosition(LibraryStore.makePosition(offset: offset, in: text), for: book)
        }
    }

    private func flushPosition() {
        saveTask?.cancel()
        guard !text.isEmpty else { return }
        library.savePosition(LibraryStore.makePosition(offset: activeOffset, in: text), for: book)
    }

    private static func makeBlocks(text: String, format: LocalBookFormat) -> [ReaderBlock] {
        let source = text as NSString
        let separator = try! NSRegularExpression(pattern: "(?:\\r?\\n){2,}")
        let matches = separator.matches(in: text, range: NSRange(location: 0, length: source.length))
        var ranges: [NSRange] = []
        var cursor = 0
        for match in matches {
            if match.range.location > cursor {
                ranges.append(NSRange(location: cursor, length: match.range.location - cursor))
            }
            cursor = NSMaxRange(match.range)
        }
        if cursor < source.length { ranges.append(NSRange(location: cursor, length: source.length - cursor)) }

        return ranges.compactMap { range in
            let raw = source.substring(with: range).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !raw.isEmpty else { return nil }
            let rendered: AttributedString
            if format == .markdown {
                rendered = (try? AttributedString(
                    markdown: raw,
                    options: .init(interpretedSyntax: .full)
                )) ?? AttributedString(raw)
            } else {
                rendered = AttributedString(raw)
            }
            return ReaderBlock(offset: range.location, source: raw, rendered: rendered)
        }
    }
}
