import SwiftUI

struct CreateIssueView: View {
    @Bindable var viewModel: CreateIssueViewModel
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focusedField: Field?

    enum Field { case title, body }

    var body: some View {
        NavigationStack {
            Form {
                headerSection
                titleSection
                bodySection
                if !viewModel.labels.isEmpty {
                    labelsSection
                }
                messagesSection
            }
            .navigationTitle("Create Issue")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(viewModel.isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if viewModel.isSubmitting {
                        ProgressView()
                    } else {
                        Button("Submit") {
                            submitIssue()
                        }
                    }
                }
            }
            .task {
                await viewModel.loadLabels()
                await viewModel.drainQueueIfNeeded()
            }
            .onSubmit {
                if focusedField == .title {
                    submitIssue()
                }
            }
        }
    }

    // MARK: - Subviews

    private var headerSection: some View {
        Section {
            HStack {
                Label(viewModel.owner, systemImage: "person")
                    .foregroundStyle(.secondary)
                Text("/")
                    .foregroundStyle(.secondary)
                Text(viewModel.repo)
                    .fontWeight(.medium)
            }
        }
    }

    private var titleSection: some View {
        Section {
            TextField("Issue title", text: $viewModel.title)
                .focused($focusedField, equals: .title)
                .submitLabel(.send)
                .disabled(viewModel.isSubmitting)
        }
    }

    private var bodySection: some View {
        Section {
            TextEditor(text: $viewModel.body)
                .focused($focusedField, equals: .body)
                .frame(minHeight: 120)
                .disabled(viewModel.isSubmitting)
        } header: {
            Text("Description")
        }
    }

    private var labelsSection: some View {
        Section {
            LabelChipsFlowView(
                labels: viewModel.labels,
                selectedLabels: viewModel.selectedLabels,
                onToggle: { viewModel.toggleLabel($0) }
            )
            .padding(.vertical, 4)
        } header: {
            Text("Labels")
        }
    }

    @ViewBuilder
    private var messagesSection: some View {
        if viewModel.pendingCount > 0 {
            Section {
                Label(
                    "\(viewModel.pendingCount) issue\(viewModel.pendingCount == 1 ? "" : "s") pending",
                    systemImage: "clock"
                )
                .foregroundStyle(.orange)
                .font(.caption)
            }
        }
        if let error = viewModel.errorMessage {
            Section {
                Text(error)
                    .foregroundStyle(.red)
                    .font(.caption)
            }
        }
        if let queue = viewModel.queueMessage {
            Section {
                Label(queue, systemImage: "tray.and.arrow.down")
                    .foregroundStyle(.orange)
                    .font(.caption)
            }
        }
    }

    // MARK: - Actions

    private func submitIssue() {
        Task {
            let success = await viewModel.submit()
            if success {
                dismiss()
            }
        }
    }
}
