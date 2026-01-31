import AppIntents
import WidgetKit

struct RepoConfigurationIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Select Repository"
    static var description: IntentDescription = "Choose which repository to create issues in."

    @Parameter(title: "Repository")
    var repo: RepoEntity?
}
