// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "LabBot",
    platforms: [.macOS(.v15)],
    dependencies: [
        // a VT100/xterm terminal view for AppKit (MIT), for the bots' terminals
        .package(url: "https://github.com/migueldeicaza/SwiftTerm", exact: "1.20.0"),
    ],
    targets: [
        .executableTarget(
            name: "LabBot",
            dependencies: [.product(name: "SwiftTerm", package: "SwiftTerm")],
            path: "Sources/LabBot"
        ),
        .testTarget(name: "LabBotTests", dependencies: ["LabBot"], path: "Tests/LabBotTests"),
    ],
    swiftLanguageModes: [.v5]
)
