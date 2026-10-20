package io.github.yggdrasil_sh.saga.call

import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyDerivationAlgorithm
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender

/**
 * Gjallarhorn (Saga 2.5): FrameCryptor von webrtc-sdk mit den Einstellungen aus docs/HORN-E2E.md §6 (wie
 * ios/App/HornCryptor.swift). Noch nicht im Anrufablauf verwendet (Knoten-Beine und Verteilung kommen mit den naechsten
 * Paketen); geprueft von HornE2EVectorsTest (JVM) und HornLoopbackTest (Geraet/Emulator).
 *
 * Befunde (webrtc-sdk M150, Apple-Teil): ratchetKey im HKDF-Modus liefert HKDF(K, ratchetSalt, 128 x 00, 32), die ersten
 * 16 Byte sind genau das FK. Die FrameCryptor-Ratsche wird NICHT benutzt (Saga ratscht selbst, eigenes Salz). Mit
 * failureTolerance -1 meldet FrameCryptor nie DECRYPTIONFAILED - "need" kommt aus getStats ([unreadable]).
 * Einen eigenen Rahmen-Transformer gibt es in der API nicht: kein Wiederholungsschutz in den Apps (bekanntes Restrisiko).
 */
object HornCryptor {
    /**
     * Ein KeyProvider je Anruf: je Sender ein Schluessel (participantId = Geraete-ID), HKDF mit dem Rahmen-Salz,
     * FrameCryptor-Ratsche aus (Saga ratscht selbst), ein kaputter Rahmen sperrt nichts, 16 Plaetze, ohne Schluessel
     * verwerfen, keine unverschluesselten Magic-Bytes (leeres Feld = keine).
     */
    fun keyProvider(): FrameCryptorKeyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
        false, HornE2E.frameSalt, 0, ByteArray(0), -1, HornE2E.RING, true, FrameCryptorKeyDerivationAlgorithm.HKDF,
    )

    /** Vor dem ersten Rahmen anhaengen (Sender: bevor der Track Bilder liefert bzw. vor der Verhandlung) */
    fun sender(f: PeerConnectionFactory, s: RtpSender, device: String, provider: FrameCryptorKeyProvider): FrameCryptor =
        FrameCryptorFactory.createFrameCryptorForRtpSender(f, s, device, FrameCryptorAlgorithm.AES_GCM, provider)

    fun receiver(f: PeerConnectionFactory, r: RtpReceiver, from: String, provider: FrameCryptorKeyProvider): FrameCryptor =
        FrameCryptorFactory.createFrameCryptorForRtpReceiver(f, r, from, FrameCryptorAlgorithm.AES_GCM, provider)

    /** Empfaenger: gehaltene neueste Generation und die vier folgenden Saga-Ratschenstufen vorab einsetzen */
    fun setSlots(p: FrameCryptorKeyProvider, device: String, newest: Int, key: ByteArray) {
        for (s in HornE2E.cryptorSlots(newest, key)) p.setKey(device, s.kid, s.key)
    }

    /** Abgelaufenen Platz unbrauchbar machen (FrameCryptor kann nicht loeschen) */
    fun scrub(p: FrameCryptorKeyProvider, device: String, kid: Int) {
        p.setKey(device, kid, HornE2E.scrubKey())
    }

    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: v?.toString()?.toBigDecimalOrNull()?.toLong() ?: 0L

    /**
     * Signal fuer "need" (§4 Nachforderung): eingehendes Video bekommt Bytes, aber kein Rahmen geht auf (framesReceived
     * bleibt 0). Mit failureTolerance -1 meldet FrameCryptor keinen Fehler, deshalb ueber getStats. Nur Video: beim Ton
     * fuellt NetEq Luecken mit Ersatz-Samples, dort ist das Signal nicht eindeutig. Optional nur fuer die Spuren eines
     * Senders ([trackIds], trackIdentifier). Die 2-s-Frist haelt der Aufrufer.
     */
    fun unreadable(report: RTCStatsReport, trackIds: Set<String>? = null): Boolean {
        val inb = report.statsMap.values.filter {
            it.type == "inbound-rtp" && it.members["kind"] == "video" &&
                (trackIds == null || it.members["trackIdentifier"] in trackIds)
        }
        if (inb.isEmpty()) return false
        val bytes = inb.sumOf { num(it.members["bytesReceived"]) }
        val frames = inb.sumOf { num(it.members["framesReceived"]) }
        return bytes > 0 && frames == 0L
    }
}
