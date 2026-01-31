import Foundation
import Observation

@Observable
final class AuthViewModel {
    var isLoggedIn = KeychainStore.isLoggedIn
    var isPolling = false
    var userCode: String?
    var verificationUri: String?
    var statusMessage: String?
    var errorMessage: String?

    private var pollTask: Task<Void, Never>?

    init() {
        if PreferencesStore.hasPendingDeviceFlow {
            resumePolling()
        }
    }

    func startOAuthFlow() {
        let clientId = PreferencesStore.clientId
        guard !clientId.isEmpty else {
            errorMessage = "Client ID is not configured."
            return
        }

        errorMessage = nil
        statusMessage = "Requesting device code..."

        pollTask?.cancel()
        pollTask = Task {
            do {
                let response = try await GitHubOAuthClient.requestDeviceCode(
                    clientId: clientId,
                    scope: Constants.oauthScope
                )

                PreferencesStore.pendingDeviceCode = response.deviceCode
                PreferencesStore.pendingUserCode = response.userCode
                PreferencesStore.pendingVerificationUri = response.verificationUri
                PreferencesStore.pendingInterval = response.interval
                PreferencesStore.pendingExpiresAt = Date().addingTimeInterval(Double(response.expiresIn))

                await MainActor.run {
                    userCode = response.userCode
                    verificationUri = response.verificationUri
                    statusMessage = "Waiting for authorization..."
                }

                await pollForToken(
                    clientId: clientId,
                    deviceCode: response.deviceCode,
                    interval: response.interval
                )
            } catch is CancellationError {
                // Cancelled, do nothing
            } catch {
                await MainActor.run {
                    errorMessage = "Failed to start login: \(error.localizedDescription)"
                    statusMessage = nil
                    isPolling = false
                }
            }
        }
    }

    func cancelFlow() {
        pollTask?.cancel()
        pollTask = nil
        clearFlowState()
    }

    func logout() {
        KeychainStore.clear()
        isLoggedIn = false
    }

    // MARK: - Private

    private func resumePolling() {
        guard let deviceCode = PreferencesStore.pendingDeviceCode,
              let code = PreferencesStore.pendingUserCode,
              let uri = PreferencesStore.pendingVerificationUri else { return }

        userCode = code
        verificationUri = uri
        statusMessage = "Waiting for authorization..."

        let clientId = PreferencesStore.clientId
        let interval = PreferencesStore.pendingInterval

        pollTask = Task {
            await pollForToken(clientId: clientId, deviceCode: deviceCode, interval: interval)
        }
    }

    private func pollForToken(clientId: String, deviceCode: String, interval: Int) async {
        await MainActor.run { isPolling = true }
        var currentInterval = interval

        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(currentInterval))
            guard !Task.isCancelled else { return }

            do {
                let response = try await GitHubOAuthClient.pollForToken(
                    clientId: clientId,
                    deviceCode: deviceCode
                )

                if let token = response.accessToken, !token.isEmpty {
                    KeychainStore.accessToken = token
                    await MainActor.run {
                        isLoggedIn = true
                        clearFlowState()
                    }
                    return
                }

                switch response.error {
                case "authorization_pending":
                    continue
                case "slow_down":
                    currentInterval += 5
                case "expired_token":
                    await MainActor.run {
                        errorMessage = "Device code expired. Please try again."
                        clearFlowState()
                    }
                    return
                default:
                    let msg = response.errorDescription ?? response.error ?? "Unknown error"
                    await MainActor.run {
                        errorMessage = msg
                        clearFlowState()
                    }
                    return
                }
            } catch is CancellationError {
                return
            } catch is URLError {
                // Transient network error, keep retrying
                continue
            } catch {
                await MainActor.run {
                    errorMessage = "Login error: \(error.localizedDescription)"
                    clearFlowState()
                }
                return
            }
        }
    }

    private func clearFlowState() {
        PreferencesStore.clearPendingDeviceFlow()
        isPolling = false
        userCode = nil
        verificationUri = nil
        statusMessage = nil
    }
}
