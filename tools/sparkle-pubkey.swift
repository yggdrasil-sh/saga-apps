// Oeffentlichen EdDSA-Schluessel (base64) aus dem privaten Sparkle-Schluessel ableiten - fuer SUPublicEDKey in der DMG.
// Der private Schluessel kommt ueber die Standardeingabe (nie als Argument), Ausgabe nur der oeffentliche Schluessel.
//   printf '%s' "$SPARKLE_ED_PRIVATE_KEY" | swift tools/sparkle-pubkey.swift
// Formate wie Sparkles generate_keys -x: base64 von 32 Byte (Seed, heutiges Format) oder 96 Byte (alt: 64 Byte
// privater Schluessel + 32 Byte oeffentlicher Schluessel).
import CryptoKit
import Foundation

let input = String(decoding: FileHandle.standardInput.readDataToEndOfFile(), as: UTF8.self)
    .trimmingCharacters(in: .whitespacesAndNewlines)
guard let secret = Data(base64Encoded: input) else {
    FileHandle.standardError.write(Data("FEHLER: privater Schluessel ist kein base64\n".utf8)); exit(1)
}
switch secret.count {
case 32:
    guard let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: secret) else { exit(1) }
    print(key.publicKey.rawRepresentation.base64EncodedString())
case 96:
    print(secret.suffix(32).base64EncodedString())
default:
    FileHandle.standardError.write(Data("FEHLER: privater Schluessel hat \(secret.count) statt 32 Byte\n".utf8)); exit(1)
}
