import Foundation

struct GitHubOAuthClient {
    private static let baseURL = Constants.gitHubOAuthBaseURL

    static func requestDeviceCode(clientId: String, scope: String) async throws -> DeviceCodeResponse {
        let url = URL(string: "\(baseURL)/login/device/code")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = DeviceCodeRequest(clientId: clientId, scope: scope)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, _) = try await URLSession.shared.data(for: request)
        return try JSONDecoder().decode(DeviceCodeResponse.self, from: data)
    }

    static func pollForToken(clientId: String, deviceCode: String) async throws -> OAuthTokenResponse {
        let url = URL(string: "\(baseURL)/login/oauth/access_token")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = DeviceTokenRequest(clientId: clientId, deviceCode: deviceCode)
        request.httpBody = try JSONEncoder().encode(body)

        let (data, _) = try await URLSession.shared.data(for: request)
        return try JSONDecoder().decode(OAuthTokenResponse.self, from: data)
    }
}
