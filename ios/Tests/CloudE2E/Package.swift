// swift-tools-version:5.9
// E2E Apps 2.3 Teil 3 ("Mein Backup" und Nextcloud-Dateien) gegen einen Saga-Server mit nachgemachten Zielen
// (tests/fakedav.py im Server-Repo): die Server-Wege und die Logik der App (App/CloudFlow.swift, App/CloudLogic.swift,
// App/LiveLogic.swift - verlinkt). Aufruf und Umgebung: Sources/CloudE2E/main.swift (Kopf).
import PackageDescription

let package = Package(
    name: "CloudE2E",
    platforms: [.macOS(.v13)],
    targets: [.executableTarget(name: "CloudE2E")]
)
