import Foundation

actor IssueQueueManager {
    private let defaults = UserDefaults(suiteName: Constants.appGroupID) ?? .standard
    private let queueKey = "queued_issues"

    struct DrainResult {
        let submitted: Int
        let failed: Int
        let stopReason: String?
    }

    func enqueue(_ issue: QueuedIssue) {
        var list = getAll()
        list.append(issue)
        save(list)
    }

    func remove(id: String) {
        let list = getAll().filter { $0.id != id }
        save(list)
    }

    func getAll() -> [QueuedIssue] {
        guard let data = defaults.data(forKey: queueKey) else { return [] }
        return (try? JSONDecoder().decode([QueuedIssue].self, from: data)) ?? []
    }

    func count() -> Int {
        getAll().count
    }

    func drainQueue() async -> DrainResult {
        guard let token = KeychainStore.accessToken else {
            return DrainResult(submitted: 0, failed: 0, stopReason: "auth")
        }

        var submitted = 0
        var failed = 0
        var stopReason: String?

        let queue = getAll()
        for issue in queue {
            do {
                _ = try await GitHubAPIClient.createIssue(
                    owner: issue.owner,
                    repo: issue.repo,
                    request: CreateIssueRequest(
                        title: issue.title,
                        body: issue.body,
                        labels: issue.labels
                    ),
                    token: token
                )
                remove(id: issue.id)
                submitted += 1
            } catch let error as GitHubAPIError {
                switch error {
                case .networkError:
                    stopReason = "network"
                    failed += 1
                    return DrainResult(submitted: submitted, failed: failed, stopReason: stopReason)
                case .unauthorized:
                    stopReason = "auth"
                    failed += 1
                    return DrainResult(submitted: submitted, failed: failed, stopReason: stopReason)
                case .httpError:
                    remove(id: issue.id)
                    failed += 1
                }
            } catch {
                stopReason = "network"
                failed += 1
                return DrainResult(submitted: submitted, failed: failed, stopReason: stopReason)
            }
        }

        return DrainResult(submitted: submitted, failed: failed, stopReason: stopReason)
    }

    // MARK: - Private

    private func save(_ issues: [QueuedIssue]) {
        let data = try? JSONEncoder().encode(issues)
        defaults.set(data, forKey: queueKey)
    }
}
