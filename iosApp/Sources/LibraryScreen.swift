import SwiftUI
import UniformTypeIdentifiers

struct LibraryScreen: View {
    @EnvironmentObject private var libraryStore: LibraryStore
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var showingImporter = false
    @State private var importError: String?
    @State private var appeared = false
    @State private var searchText = ""

    var body: some View {
        library
            .background(Backdrop().ignoresSafeArea())
            .fileImporter(
                isPresented: $showingImporter,
                allowedContentTypes: LibraryStore.supportedContentTypes,
                allowsMultipleSelection: true,
                onCompletion: handleImport
            )
            .alert("Couldn’t import document", isPresented: Binding(
                get: { importError != nil },
                set: { if !$0 { importError = nil } }
            )) {
                Button("Got it", role: .cancel) {}
            } message: {
                Text(importError ?? "Please try another file.")
            }
            .alert("Reading position not saved", isPresented: Binding(
                get: { libraryStore.persistenceNotice != nil },
                set: { if !$0 { libraryStore.clearPersistenceNotice() } }
            )) {
                Button("OK", role: .cancel) { libraryStore.clearPersistenceNotice() }
            } message: {
                Text(libraryStore.persistenceNotice ?? "Please try again.")
            }
            .onAppear {
                guard !reduceMotion else { appeared = true; return }
                withAnimation(.easeOut(duration: 0.55)) { appeared = true }
            }
            .searchable(text: $searchText, prompt: "Search your library")
    }

    private var library: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                header
                privacyBanner
                HStack(alignment: .firstTextBaseline) {
                    Text("Your books")
                        .font(.system(.title2, design: .rounded, weight: .bold))
                    Spacer()
                    Text("\(libraryStore.books.count) local")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Palette.violet)
                }
                libraryControls
                if filteredBooks.isEmpty && searchText.isEmpty {
                    EmptyLibraryCard { showingImporter = true }
                } else if filteredBooks.isEmpty {
                    ContentUnavailableView.search(text: searchText)
                } else {
                    LocalBookGrid(books: filteredBooks)
                }
            }
            .padding(.horizontal, sizeClass == .regular ? 44 : 20)
            .padding(.top, sizeClass == .regular ? 28 : 14)
            .padding(.bottom, 32)
            .frame(maxWidth: 1180, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .scrollIndicators(.hidden)
        .opacity(appeared ? 1 : 0)
        .offset(y: appeared ? 0 : 14)
    }

    private var filteredBooks: [LocalBook] {
        let ordered = settings.newestFirst
            ? libraryStore.books
            : libraryStore.books.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        guard !searchText.isEmpty else { return ordered }
        return ordered.filter {
            $0.title.localizedCaseInsensitiveContains(searchText)
                || $0.originalFilename.localizedCaseInsensitiveContains(searchText)
        }
    }

    private var libraryControls: some View {
        HStack(spacing: 10) {
            Button(settings.newestFirst ? "Newest first" : "Title A–Z") { settings.newestFirst.toggle() }
            Spacer()
            Button(settings.compactLibrary ? "Compact" : "Comfortable") { settings.compactLibrary.toggle() }
        }
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(Palette.violet)
    }

    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    BrandMark(size: 34)
                    Text("NARRATIFY")
                        .font(.caption.weight(.heavy))
                        .tracking(3)
                        .foregroundStyle(Palette.violet)
                        .accessibilityHidden(true)
                }
                Text("Your library")
                    .font(.system(.largeTitle, design: .rounded, weight: .bold))
                    .foregroundStyle(Palette.ink)
            }
            Spacer()
            Button { showingImporter = true } label: {
                Image(systemName: "plus")
                    .font(.title2.weight(.semibold))
                    .frame(width: 50, height: 50)
                    .foregroundStyle(.white)
                    .background(Palette.violet, in: Circle())
                    .shadow(color: Palette.violet.opacity(0.28), radius: 10, y: 5)
            }
            .accessibilityLabel("Import local documents")
            .accessibilityHint("Choose text, Markdown, or unencrypted EPUB files from Files")
        }
    }

    private var privacyBanner: some View {
        Label("TXT, Markdown, and EPUB stay on this device", systemImage: "lock.fill")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Palette.violet)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 18)
            .frame(minHeight: 54)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(Palette.violet.opacity(0.10)))
            .accessibilityLabel("Local library. Imported text, Markdown, and unencrypted EPUB documents stay on this device.")
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        Task {
            do {
                for url in try result.get() { try await libraryStore.importDocument(from: url) }
            } catch {
                importError = error.localizedDescription
            }
        }
    }
}

struct Backdrop: View {
    var body: some View {
        ZStack {
            Palette.canvas
            Circle()
                .fill(Palette.violet.opacity(0.045))
                .frame(width: 520, height: 520)
                .offset(x: 240, y: -300)
            Circle()
                .fill(Color.white.opacity(0.35))
                .frame(width: 430, height: 430)
                .offset(x: -230, y: 430)
        }
    }
}

private struct EmptyLibraryCard: View {
    let importAction: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: "text.book.closed")
                .font(.title)
                .foregroundStyle(Palette.violet)
            Text("Bring your own reading")
                .font(.system(.title3, design: .rounded, weight: .bold))
            Text("Import an unencrypted TXT, Markdown, or EPUB file, or use Discover to add a public-domain EPUB.")
                .font(.body)
                .foregroundStyle(Palette.muted)
            Button("Choose local files", action: importAction)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
        }
        .padding(22)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(Palette.violet.opacity(0.12)))
    }
}

private struct LocalBookGrid: View {
    let books: [LocalBook]
    @EnvironmentObject private var libraryStore: LibraryStore
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 18) {
            ForEach(books) { book in
                NavigationLink(value: book) {
                    HStack(spacing: 14) {
                        Image(systemName: book.format == .epub ? "books.vertical.fill" : (book.format == .markdown ? "text.document.fill" : "doc.text.fill"))
                            .font(.title2)
                            .foregroundStyle(.white)
                            .frame(width: 54, height: 72)
                            .background(Palette.violet.gradient, in: RoundedRectangle(cornerRadius: 10))
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 5) {
                            Text(book.title)
                                .font(.headline)
                                .foregroundStyle(Palette.ink)
                                .lineLimit(2)
                            Text(book.format.label)
                                .font(.subheadline)
                                .foregroundStyle(Palette.muted)
                            if let position = libraryStore.position(for: book) {
                                Text("\(Int((position.progression * 100).rounded()))% read")
                                    .font(.caption)
                                    .foregroundStyle(Palette.violet)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(14)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open \(book.title), \(book.format.label) document")
            }
        }
    }

    private var columns: [GridItem] {
        let regularCount = settings.compactLibrary ? 3 : 2
        let compactCount = settings.compactLibrary ? 2 : 1
        let count = dynamicTypeSize.isAccessibilitySize ? 1 : (sizeClass == .regular ? regularCount : compactCount)
        return Array(repeating: GridItem(.flexible(), spacing: 16), count: count)
    }
}
