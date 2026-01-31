import Foundation

enum GitHubAPIError: Error, LocalizedError {
    case unauthorized
    case httpError(statusCode: Int, message: String)
    case networkError(Error)

    var errorDescription: String? {
        switch self {
        case .unauthorized:
            return "Authentication failed. Please log in again."
        case .httpError(let code, let message):
            return "HTTP \(code): \(message)"
        case .networkError(let error):
            return error.localizedDescription
        }
    }

    var isNetworkError: Bool {
        if case .networkError = self { return true }
        return false
    }
}

struct GitHubAPIClient {
    private static let baseURL = Constants.gitHubAPIBaseURL
    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        return e
    }()
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        return d
    }()

    // MARK: - Issues

    static func createIssue(
        owner: String,
        repo: String,
        request: CreateIssueRequest,
        token: String
    ) async throws -> IssueResponse {
        let url = URL(string: "\(baseURL)/repos/\(owner)/\(repo)/issues")!
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = try encoder.encode(request)

        return try await perform(urlRequest)
    }

    // MARK: - Labels

    static func listLabels(
        owner: String,
        repo: String,
        token: String
    ) async throws -> [LabelResponse] {
        var components = URLComponents(string: "\(baseURL)/repos/\(owner)/\(repo)/labels")!
        components.queryItems = [URLQueryItem(name: "per_page", value: "100")]
        var urlRequest = URLRequest(url: components.url!)
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        return try await perform(urlRequest)
    }

    // MARK: - Repos

    static func listRepos(token: String) async throws -> [RepoResponse] {
        var components = URLComponents(string: "\(baseURL)/user/repos")!
        components.queryItems = [
            URLQueryItem(name: "sort", value: "updated"),
            URLQueryItem(name: "per_page", value: "100"),
        ]
        var urlRequest = URLRequest(url: components.url!)
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        return try await perform(urlRequest)
    }

    // MARK: - Private

    private static func perform<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw GitHubAPIError.networkError(error)
        }

        guard let http = response as? HTTPURLResponse else {
            throw GitHubAPIError.httpError(statusCode: 0, message: "Invalid response")
        }

        switch http.statusCode {
        case 200...299:
            return try decoder.decode(T.self, from: data)
        case 401:
            throw GitHubAPIError.unauthorized
        default:
            let body = String(data: data, encoding: .utf8) ?? ""
            throw GitHubAPIError.httpError(statusCode: http.statusCode, message: body)
        }
    }
}
