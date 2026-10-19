// swift-tools-version:5.9
// E2E Chats ueber Servergrenzen (2.3) gegen ZWEI verbundene Saga-Server: die Ablaeufe der App (App/FedChatFlow.swift,
// App/ChatCryptoV2.swift, App/FedChatLogic.swift - verlinkt) als Konto auf Server A, die Gegenseite als Referenz-App
// (tests/chatlib.py, PyNaCl) auf Server B. Aufruf und Umgebung: Sources/FedE2E/main.swift (Kopf).
import PackageDescription

let package = Package(
    name: "FedE2E",
    platforms: [.macOS(.v13)],
    dependencies: [.package(url: "https://github.com/jedisct1/swift-sodium.git", exact: "0.9.1")],
    targets: [
        .executableTarget(name: "FedE2E", dependencies: [.product(name: "Clibsodium", package: "swift-sodium")]),
    ]
)
