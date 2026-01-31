import AppIntents

struct RepoEntityQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [RepoEntity] {
        let cached = PreferencesStore.cachedRepos
        return identifiers.compactMap { id in
            guard let repo = cached.first(where: { $0.id == id }) else { return nil }
            return RepoEntity(from: repo)
        }
    }

    func suggestedEntities() async throws -> [RepoEntity] {
        PreferencesStore.cachedRepos.map { RepoEntity(from: $0) }
    }

    func defaultResult() async -> RepoEntity? {
        let owner = PreferencesStore.repoOwner
        let name = PreferencesStore.repoName
        guard !owner.isEmpty, !name.isEmpty else { return nil }
        return RepoEntity(id: "\(owner)/\(name)", owner: owner, name: name)
    }
}
