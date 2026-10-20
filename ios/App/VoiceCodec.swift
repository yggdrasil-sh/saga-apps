import Foundation
import AVFoundation

// Sprachnachrichten (Saga 2.5): Opus mit dem Codec des Systems (AVAudioConverter, kAudioFormatOpus), ohne Fremdbibliothek.
// Encoder: PCM Float32 48 kHz mono -> Opus 24 kbit/s VBR, 20-ms-Rahmen (960 Samples) -> Ogg (VoiceOgg.swift).
// Decoder: Opus-Pakete (aus dem Ogg-Demuxer) -> PCM Float32 48 kHz mono. Beides ohne Audio-Geraet: die Tests fuettern
// synthetische PCM (Sinus) bzw. eine erzeugte Datei - nie das Mikrofon.

enum VoiceCodecError: Error { case format, converter, encode, decode }

enum VoiceFormats {
    static let frame: AVAudioFrameCount = 960
    static var pcm: AVAudioFormat { AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false)! }
    static var opus: AVAudioFormat? {
        var d = AudioStreamBasicDescription(mSampleRate: 48_000, mFormatID: kAudioFormatOpus, mFormatFlags: 0, mBytesPerPacket: 0,
                                            mFramesPerPacket: 960, mBytesPerFrame: 0, mChannelsPerFrame: 1, mBitsPerChannel: 0, mReserved: 0)
        return AVAudioFormat(streamDescription: &d)
    }
}

/// PCM rein, Ogg/Opus raus (nicht threadsicher: von einer Warteschlange aus benutzen)
final class VoiceEncoder {
    private let conv: AVAudioConverter
    private let opus: AVAudioFormat
    private var pending: [Float] = []
    private(set) var writer: OggOpus.Writer
    /// gelieferte Samples (ohne Polster am Ende)
    private(set) var inSamples: Int64 = 0
    /// RMS je 100 ms (fuer die Wellenform und die Pegelanzeige)
    private(set) var levels: [Float] = []
    private var lvSum: Float = 0
    private var lvN = 0

    init(kbps: Int = 24) throws {
        guard let o = VoiceFormats.opus, let c = AVAudioConverter(from: VoiceFormats.pcm, to: o) else { throw VoiceCodecError.converter }
        c.bitRate = kbps * 1000
        c.bitRateStrategy = AVAudioBitRateStrategy_Variable
        opus = o; conv = c
        let lead = Int(c.primeInfo.leadingFrames)
        writer = OggOpus.Writer(preSkip: lead > 0 && lead < 10_000 ? lead : OggOpus.defaultPreSkip)
    }

    var seconds: Double { Double(inSamples) / 48_000 }
    var lastLevel: Float { levels.last ?? 0 }

    /// Samples anhaengen (48 kHz mono) - kodiert jeden vollen 20-ms-Rahmen
    func append(_ s: UnsafeBufferPointer<Float>) throws {
        for x in s {
            lvSum += x * x; lvN += 1
            if lvN == 4800 { levels.append((lvSum / Float(lvN)).squareRoot()); lvSum = 0; lvN = 0 }
        }
        inSamples += Int64(s.count)
        pending.append(contentsOf: s)
        let f = Int(VoiceFormats.frame)
        if pending.count >= f * 4 {                                   // in Stuecken von 4 Rahmen kodieren
            let n = pending.count / f * f
            try encode(Array(pending[0..<n])); pending.removeFirst(n)
        }
    }
    func append(_ s: [Float]) throws { try s.withUnsafeBufferPointer { try append($0) } }

    /// Rest auffuellen, Encoder leeren -> fertige Datei
    func finish() throws -> Data {
        if lvN > 0 { levels.append((lvSum / Float(lvN)).squareRoot()); lvN = 0; lvSum = 0 }
        let f = Int(VoiceFormats.frame)
        if !pending.isEmpty { pending += [Float](repeating: 0, count: (f - pending.count % f) % f); try encode(pending); pending = [] }
        try drain(final: true)
        return writer.finish(samples: inSamples)
    }

    private var feed: AVAudioPCMBuffer?
    private func encode(_ s: [Float]) throws {
        guard let b = AVAudioPCMBuffer(pcmFormat: VoiceFormats.pcm, frameCapacity: AVAudioFrameCount(s.count)) else { throw VoiceCodecError.encode }
        b.frameLength = AVAudioFrameCount(s.count)
        s.withUnsafeBufferPointer { p in b.floatChannelData![0].update(from: p.baseAddress!, count: s.count) }
        feed = b
        try drain(final: false)
    }
    private func drain(final: Bool) throws {
        var ended = false
        while true {
            let out = AVAudioCompressedBuffer(format: opus, packetCapacity: 16, maximumPacketSize: max(conv.maximumOutputPacketSize, 1500))
            var err: NSError?
            let st = conv.convert(to: out, error: &err) { [weak self] _, status in
                if let b = self?.feed { self?.feed = nil; status.pointee = .haveData; return b }
                if final { ended = true; status.pointee = .endOfStream } else { status.pointee = .noDataNow }
                return nil
            }
            if st == .error { throw err ?? VoiceCodecError.encode }
            take(out)
            if st == .endOfStream || (!final && (st == .inputRanDry || out.packetCount == 0)) { break }
            if final && ended && out.packetCount == 0 { break }
        }
    }
    private func take(_ out: AVAudioCompressedBuffer) {
        guard let descs = out.packetDescriptions else { return }
        let base = out.data.assumingMemoryBound(to: UInt8.self)
        for i in 0..<Int(out.packetCount) {
            let d = descs[i]
            guard d.mDataByteSize > 0 else { continue }
            writer.add(Data(bytes: base + Int(d.mStartOffset), count: Int(d.mDataByteSize)))
        }
    }
}

/// Opus-Pakete rein, PCM raus (je Aufruf eine Folge von Paketen; reset() nach dem Spulen)
final class VoiceDecoder {
    private let conv: AVAudioConverter
    private let opus: AVAudioFormat
    init() throws {
        guard let o = VoiceFormats.opus, let c = AVAudioConverter(from: o, to: VoiceFormats.pcm) else { throw VoiceCodecError.converter }
        opus = o; conv = c
    }
    func reset() { conv.reset() }
    /// Pakete dekodieren -> PCM (960 Samples je 20-ms-Paket)
    func decode(_ packets: [Data]) throws -> AVAudioPCMBuffer {
        let frames = packets.reduce(0) { $0 + max(OggOpus.samples($1), 960) }
        guard let out = AVAudioPCMBuffer(pcmFormat: VoiceFormats.pcm, frameCapacity: AVAudioFrameCount(max(frames, 960))) else { throw VoiceCodecError.decode }
        var i = 0
        while i < packets.count {
            let chunk = Array(packets[i..<min(packets.count, i + 8)]); i += chunk.count
            let size = chunk.reduce(0) { $0 + $1.count }
            let inb = AVAudioCompressedBuffer(format: opus, packetCapacity: AVAudioPacketCount(chunk.count), maximumPacketSize: max(size, 1))
            var off = 0
            let base = inb.data.assumingMemoryBound(to: UInt8.self)
            for (k, p) in chunk.enumerated() {
                p.withUnsafeBytes { r in if let a = r.baseAddress { (base + off).update(from: a.assumingMemoryBound(to: UInt8.self), count: p.count) } }
                inb.packetDescriptions![k] = AudioStreamPacketDescription(mStartOffset: Int64(off), mVariableFramesInPacket: 0, mDataByteSize: UInt32(p.count))
                off += p.count
            }
            inb.packetCount = AVAudioPacketCount(chunk.count); inb.byteLength = UInt32(off)
            let part = AVAudioPCMBuffer(pcmFormat: VoiceFormats.pcm, frameCapacity: AVAudioFrameCount(chunk.count * 5760))!
            var given = false, err: NSError?
            let st = conv.convert(to: part, error: &err) { _, status in
                if given { status.pointee = .noDataNow; return nil }
                given = true; status.pointee = .haveData; return inb
            }
            if st == .error { throw err ?? VoiceCodecError.decode }
            let n = Int(part.frameLength), have = Int(out.frameLength)
            if have + n > Int(out.frameCapacity) { break }
            out.floatChannelData![0].advanced(by: have).update(from: part.floatChannelData![0], count: n)
            out.frameLength = AVAudioFrameCount(have + n)
        }
        return out
    }
}
