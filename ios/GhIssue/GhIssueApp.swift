import SwiftUI

@main
struct GhIssueApp: App {
    @State private var networkMonitor = NetworkMonitor()
    @State private var authViewModel = AuthViewModel()
    @State private var deepLinkOwner: String?
    @State private var deepLinkRepo: String?
    private let queueManager = IssueQueueManager()

    var body: some Scene {
        WindowGroup {
            MainView(
                authViewModel: authViewModel,
                queueManager: queueManager,
                deepLinkOwner: $deepLinkOwner,
                deepLinkRepo: $deepLinkRepo
            )
            .onOpenURL { url in
                handleDeepLink(url)
            }
            .task {
                setupNetworkDrain()
            }
        }
    }

    private func handleDeepLink(_ url: URL) {
        guard url.scheme == Constants.urlScheme,
              url.host == "create",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let owner = components.queryItems?.first(where: { $0.name == "owner" })?.value,
              let repo = components.queryItems?.first(where: { $0.name == "repo" })?.value
        else { return }

        deepLinkOwner = owner
        deepLinkRepo = repo
    }

    private func setupNetworkDrain() {
        networkMonitor.onConnectivityRestored = {
            Task {
                let count = await queueManager.count()
                guard count > 0 else { return }
                let result = await queueManager.drainQueue()
                if result.submitted > 0 {
                    print("Auto-submitted \(result.submitted) queued issue(s)")
                }
            }
        }
    }
}
