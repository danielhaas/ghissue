import Foundation

struct QueuedIssue: Codable, Identifiable {
    let id: String
    let owner: String
    let repo: String
    let title: String
    let body: String?
    let labels: [String]?
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case id, owner, repo, title, body, labels
        case createdAt = "created_at"
    }
}
