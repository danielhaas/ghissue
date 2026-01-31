import Foundation
import Observation

@Observable
final class CreateIssueViewModel {
    var title = ""
    var body = ""
    var labels: [LabelResponse] = []
    var selectedLabels: Set<String> = []
    var isSubmitting = false
    var isLoadingLabels = false
    var errorMessage: String?
    var successMessage: String?
    var queueMessage: String?
    var pendingCount = 0

    let owner: String
    let repo: String

    private let queueManager: IssueQueueManager

    init(owner: String, repo: String, queueManager: IssueQueueManager) {
        self.owner = owner
        self.repo = repo
        self.queueManager = queueManager
    }

    func loadLabels() async {
        guard let token = KeychainStore.accessToken else { return }
        isLoadingLabels = true
        do {
            labels = try await GitHubAPIClient.listLabels(owner: owner, repo: repo, token: token)
        } catch {
            // Silently skip if label fetch fails
        }
        isLoadingLabels = false
    }

    func refreshPendingCount() async {
        pendingCount = await queueManager.count()
    }

    func drainQueueIfNeeded() async {
        let count = await queueManager.count()
        guard count > 0 else { return }
        pendingCount = count

        let result = await queueManager.drainQueue()
        if result.submitted > 0 {
            queueMessage = "\(result.submitted) queued issue\(result.submitted == 1 ? "" : "s") submitted."
        }
        pendingCount = await queueManager.count()
    }

    func submit() async -> Bool {
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedTitle.isEmpty else {
            errorMessage = "Title is required."
            return false
        }

        let trimmedBody = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let bodyValue = trimmedBody.isEmpty ? nil : trimmedBody
        let labelList = selectedLabels.isEmpty ? nil : Array(selectedLabels)

        isSubmitting = true
        errorMessage = nil

        guard let token = KeychainStore.accessToken else {
            errorMessage = "Not logged in."
            isSubmitting = false
            return false
        }

        do {
            _ = try await GitHubAPIClient.createIssue(
                owner: owner,
                repo: repo,
                request: CreateIssueRequest(title: trimmedTitle, body: bodyValue, labels: labelList),
                token: token
            )
            successMessage = "Issue created."
            isSubmitting = false
            return true
        } catch let error as GitHubAPIError where error.isNetworkError {
            let queued = QueuedIssue(
                id: UUID().uuidString,
                owner: owner,
                repo: repo,
                title: trimmedTitle,
                body: bodyValue,
                labels: labelList,
                createdAt: Date()
            )
            await queueManager.enqueue(queued)
            let count = await queueManager.count()
            queueMessage = "Offline. Issue queued (\(count) pending)."
            isSubmitting = false
            return true
        } catch {
            errorMessage = "Error: \(error.localizedDescription)"
            isSubmitting = false
            return false
        }
    }

    func toggleLabel(_ name: String) {
        if selectedLabels.contains(name) {
            selectedLabels.remove(name)
        } else {
            selectedLabels.insert(name)
        }
    }
}
