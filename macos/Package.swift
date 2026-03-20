// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "GhIssue",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "GhIssue",
            path: "Sources/GhIssue",
            swiftSettings: [
                .swiftLanguageMode(.v5),
            ]
        ),
    ]
)
