import Foundation
import Observation

@Observable
final class RepoViewModel {
    var repos: [RepoResponse] = []
    var isLoading = false
    var errorMessage: String?

    var selectedOwner: String {
        get { PreferencesStore.repoOwner }
        set { PreferencesStore.repoOwner = newValue }
    }

    var selectedName: String {
        get { PreferencesStore.repoName }
        set { PreferencesStore.repoName = newValue }
    }

    var selectedFullName: String {
        guard !selectedOwner.isEmpty, !selectedName.isEmpty else { return "" }
        return "\(selectedOwner)/\(selectedName)"
    }

    var hasSelection: Bool {
        !selectedOwner.isEmpty && !selectedName.isEmpty
    }

    func fetchRepos() async {
        guard let token = KeychainStore.accessToken else {
            errorMessage = "Not logged in."
            return
        }

        isLoading = true
        errorMessage = nil

        do {
            let fetched = try await GitHubAPIClient.listRepos(token: token)
            repos = fetched
            // Cache for widget
            PreferencesStore.cachedRepos = fetched.map { CachedRepo(owner: $0.owner.login, name: $0.name) }
        } catch {
            errorMessage = "Failed to load repos: \(error.localizedDescription)"
        }

        isLoading = false
    }

    func selectRepo(_ repo: RepoResponse) {
        selectedOwner = repo.owner.login
        selectedName = repo.name
    }
}
