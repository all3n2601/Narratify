import SwiftUI

struct BrandMark: View {
    var size: CGFloat = 44

    var body: some View {
        Image("BrandLogo")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityLabel("Narratify")
    }
}

struct BottomBar: View {
    @Binding var selection: AppSection

    var body: some View {
        HStack {
            ForEach(AppSection.allCases) { section in
                NavLabel(section: section, selected: selection == section) { selection = section }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
        .overlay(alignment: .top) { Divider().opacity(0.5) }
    }
}

struct Sidebar: View {
    @Binding var selection: AppSection

    var body: some View {
        VStack(spacing: 20) {
            BrandMark(size: 52)
            NavLabel(section: .library, selected: selection == .library) { selection = .library }
            NavLabel(section: .discover, selected: selection == .discover) { selection = .discover }
            NavLabel(section: .voices, selected: selection == .voices) { selection = .voices }
            Spacer()
            NavLabel(section: .settings, selected: selection == .settings) { selection = .settings }
        }
        .padding(.vertical, 26)
        .padding(.horizontal, 12)
        .background(.ultraThinMaterial)
        .overlay(alignment: .trailing) { Divider().opacity(0.5) }
    }
}

private struct NavLabel: View {
    let section: AppSection
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: section.icon)
                    .font(.title3.weight(.semibold))
                Text(section.title)
                    .font(.caption.weight(selected ? .bold : .regular))
            }
            .foregroundStyle(selected ? Palette.violet : Palette.muted)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background(selected ? Palette.violet.opacity(0.09) : .clear, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(selected ? "\(section.title), selected" : section.title)
    }
}
