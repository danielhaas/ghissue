import AppIntents

struct RepoEntity: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Repository")
    static var defaultQuery = RepoEntityQuery()

    var id: String
    var owner: String
    var name: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(owner)/\(name)")
    }

    init(id: String, owner: String, name: String) {
        self.id = id
        self.owner = owner
        self.name = name
    }

    init(from cached: CachedRepo) {
        self.id = cached.id
        self.owner = cached.owner
        self.name = cached.name
    }
}
