import Foundation

struct PreferencesStore {
    private static let defaults = UserDefaults(suiteName: Constants.appGroupID) ?? .standard

    // MARK: - Config

    static var clientId: String {
        get { defaults.string(forKey: "client_id") ?? Constants.defaultClientID }
        set { defaults.set(newValue, forKey: "client_id") }
    }

    static var repoOwner: String {
        get { defaults.string(forKey: "repo_owner") ?? "" }
        set { defaults.set(newValue, forKey: "repo_owner") }
    }

    static var repoName: String {
        get { defaults.string(forKey: "repo_name") ?? "" }
        set { defaults.set(newValue, forKey: "repo_name") }
    }

    static var isConfigured: Bool {
        !clientId.isEmpty && !repoOwner.isEmpty && !repoName.isEmpty
    }

    // MARK: - Pending Device Flow

    static var pendingDeviceCode: String? {
        get { defaults.string(forKey: "pending_device_code") }
        set { defaults.set(newValue, forKey: "pending_device_code") }
    }

    static var pendingUserCode: String? {
        get { defaults.string(forKey: "pending_user_code") }
        set { defaults.set(newValue, forKey: "pending_user_code") }
    }

    static var pendingVerificationUri: String? {
        get { defaults.string(forKey: "pending_verification_uri") }
        set { defaults.set(newValue, forKey: "pending_verification_uri") }
    }

    static var pendingInterval: Int {
        get {
            let val = defaults.integer(forKey: "pending_interval")
            return val > 0 ? val : 5
        }
        set { defaults.set(newValue, forKey: "pending_interval") }
    }

    static var pendingExpiresAt: Date {
        get { Date(timeIntervalSince1970: defaults.double(forKey: "pending_expires_at")) }
        set { defaults.set(newValue.timeIntervalSince1970, forKey: "pending_expires_at") }
    }

    static var hasPendingDeviceFlow: Bool {
        pendingDeviceCode != nil && Date() < pendingExpiresAt
    }

    static func clearPendingDeviceFlow() {
        defaults.removeObject(forKey: "pending_device_code")
        defaults.removeObject(forKey: "pending_user_code")
        defaults.removeObject(forKey: "pending_verification_uri")
        defaults.removeObject(forKey: "pending_interval")
        defaults.removeObject(forKey: "pending_expires_at")
    }

    // MARK: - Cached Repos (for widget)

    static var cachedRepos: [CachedRepo] {
        get {
            guard let data = defaults.data(forKey: "cached_repos") else { return [] }
            return (try? JSONDecoder().decode([CachedRepo].self, from: data)) ?? []
        }
        set {
            let data = try? JSONEncoder().encode(newValue)
            defaults.set(data, forKey: "cached_repos")
        }
    }
}

struct CachedRepo: Codable, Hashable, Identifiable {
    let owner: String
    let name: String

    var id: String { "\(owner)/\(name)" }
    var fullName: String { "\(owner)/\(name)" }
}
