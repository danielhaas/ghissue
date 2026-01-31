import SwiftUI

struct LabelChipView: View {
    let name: String
    let colorHex: String
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Text(name)
            .font(.caption)
            .fontWeight(.medium)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color(hex: colorHex).opacity(isSelected ? 1.0 : 0.4))
            .foregroundStyle(isSelected ? Color.textColor(forBackgroundHex: colorHex) : .primary)
            .clipShape(Capsule())
            .overlay(
                Capsule()
                    .strokeBorder(Color(hex: colorHex), lineWidth: isSelected ? 0 : 1)
            )
            .onTapGesture(perform: onTap)
    }
}
