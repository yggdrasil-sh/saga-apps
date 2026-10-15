// swift-tools-version:5.9
// Chat-Testvektoren (tests/chat_vectors.json aus dem Server-Repo) gegen die App-Krypto (App/ChatCrypto.swift, verlinkt).
// Aufruf: swift run -c release ChatVectors <pfad/zu/chat_vectors.json>
import PackageDescription

let package = Package(
    name: "ChatVectors",
    platforms: [.macOS(.v13)],
    dependencies: [.package(url: "https://github.com/jedisct1/swift-sodium.git", exact: "0.9.1")],
    targets: [
        .executableTarget(name: "ChatVectors", dependencies: [.product(name: "Clibsodium", package: "swift-sodium")]),
    ]
)
