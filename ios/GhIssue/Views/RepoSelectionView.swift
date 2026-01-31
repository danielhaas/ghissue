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
            .navigationBarTitleDisplayMode(.inline)
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
        List(viewModel.repos) { repo in
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
                            .foregroundStyle(.accent)
                    }
                }
            }
            .foregroundStyle(.primary)
        }
    }
}
