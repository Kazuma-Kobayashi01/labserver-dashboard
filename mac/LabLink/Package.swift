// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "LabLink",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "LabLink", path: "Sources/LabLink")
    ]
)
