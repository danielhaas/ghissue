import WidgetKit

struct GhIssueEntry: TimelineEntry {
    let date: Date
    let owner: String
    let repo: String
    let isConfigured: Bool
}

struct GhIssueTimelineProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> GhIssueEntry {
        GhIssueEntry(date: .now, owner: "owner", repo: "repo", isConfigured: true)
    }

    func snapshot(for configuration: RepoConfigurationIntent, in context: Context) async -> GhIssueEntry {
        entry(for: configuration)
    }

    func timeline(for configuration: RepoConfigurationIntent, in context: Context) async -> Timeline<GhIssueEntry> {
        let entry = entry(for: configuration)
        return Timeline(entries: [entry], policy: .never)
    }

    private func entry(for configuration: RepoConfigurationIntent) -> GhIssueEntry {
        if let repo = configuration.repo {
            return GhIssueEntry(date: .now, owner: repo.owner, repo: repo.name, isConfigured: true)
        }

        let owner = PreferencesStore.repoOwner
        let name = PreferencesStore.repoName
        if !owner.isEmpty, !name.isEmpty {
            return GhIssueEntry(date: .now, owner: owner, repo: name, isConfigured: true)
        }

        return GhIssueEntry(date: .now, owner: "", repo: "", isConfigured: false)
    }
}
