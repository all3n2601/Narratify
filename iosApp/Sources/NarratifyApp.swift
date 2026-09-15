import SwiftUI

@main
struct NarratifyApp: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var settings = AppSettings()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                AppRootScreen()
                    .navigationDestination(for: LocalBook.self) { book in
                        if book.format == .epub {
                            EPUBReaderScreen(book: book)
                        } else {
                            ReaderScreen(book: book)
                        }
                    }
            }
            .environmentObject(library)
            .environmentObject(settings)
            .tint(Palette.violet)
        }
    }
}

enum Palette {
    static let canvas = Color(red: 0.965, green: 0.949, blue: 0.914)
    static let surface = Color(red: 1.0, green: 0.988, blue: 0.961)
    static let ink = Color(red: 0.12, green: 0.11, blue: 0.10)
    static let muted = Color(red: 0.40, green: 0.37, blue: 0.33)
    static let violet = Color(red: 0.40, green: 0.33, blue: 0.56)
    static let lilac = Color(red: 0.78, green: 0.72, blue: 0.92)
}
