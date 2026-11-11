// swift-tools-version:5.7
import PackageDescription

// The platform-independent sync core (wire protocol, clock sync, jitter buffer, resampler and
// playout engine) as a package, so it can be unit-tested with `swift test` on a Mac without a
// simulator. The app target in Hearby.xcodeproj compiles the very same files directly.
let package = Package(
    name: "HearbyCore",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "HearbyCore", targets: ["HearbyCore"]),
    ],
    targets: [
        .target(name: "HearbyCore", path: "Hearby/Core"),
        .testTarget(name: "HearbyCoreTests", dependencies: ["HearbyCore"], path: "Tests/HearbyCoreTests"),
    ]
)
