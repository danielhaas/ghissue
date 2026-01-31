import SwiftUI

struct OAuthLoginSection: View {
    @Bindable var viewModel: AuthViewModel

    var body: some View {
        if viewModel.isLoggedIn {
            loggedInView
        } else if let code = viewModel.userCode, let uri = viewModel.verificationUri {
            deviceFlowView(code: code, uri: uri)
        } else {
            loggedOutView
        }
    }

    // MARK: - Subviews

    private var loggedInView: some View {
        Section {
            HStack {
                Label("Logged in", systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                Spacer()
                Button("Log Out", role: .destructive) {
                    viewModel.logout()
                }
            }
        }
    }

    private var loggedOutView: some View {
        Section {
            if let error = viewModel.errorMessage {
                Text(error)
                    .foregroundStyle(.red)
                    .font(.caption)
            }
            if let status = viewModel.statusMessage {
                HStack {
                    ProgressView()
                        .padding(.trailing, 4)
                    Text(status)
                        .foregroundStyle(.secondary)
                }
            }
            Button("Log In with GitHub") {
                viewModel.startOAuthFlow()
            }
        } header: {
            Text("Authentication")
        }
    }

    private func deviceFlowView(code: String, uri: String) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                Text("Enter this code on GitHub:")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Text(code)
                    .font(.system(.title, design: .monospaced, weight: .bold))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .center)

                HStack(spacing: 12) {
                    Button {
                        UIPasteboard.general.string = code
                    } label: {
                        Label("Copy Code", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)

                    if let url = URL(string: uri) {
                        Link(destination: url) {
                            Label("Open GitHub", systemImage: "safari")
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .center)

                if viewModel.isPolling {
                    HStack {
                        ProgressView()
                            .padding(.trailing, 4)
                        Text("Waiting for authorization...")
                            .foregroundStyle(.secondary)
                            .font(.caption)
                    }
                }

                Button("Cancel", role: .destructive) {
                    viewModel.cancelFlow()
                }
                .font(.caption)
            }
            .padding(.vertical, 4)
        } header: {
            Text("Authentication")
        }
    }
}
