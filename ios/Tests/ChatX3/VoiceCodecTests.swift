import XCTest
import AVFoundation

// Sprachnachrichten: Opus-Codec des Systems (AVAudioConverter, VoiceCodec.swift) mit eigenem Ogg-Muxer, Wiedergabe durch
// AVFoundation (Ogg/Opus nativ: AVAudioFile, AVURLAsset - auch ueber einen AVAssetResourceLoader wie im Chat).
// Eingang ist IMMER synthetisch (Sinus) bzw. die erzeugte Testdatei - kein Mikrofon, keine AVAudioEngine, kein Audio-Geraet.

final class VoiceCodecTests: XCTestCase {
    private func sine(_ sec: Double, hz: Float = 440, amp: Float = 0.3) -> [Float] {
        (0..<Int(sec * 48_000)).map { amp * sin(2 * .pi * hz * Float($0) / 48_000) }
    }
    private func encode(_ pcm: [Float], kbps: Int = 24) throws -> (Data, VoiceEncoder) {
        let e = try VoiceEncoder(kbps: kbps)
        for s in stride(from: 0, to: pcm.count, by: 4_096) { try e.append(Array(pcm[s..<min(pcm.count, s + 4_096)])) }
        return (try e.finish(), e)
    }

    func testEncodeDemuxDecode() throws {
        let (ogg, e) = try encode(sine(3))
        XCTAssertEqual(e.seconds, 3, accuracy: 1e-9)
        XCTAssertEqual(e.levels.count, 30, "RMS je 100 ms")
        let f = try OggOpus.demux(ogg)
        XCTAssertEqual(f.duration, 3, accuracy: 0.001, "Dauer aus der Granule")
        XCTAssertTrue(f.packets.allSatisfy { OggOpus.samples($0.data) == 960 })
        let kbps = Double(ogg.count) * 8 / 3 / 1000
        XCTAssertLessThan(kbps, 34); XCTAssertGreaterThan(kbps, 14)
        let pcm = try VoiceDecoder().decode(f.packets.map(\.data))
        let n = Int(pcm.frameLength), x = pcm.floatChannelData![0]
        XCTAssertGreaterThan(n, 140_000)
        var sum: Float = 0, zc = 0
        for i in 4_800..<(n - 4_800) { sum += x[i] * x[i]; if (x[i - 1] < 0) != (x[i] < 0) { zc += 1 } }
        let rms = (sum / Float(n - 9_600)).squareRoot()
        XCTAssertEqual(rms, Float(0.3) / Float(2).squareRoot(), accuracy: 0.05, "Pegel bleibt erhalten")
        let hz = Double(zc) / 2 / (Double(n - 9_600) / 48_000)
        XCTAssertEqual(hz, 440, accuracy: 15, "Tonhoehe bleibt erhalten")
    }

    func testWaveformFromLevels() throws {
        // leise -> laut -> leise: Mitte voll, Raender kleiner
        var pcm: [Float] = []
        for (i, a) in [0.02, 0.1, 0.3, 0.1, 0.02].enumerated() { pcm += sine(1, hz: 300 + Float(i) * 50, amp: Float(a)) }
        let (_, e) = try encode(pcm)
        let wf = VoiceLogic.waveform(e.levels)
        XCTAssertEqual(wf.count, 64)
        XCTAssertEqual(wf.max(), 255)
        XCTAssertLessThan(wf[0], wf[32]); XCTAssertLessThan(wf[63], wf[32])
    }

    func testNativePlaybackOfFixture() async throws {
        // Ogg/Opus spielt AVFoundation selbst (iOS/macOS 26) - daher kein eigener Decoder im Chat
        let d = try loadVector("voice_sine", ext: "ogg")
        let u = FileManager.default.temporaryDirectory.appendingPathComponent("voice-test-\(UUID().uuidString).ogg")
        try d.write(to: u); defer { try? FileManager.default.removeItem(at: u) }
        let af = try AVAudioFile(forReading: u)
        XCTAssertEqual(af.fileFormat.streamDescription.pointee.mFormatID, kAudioFormatOpus)
        XCTAssertEqual(Double(af.length) / 48_000, 2.5, accuracy: 0.02)
        let asset = AVURLAsset(url: u)
        let playable = try await asset.load(.isPlayable)
        XCTAssertTrue(playable)
        let dur = try await asset.load(.duration).seconds
        XCTAssertEqual(dur, 2.5, accuracy: 0.15)
        // Dekodieren durch das System (Offline, ohne Audio-Geraet)
        let buf = AVAudioPCMBuffer(pcmFormat: af.processingFormat, frameCapacity: AVAudioFrameCount(af.length))!
        try af.read(into: buf)
        XCTAssertGreaterThan(buf.frameLength, 110_000)
    }

    /// Datei-Eingang wie VoiceRecorder.startFromFile (Simulator-Tests): fremdes Format (44,1 kHz Stereo) -> 48 kHz mono ->
    /// Opus -> Ogg. Rein offline mit AVAudioConverter - kein Audio-Geraet.
    func testFileInputResampling() throws {
        let src = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 44_100, channels: 2, interleaved: false)!
        let n = 44_100 * 2
        let b = AVAudioPCMBuffer(pcmFormat: src, frameCapacity: AVAudioFrameCount(n))!
        b.frameLength = AVAudioFrameCount(n)
        for c in 0..<2 { for i in 0..<n { b.floatChannelData![c][i] = 0.3 * sin(2 * .pi * 500 * Float(i) / 44_100) } }
        let conv = try XCTUnwrap(AVAudioConverter(from: src, to: VoiceFormats.pcm))
        let e = try VoiceEncoder()
        var pos = 0
        while pos < n {                                                  // in Stuecken wie der Abgriff (4800 Frames)
            let len = min(4_800, n - pos)
            let part = AVAudioPCMBuffer(pcmFormat: src, frameCapacity: AVAudioFrameCount(len))!
            part.frameLength = AVAudioFrameCount(len)
            for c in 0..<2 { part.floatChannelData![c].update(from: b.floatChannelData![c] + pos, count: len) }
            let out = AVAudioPCMBuffer(pcmFormat: VoiceFormats.pcm, frameCapacity: AVAudioFrameCount(Double(len) * 48_000 / 44_100 + 64))!
            var given = false
            _ = conv.convert(to: out, error: nil) { _, st in
                if given { st.pointee = .noDataNow; return nil }
                given = true; st.pointee = .haveData; return part
            }
            try e.append(UnsafeBufferPointer(start: out.floatChannelData![0], count: Int(out.frameLength)))
            pos += len
        }
        let f = try OggOpus.demux(try e.finish())
        XCTAssertEqual(f.duration, 2, accuracy: 0.05)
        XCTAssertEqual(VoiceLogic.waveform(e.levels).count, 64)
    }

    /// Wie im Chat: AVURLAsset ueber einen eigenen Schema-Lader (Range-Anfragen), Typ Ogg
    func testResourceLoaderStreaming() async throws {
        let d = try loadVector("voice_sine", ext: "ogg")
        let loader = MemLoader(d)
        let asset = AVURLAsset(url: URL(string: "saga-media-test://voice.ogg")!)
        asset.resourceLoader.setDelegate(loader, queue: loader.q)
        let playable = try await asset.load(.isPlayable)
        XCTAssertTrue(playable, "Ogg/Opus ueber den Resource-Loader abspielbar")
        let dur = try await asset.load(.duration).seconds
        XCTAssertEqual(dur, 2.5, accuracy: 0.15)
        XCTAssertGreaterThan(loader.requests, 0)
    }
}

/// Liefert Bytes aus dem Speicher mit Range wie ChatVideoLoader (ohne Netz)
final class MemLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    let data: Data
    let q = DispatchQueue(label: "test.voice.loader")
    var requests = 0
    init(_ d: Data) { data = d }
    func resourceLoader(_ l: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource r: AVAssetResourceLoadingRequest) -> Bool {
        requests += 1
        if let i = r.contentInformationRequest {
            i.contentType = UTType(filenameExtension: "ogg")?.identifier ?? "org.xiph.ogg-audio"
            i.contentLength = Int64(data.count); i.isByteRangeAccessSupported = true
        }
        if let dr = r.dataRequest {
            let a = Int(dr.currentOffset), b = dr.requestsAllDataToEndOfResource ? data.count : min(data.count, Int(dr.requestedOffset) + dr.requestedLength)
            if a < b { dr.respond(with: data.subdata(in: a..<b)) }
        }
        r.finishLoading()
        return true
    }
}
import UniformTypeIdentifiers
