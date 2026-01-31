import SwiftUI
import WidgetKit

struct GhIssueWidget: Widget {
    let kind = "GhIssueWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: kind,
            intent: RepoConfigurationIntent.self,
            provider: GhIssueTimelineProvider()
        ) { entry in
            GhIssueWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Create Issue")
        .description("Quickly create a GitHub issue.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
