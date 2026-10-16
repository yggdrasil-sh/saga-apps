import Foundation
import Clibsodium

// Anrufe (Saga 2.1): Signalisierung saga-call/v1 - genau wie static/chat-crypto.js (sealSignal/openSignal/verifySignal)
// und tests/chatlib.py (seal_signal/open_signal/verify_signal). Jede Signal-Nachricht (SDP, ICE, Zustand) ist ein
// JSON-Text `body`; das sendende Chat-Geraet signiert LP("saga-call/v1/signal", call_id, from_uid, from_device, to_uid,
// to_device, body) mit seinem Signaturschluessel und versiegelt {"v":1,"body":body,"sig":B64(sig)} fuer den
// X25519-Schluessel des Zielgeraets. Geprueft gegen tests/call_vectors.json (ios/Tests/ChatVectors).

extension ChatCrypto {
    static let VC = "saga-call/v1/"

    static func signalMsg(callID: String, fromUID: String, fromDevice: String, toUID: String, toDevice: String, body: String) -> Data {
        lp(VC + "signal", callID, fromUID, fromDevice, toUID, toDevice, body)
    }
    /// {"v":1,"body":<body>,"sig":<B64>} ohne Leerzeichen (wie JSON.stringify bzw. json.dumps mit separators)
    static func signalInner(body: String, sig: Data) -> String {
        "{\"v\":1,\"body\":" + CallLogic.jsonString(body) + ",\"sig\":\"" + b64(sig) + "\"}"
    }
    /// Signieren + fuer das Zielgeraet versiegeln -> B64 (nil bei ungueltigem Schluessel)
    static func sealSignal(callID: String, fromUID: String, fromDevice: String, signSK: Data, toUID: String, toDevice: String,
                           toPK: Data, body: String) -> String? {
        let sig = sign(signalMsg(callID: callID, fromUID: fromUID, fromDevice: fromDevice, toUID: toUID, toDevice: toDevice, body: body), sk: signSK)
        return seal(Data(signalInner(body: body, sig: sig).utf8), pk: toPK).map(b64)
    }
    /// Versiegeltes Signal mit dem eigenen Geraet oeffnen -> (body, sig); nil = nicht oeffenbar oder unbekanntes Format
    static func openSignal(_ sealed: String, pk: Data, sk: Data) -> (body: String, sig: Data)? {
        guard let raw = unb64(sealed), let pt = sealOpen(raw, pk: pk, sk: sk),
              let j = (try? JSONSerialization.jsonObject(with: pt)) as? [String: Any],
              (j["v"] as? NSNumber)?.intValue == 1, let body = j["body"] as? String, let sig = unb64(j["sig"] as? String, 64) else { return nil }
        return (body, sig)
    }
    /// Signatur pruefen - mit MEINEM uid/Geraet als Ziel (verhindert Umleiten an andere Geraete/Anrufe)
    static func verifySignal(callID: String, fromUID: String, fromDevice: String, toUID: String, toDevice: String, body: String,
                             sig: Data, signPK: Data?) -> Bool {
        verify(sig, signalMsg(callID: callID, fromUID: fromUID, fromDevice: fromDevice, toUID: toUID, toDevice: toDevice, body: body), pk: signPK)
    }
}
