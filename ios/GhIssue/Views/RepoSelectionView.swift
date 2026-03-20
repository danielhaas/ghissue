import SwiftUI

struct RepoSelectionView: View {
    @Bindable var viewModel: RepoViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading {
                    ProgressView("Loading repositories...")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let error = viewModel.errorMessage {
                    ContentUnavailableView {
                        Label("Error", systemImage: "exclamationmark.triangle")
                    } description: {
                        Text(error)
                    } actions: {
                        Button("Retry") {
                            Task { await viewModel.fetchRepos() }
                        }
                    }
                } else if viewModel.repos.isEmpty {
                    ContentUnavailableView(
                        "No Repositories",
                        systemImage: "tray",
                        description: Text("No repositories found.")
                    )
                } else {
                    repoList
                }
            }
            .navigationTitle("Select Repository")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .task {
            if viewModel.repos.isEmpty {
                await viewModel.fetchRepos()
            }
        }
    }

    private var repoList: some View {
        let repos: [RepoResponse] = viewModel.repos
        return List {
            ForEach(repos, id: \.id) { (repo: RepoResponse) in
                Button {
                    viewModel.selectRepo(repo)
                    dismiss()
                } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(repo.name)
                                .font(.body)
                            Text(repo.owner.login)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if repo.owner.login == viewModel.selectedOwner && repo.name == viewModel.selectedName {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .foregroundStyle(.primary)
            }
        }
    }
}
