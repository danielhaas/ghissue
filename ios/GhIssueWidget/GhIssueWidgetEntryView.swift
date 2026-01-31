import SwiftUI
import WidgetKit

struct GhIssueWidgetEntryView: View {
    var entry: GhIssueEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        Group {
            if entry.isConfigured {
                configuredView
            } else {
                notConfiguredView
            }
        }
        .containerBackground(.fill.tertiary, for: .widget)
    }

    private var configuredView: some View {
        let deepLink = URL(string: "\(Constants.urlScheme)://create?owner=\(entry.owner)&repo=\(entry.repo)")!

        return Link(destination: deepLink) {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Image(systemName: "plus.circle.fill")
                        .font(.title2)
                        .foregroundStyle(.accent)
                    if family == .systemMedium {
                        Text("Create Issue")
                            .font(.headline)
                    }
                }

                Text(entry.repo)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                if family == .systemMedium {
                    Text(entry.owner)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }

    private var notConfiguredView: some View {
        VStack(spacing: 4) {
            Image(systemName: "gear")
                .font(.title2)
                .foregroundStyle(.secondary)
            Text("Configure")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
