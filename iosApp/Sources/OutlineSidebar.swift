import SwiftUI

/// The reader's table of contents. One list serves both presentations — a column beside the page
/// in landscape and a sheet over it in portrait — so the outline behaves the same however the
/// device is held.
struct OutlineList: View {
    let items: [OutlineItem]
    let currentIndex: Int
    let onSelect: (Int) -> Void

    var body: some View {
        if BookOutline.isWorthShowing(items) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                            row(item, index: index)
                        }
                    }
                    .padding(.vertical, 8)
                }
                .scrollIndicators(.hidden)
                // A long book opens hundreds of rows above the reader's place, so the outline is
                // useless unless it starts where they are.
                .onAppear {
                    guard currentIndex >= 0 else { return }
                    proxy.scrollTo(currentIndex, anchor: .center)
                }
                .onChange(of: currentIndex) { _, index in
                    guard index >= 0 else { return }
                    withAnimation { proxy.scrollTo(index, anchor: .center) }
                }
            }
        } else {
            ContentUnavailableView(
                "No sections in this book",
                systemImage: "list.bullet",
                description: Text("This document has no chapters Narratify can detect.")
            )
        }
    }

    private func row(_ item: OutlineItem, index: Int) -> some View {
        let isCurrent = index == currentIndex
        return Button { onSelect(index) } label: {
            Text(item.title)
                .font(.system(size: 15, design: .serif))
                .fontWeight(isCurrent ? .bold : .regular)
                .foregroundStyle(isCurrent ? Palette.ink : Palette.muted)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                // Depth is capped: a deeply nested outline would indent its titles off-screen.
                .padding(.leading, 16 + 16 * CGFloat(min(item.depth, 3)))
                .padding(.trailing, 16)
                .padding(.vertical, 10)
                .background(isCurrent ? Palette.lilac.opacity(0.35) : .clear)
        }
        .buttonStyle(.plain)
        .id(index)
        .accessibilityLabel(isCurrent ? "\(item.title), current section" : item.title)
    }
}

/// Landscape presentation: a column beside the page, so the outline can be read at the same time.
struct OutlineColumn: View {
    let items: [OutlineItem]
    let currentIndex: Int
    let onSelect: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("CONTENTS")
                .font(.caption.weight(.bold))
                .tracking(1.5)
                .foregroundStyle(Palette.muted)
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 10)
            Divider()
            OutlineList(items: items, currentIndex: currentIndex, onSelect: onSelect)
        }
        .background(Palette.surface)
    }
}

/// Portrait presentation: the same list as a sheet that closes on selection.
struct OutlineSheet: View {
    let items: [OutlineItem]
    let currentIndex: Int
    let onSelect: (Int) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            OutlineList(items: items, currentIndex: currentIndex) { index in
                onSelect(index)
                dismiss()
            }
            .background(Palette.surface)
            .navigationTitle("Contents")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// The button that opens the outline, shared by both readers.
struct OutlineToolbarButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) { Image(systemName: "list.bullet") }
            .accessibilityLabel("Contents")
    }
}
