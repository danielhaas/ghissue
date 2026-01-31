import Foundation

struct CreateIssueRequest: Encodable {
    let title: String
    let body: String?
    let labels: [String]?
}

struct IssueResponse: Decodable {
    let id: Int
    let number: Int
    let htmlUrl: String
    let title: String

    enum CodingKeys: String, CodingKey {
        case id, number, title
        case htmlUrl = "html_url"
    }
}

struct DeviceCodeRequest: Encodable {
    let clientId: String
    let scope: String

    enum CodingKeys: String, CodingKey {
        case clientId = "client_id"
        case scope
    }
}

struct DeviceCodeResponse: Decodable {
    let deviceCode: String
    let userCode: String
    let verificationUri: String
    let expiresIn: Int
    let interval: Int

    enum CodingKeys: String, CodingKey {
        case deviceCode = "device_code"
        case userCode = "user_code"
        case verificationUri = "verification_uri"
        case expiresIn = "expires_in"
        case interval
    }
}

struct DeviceTokenRequest: Encodable {
    let clientId: String
    let deviceCode: String
    let grantType: String = "urn:ietf:params:oauth:grant-type:device_code"

    enum CodingKeys: String, CodingKey {
        case clientId = "client_id"
        case deviceCode = "device_code"
        case grantType = "grant_type"
    }
}

struct OAuthTokenResponse: Decodable {
    let accessToken: String?
    let tokenType: String?
    let scope: String?
    let error: String?
    let errorDescription: String?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case tokenType = "token_type"
        case scope
        case error
        case errorDescription = "error_description"
    }
}

struct RepoResponse: Decodable, Identifiable {
    let id: Int
    let fullName: String
    let name: String
    let owner: RepoOwner

    enum CodingKeys: String, CodingKey {
        case id, name, owner
        case fullName = "full_name"
    }
}

struct RepoOwner: Decodable {
    let login: String
}

struct LabelResponse: Decodable, Identifiable {
    let id: Int
    let name: String
    let color: String
}
