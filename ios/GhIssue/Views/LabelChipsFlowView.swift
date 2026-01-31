import SwiftUI

struct LabelChipsFlowView: View {
    let labels: [LabelResponse]
    let selectedLabels: Set<String>
    let onToggle: (String) -> Void

    var body: some View {
        FlowLayout(spacing: 6) {
            ForEach(labels) { label in
                LabelChipView(
                    name: label.name,
                    colorHex: label.color,
                    isSelected: selectedLabels.contains(label.name),
                    onTap: { onToggle(label.name) }
                )
            }
        }
    }
}
