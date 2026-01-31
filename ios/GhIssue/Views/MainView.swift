import SwiftUI

struct MainView: View {
    @State var authViewModel: AuthViewModel
    @State var repoViewModel = RepoViewModel()
    @State private var showRepoSheet = false
    @State private var showCreateIssueSheet = false
    @State private var createIssueOwner = ""
    @State private var createIssueRepo = ""

    let queueManager: IssueQueueManager
    @Binding var deepLinkOwner: String?
    @Binding var deepLinkRepo: String?

    var body: some View {
        NavigationStack {
            Form {
                OAuthLoginSection(viewModel: authViewModel)

                if authViewModel.isLoggedIn {
                    repoSection
                    clientIdSection
                }
            }
            .navigationTitle("GhIssue")
            .sheet(isPresented: $showRepoSheet) {
                RepoSelectionView(viewModel: repoViewModel)
            }
            .sheet(isPresented: $showCreateIssueSheet) {
                CreateIssueView(
                    viewModel: CreateIssueViewModel(
                        owner: createIssueOwner,
                        repo: createIssueRepo,
                        queueManager: queueManager
                    )
                )
            }
            .onChange(of: deepLinkOwner) {
                handleDeepLinkChange()
            }
            .onChange(of: deepLinkRepo) {
                handleDeepLinkChange()
            }
        }
    }

    // MARK: - Subviews

    private var repoSection: some View {
        Section {
            if repoViewModel.hasSelection {
                HStack {
                    Label(repoViewModel.selectedFullName, systemImage: "folder")
                    Spacer()
                    Button("Change") { showRepoSheet = true }
                }
            } else {
                Button("Select Repository") { showRepoSheet = true }
            }

            if repoViewModel.hasSelection {
                Button("Create Issue") {
                    createIssueOwner = repoViewModel.selectedOwner
                    createIssueRepo = repoViewModel.selectedName
                    showCreateIssueSheet = true
                }
            }
        } header: {
            Text("Repository")
        }
    }

    private var clientIdSection: some View {
        Section {
            LabeledContent("Client ID") {
                Text(PreferencesStore.clientId)
                    .foregroundStyle(.secondary)
                    .font(.caption)
            }
        } header: {
            Text("Configuration")
        } footer: {
            Text("OAuth Client ID from your GitHub OAuth App.")
        }
    }

    // MARK: - Deep Link

    private func handleDeepLinkChange() {
        guard let owner = deepLinkOwner, let repo = deepLinkRepo else { return }
        createIssueOwner = owner
        createIssueRepo = repo
        showCreateIssueSheet = true
        deepLinkOwner = nil
        deepLinkRepo = nil
    }
}
