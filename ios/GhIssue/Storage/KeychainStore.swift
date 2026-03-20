import Foundation
import Security

struct KeychainStore {
    private static let service = "com.ghissue.app"
    private static let account = "github_access_token"
    private static let accessGroup = Constants.keychainAccessGroup

    static var accessToken: String? {
        get { read() }
        set {
            if let value = newValue {
                save(value)
            } else {
                delete()
            }
        }
    }

    static var isLoggedIn: Bool {
        accessToken != nil
    }

    static func clear() {
        delete()
    }

    // MARK: - Private

    private static func baseQuery() -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        #if os(iOS)
        query[kSecAttrAccessGroup as String] = accessGroup
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        #endif
        return query
    }

    private static func save(_ token: String) {
        delete()
        let data = Data(token.utf8)
        var query = baseQuery()
        query[kSecValueData as String] = data
        SecItemAdd(query as CFDictionary, nil)
    }

    private static func read() -> String? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func delete() {
        let query = baseQuery()
        SecItemDelete(query as CFDictionary)
    }
}
