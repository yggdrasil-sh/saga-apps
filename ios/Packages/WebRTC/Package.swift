// swift-tools-version:5.9
// WebRTC fuer die Apple-Apps (Saga 2.5): das vorgebaute xcframework von webrtc-sdk (LiveKit-Zweig, BSD) - dieselbe
// Datei und Pruefsumme wie im Paket https://github.com/webrtc-sdk/Specs (Tag siehe url). Eigene Huelle, weil das
// Specs-Manifest `.visionOS(.v26)` mit tools-version 5.9 angibt und SwiftPM aus Xcode 27 es deshalb ablehnt.
// Neue Version: url + checksum aus Specs/Package.swift des Tags uebernehmen (CHANGES.md dort), Header pruefen
// (RTCFrameCryptorKeyProvider.h mit RTCKeyDerivationAlgorithmHKDF, siehe docs/HORN-E2E.md im Server-Repo).
import PackageDescription

let package = Package(
    name: "WebRTC",
    platforms: [.iOS(.v13), .macOS(.v10_15)],
    products: [
        .library(name: "WebRTC", targets: ["WebRTC"]),
    ],
    targets: [
        .binaryTarget(
            name: "WebRTC",
            url: "https://github.com/webrtc-sdk/Specs/releases/download/150.7871.01/WebRTC.xcframework.zip",
            checksum: "03815cdf2f6a0ed328c94d74cce8fd1b8d2b6e95e2b37eab66795012fcecfdfa"
        ),
    ]
)
