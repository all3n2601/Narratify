import ReadiumNavigator
import ReadiumShared
import SwiftUI

struct EPUBReaderScreen: View {
    let book: LocalBook

    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.scenePhase) private var scenePhase
    @State private var session: EPUBReaderSession?
    @State private var errorMessage: String?
    @State private var showingSettings = false
    @State private var outline: [OutlineItem] = []
    @State private var outlineLinks: [String: ReadiumShared.Link] = [:]
    @State private var outlineColumnOpen = false
    @State private var showingOutlineSheet = false
    @State private var currentHref = ""
    @StateObject private var narration = EPUBNarrationController()

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
                    Button { narration.toggle() } label: { Image(systemName: narration.buttonIcon) }
                        .accessibilityLabel(narration.buttonLabel)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    OutlineToolbarButton { toggleOutline(landscape: landscape) }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingSettings = true } label: { Image(systemName: "textformat.size") }
                        .accessibilityLabel("Reading settings")
                }
            }
        }
        .background(Palette.canvas.ignoresSafeArea())
        .navigationTitle(book.title)
        .navigationBarTitleDisplayMode(.inline)
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
        .alert("Narration unavailable", isPresented: Binding(
            get: { narration.errorMessage != nil },
            set: { if !$0 { narration.clearError() } }
        )) {
            Button("OK", role: .cancel) { narration.clearError() }
        } message: {
            Text(narration.errorMessage ?? "Please try another voice.")
        }
        .task { await openPublication() }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = settings.keepScreenAwake
            outlineColumnOpen = settings.readerOutlineOpen
        }
        .onDisappear { narration.stop(); closeSession(); UIApplication.shared.isIdleTimerDisabled = false }
        .onChange(of: settings.speechRate) { _, rate in narration.rateMultiplier = rate }
        .onChange(of: settings.keepScreenAwake) { _, value in UIApplication.shared.isIdleTimerDisabled = value }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { flushPosition() }
        }
    }

    private var content: some View {
        Group {
            if let session {
                EPUBNavigatorView(navigator: session.navigator) { locator in
                    library.saveEPUBLocator(locator, for: book)
                    currentHref = locator.href.string
                }
            } else if let errorMessage {
                ContentUnavailableView(
                    "Unable to open EPUB",
                    systemImage: "books.vertical",
                    description: Text(errorMessage)
                )
            } else {
                ProgressView("Opening \(book.title)…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel("Opening EPUB")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var currentOutlineIndex: Int { BookOutline.currentIndex(outline, href: currentHref) }

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
        guard case let .resource(href) = outline[index].target, let link = outlineLinks[href] else { return }
        Task { _ = await session?.navigator.go(to: link) }
    }

    /// A publication that ships no table of contents still has a reading order, which is a
    /// coarser but honest outline. Neither is allowed to stop the book from opening.
    ///
    /// The manifest is read directly rather than through the async `tableOfContents()` service.
    /// That service only computes a table of contents for formats Narratify does not open (PDF);
    /// for EPUB it returns this same manifest value, and awaiting it would send a non-Sendable
    /// publication across isolation while the navigator still holds it.
    private func loadOutline(for publication: Publication) {
        let tableOfContents = publication.manifest.tableOfContents
        let readingOrder = publication.readingOrder
        outline = BookOutline.fromTableOfContents(
            convert(tableOfContents),
            readingOrder: convert(readingOrder)
        )
        var links: [String: ReadiumShared.Link] = [:]
        collect(tableOfContents, into: &links)
        collect(readingOrder, into: &links)
        outlineLinks = links
    }

    private func convert(_ links: [ReadiumShared.Link]) -> [OutlineLink] {
        links.map { OutlineLink(title: $0.title, href: $0.href, children: convert($0.children)) }
    }

    private func collect(_ links: [ReadiumShared.Link], into store: inout [String: ReadiumShared.Link]) {
        for link in links {
            if store[link.href] == nil { store[link.href] = link }
            collect(link.children, into: &store)
        }
    }

    private func openPublication() async {
        guard session == nil, errorMessage == nil else { return }
        do {
            let publication = try await library.openEPUB(for: book)
            guard publication.conforms(to: .epub), !publication.isRestricted else {
                throw LocalLibraryError.protectedEPUB
            }
            let navigator = try EPUBNavigatorViewController(
                publication: publication,
                initialLocation: library.epubLocator(for: book)
            )
            narration.configure(
                publication: publication,
                navigator: navigator,
                preferredVoiceID: settings.preferredVoiceID,
                rateMultiplier: settings.speechRate
            )
            session = EPUBReaderSession(publication: publication, navigator: navigator)
            currentHref = navigator.currentLocation?.href.string ?? ""
            loadOutline(for: publication)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func flushPosition() {
        if let locator = session?.navigator.currentLocation {
            library.saveEPUBLocator(locator, for: book)
        }
    }

    private func closeSession() {
        flushPosition()
        session?.navigator.delegate = nil
        session = nil
    }
}

/// Owns the Readium publication for exactly as long as its navigator. Readium
/// 3.11 releases archive resources on deallocation; its former `close()` API is
/// deprecated, so dropping this session is the supported cleanup path.
@MainActor
private final class EPUBReaderSession {
    let publication: Publication
    let navigator: EPUBNavigatorViewController

    init(publication: Publication, navigator: EPUBNavigatorViewController) {
        self.publication = publication
        self.navigator = navigator
    }
}

private struct EPUBNavigatorView: UIViewControllerRepresentable {
    let navigator: EPUBNavigatorViewController
    let onLocation: @MainActor (Locator) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onLocation: onLocation) }

    func makeUIViewController(context: Context) -> EPUBNavigatorViewController {
        navigator.delegate = context.coordinator
        return navigator
    }

    func updateUIViewController(_ controller: EPUBNavigatorViewController, context: Context) {
        controller.delegate = context.coordinator
        context.coordinator.onLocation = onLocation
    }

    static func dismantleUIViewController(
        _ controller: EPUBNavigatorViewController,
        coordinator: Coordinator
    ) {
        controller.delegate = nil
        coordinator.cancelPendingSave()
    }

    @MainActor
    final class Coordinator: NSObject, EPUBNavigatorDelegate {
        var onLocation: @MainActor (Locator) -> Void
        private var saveTask: Task<Void, Never>?

        init(onLocation: @escaping @MainActor (Locator) -> Void) {
            self.onLocation = onLocation
        }

        func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
            saveTask?.cancel()
            saveTask = Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(350))
                guard !Task.isCancelled else { return }
                onLocation(locator)
            }
        }

        func navigator(_ navigator: Navigator, presentError error: NavigatorError) {
            // Readium owns the rendering surface; non-fatal navigator errors are
            // intentionally not converted into destructive navigation changes.
        }

        func cancelPendingSave() {
            saveTask?.cancel()
            saveTask = nil
        }
    }
}
