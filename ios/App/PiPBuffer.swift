import Foundation
import CoreMedia
import CoreVideo

// Bild-im-Bild (iOS, Saga 2.4): Videobild des Gegenuebers -> CMSampleBuffer fuer AVSampleBufferDisplayLayer.
// Ohne WebRTC (mit swiftc pruefbar). Bilder aus dem Hardware-Decoder sind schon CVPixelBuffer: sie werden NICHT
// kopiert, nur eingepackt; die Formatbeschreibung wird wiederverwendet, solange Groesse/Format gleich bleiben.
// Nur I420-Bilder (Software-Decoder, Testbild) werden in einen NV12-Puffer aus einem Pool umgerechnet.

final class PiPSampleMaker {
    private var format: CMVideoFormatDescription?
    private var pool: CVPixelBufferPool?
    private var poolSize = (w: 0, h: 0)
    private let lock = NSLock()
    /// Zaehler fuer Tests/Diagnose: neu angelegte Formatbeschreibungen bzw. umgerechnete (kopierte) Bilder
    private(set) var formatsMade = 0
    private(set) var conversions = 0

    /// CVPixelBuffer ohne Kopie einpacken; sofort anzeigen (kein Zeitplan, Anruf = live)
    func wrap(_ pb: CVPixelBuffer, ptsNs: Int64) -> CMSampleBuffer? {
        lock.lock(); defer { lock.unlock() }
        if format == nil || !CMVideoFormatDescriptionMatchesImageBuffer(format!, imageBuffer: pb) {
            var f: CMVideoFormatDescription?
            guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pb, formatDescriptionOut: &f) == noErr, let f else { return nil }
            format = f; formatsMade += 1
        }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMTime(value: ptsNs, timescale: 1_000_000_000), decodeTimeStamp: .invalid)
        var sb: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pb, formatDescription: format!,
                                                       sampleTiming: &timing, sampleBufferOut: &sb) == noErr, let sb else { return nil }
        if let a = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: true), CFArrayGetCount(a) > 0 {
            let d = unsafeBitCast(CFArrayGetValueAtIndex(a, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(d, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sb
    }

    /// I420 (drei Ebenen) -> NV12 aus einem Pool (Rueckfall, kopiert; Hardware-Bilder laufen ueber wrap)
    func nv12(width w: Int, height h: Int, y: UnsafePointer<UInt8>, strideY: Int, u: UnsafePointer<UInt8>, strideU: Int,
              v: UnsafePointer<UInt8>, strideV: Int) -> CVPixelBuffer? {
        guard w > 0, h > 0 else { return nil }
        lock.lock()
        if pool == nil || poolSize != (w, h) {
            let attrs: [String: Any] = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                                        kCVPixelBufferWidthKey as String: w, kCVPixelBufferHeightKey as String: h,
                                        kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any]()]
            var p: CVPixelBufferPool?
            CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attrs as CFDictionary, &p)
            pool = p; poolSize = (w, h)
        }
        let pl = pool
        conversions += 1
        lock.unlock()
        guard let pl else { return nil }
        var out: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pl, &out) == kCVReturnSuccess, let pb = out else { return nil }
        CVPixelBufferLockBaseAddress(pb, [])
        defer { CVPixelBufferUnlockBaseAddress(pb, []) }
        guard let dy = CVPixelBufferGetBaseAddressOfPlane(pb, 0)?.assumingMemoryBound(to: UInt8.self),
              let duv = CVPixelBufferGetBaseAddressOfPlane(pb, 1)?.assumingMemoryBound(to: UInt8.self) else { return nil }
        let sy = CVPixelBufferGetBytesPerRowOfPlane(pb, 0), suv = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
        for r in 0..<h { memcpy(dy + r * sy, y + r * strideY, w) }
        let cw = (w + 1) / 2, ch = (h + 1) / 2
        for r in 0..<ch {
            let row = duv + r * suv, ur = u + r * strideU, vr = v + r * strideV
            for c in 0..<cw { row[2 * c] = ur[c]; row[2 * c + 1] = vr[c] }
        }
        return pb
    }
}
