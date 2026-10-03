import SwiftUI

struct CatalogScreen: View {
    @EnvironmentObject private var libraryStore: LibraryStore
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var query = ""
    @State private var books: [CatalogBook] = []
    @State private var isSearching = false
    @State private var importingID: String?
    @State private var status = "Search across five open book catalogs."
    @State private var errorMessage: String?
    @State private var searchTask: Task<Void, Never>?
    @FocusState private var searchFieldFocused: Bool

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                PageHeader(
                    eyebrow: "OPEN CATALOG",
                    title: "Discover books",
                    detail: "One search across Project Gutenberg, Open Library, Google Books, Internet Archive, and LibriVox. Narratify only imports direct public EPUB files."
                )
                searchControls
                HStack(spacing: 10) {
                    if isSearching { ProgressView() }
                    Text(status).font(.subheadline).foregroundStyle(Palette.muted)
                }
                ForEach(books) { book in
                    CatalogResultCard(
                        book: book,
                        isImporting: importingID == book.id,
                        addAction: { add(book) }
                    )
                }
            }
            .padding(.horizontal, sizeClass == .regular ? 44 : 20)
            .padding(.vertical, sizeClass == .regular ? 28 : 18)
            .frame(maxWidth: 920)
            .frame(maxWidth: .infinity)
        }
        .scrollIndicators(.hidden)
        .background(Backdrop().ignoresSafeArea())
        .onDisappear {
            searchTask?.cancel()
            searchTask = nil
            if isSearching {
                isSearching = false
                status = "Search cancelled. Try another search."
            }
        }
        .alert("Couldn’t add that book", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "Please try again.")
        }
    }

    private var searchControls: some View {
        HStack(spacing: 10) {
            TextField("Title or author", text: $query)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.search)
                .focused($searchFieldFocused)
                .onSubmit(search)
            Button("Search", action: search)
                .buttonStyle(.borderedProminent)
                .disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).count < 2 || isSearching)
        }
    }

    private func search() {
        let searchQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard searchQuery.count >= 2 else {
            status = "Enter at least two characters."
            return
        }
        searchTask?.cancel()
        searchFieldFocused = false
        isSearching = true
        books = []
        status = "Searching catalogs…"
        searchTask = Task {
            let result = await BookCatalogService.search(searchQuery)
            guard !Task.isCancelled else { return }
            books = result.books
            isSearching = false
            if result.books.isEmpty {
                status = result.failedProviderCount > 0
                    ? "No results. \(result.failedProviderCount) sources were unavailable."
                    : "No results. Try another search."
            } else if result.failedProviderCount > 0 {
                status = "\(result.books.count) combined results · \(result.failedProviderCount) source failures"
            } else {
                status = "\(result.books.count) combined results"
            }
            searchTask = nil
        }
    }

    private func add(_ book: CatalogBook) {
        guard let epubURL = book.epubURL else { return }
        importingID = book.id
        Task {
            do {
                let imported = try await libraryStore.importRemoteEPUB(from: epubURL, suggestedTitle: book.title)
                status = "Added \(imported.title) to your library."
            } catch {
                errorMessage = error.localizedDescription
            }
            importingID = nil
        }
    }
}

private struct CatalogResultCard: View {
    let book: CatalogBook
    let isImporting: Bool
    let addAction: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            cover
            VStack(alignment: .leading, spacing: 6) {
                Text(book.title).font(.headline).foregroundStyle(Palette.ink)
                Text(book.author).font(.subheadline).foregroundStyle(Palette.muted)
                Text(book.sources).font(.caption.weight(.bold)).foregroundStyle(Palette.violet)
                if let note = book.note {
                    Text(note).font(.caption).foregroundStyle(Palette.muted)
                }
                HStack {
                    Link("View source", destination: book.detailURL)
                    if book.epubURL != nil {
                        Button(isImporting ? "Adding…" : "Add EPUB", action: addAction)
                            .buttonStyle(.borderedProminent)
                            .disabled(isImporting)
                    }
                }
                .font(.subheadline.weight(.semibold))
                .padding(.top, 4)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Palette.violet.opacity(0.10)))
    }

    @ViewBuilder private var cover: some View {
        if let coverURL = book.coverURL {
            AsyncImage(url: coverURL) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Image(systemName: "book.closed.fill").foregroundStyle(Palette.violet)
            }
            .frame(width: 58, height: 82)
            .background(Palette.violet.opacity(0.08))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        } else {
            Image(systemName: "book.closed.fill")
                .font(.title2)
                .foregroundStyle(.white)
                .frame(width: 58, height: 82)
                .background(Palette.violet.gradient, in: RoundedRectangle(cornerRadius: 8))
        }
    }
}
