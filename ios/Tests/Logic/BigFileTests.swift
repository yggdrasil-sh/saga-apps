import Foundation

// Grosse Dateien im Chat (2.5): v1 bis v1Max, darueber saga-file/v2 bis video_max - fuer jede Datei, nicht nur Videos.
// v1Max = file_max, ueber Servergrenzen hoechstens 10 MiB; ohne file2 bzw. big_files == false nur v1.
func bigFileTests() {
    let MB = 1_048_576, L = ChatFileSendLogic.self
    check(L.v1Max(fileMax: 50 * MB, fed: false) == 50 * MB, "v1Max lokal = file_max")
    check(L.v1Max(fileMax: 50 * MB, fed: true) == 10_485_760, "v1Max foederiert hoechstens 10 MiB")
    check(L.v1Max(fileMax: 5 * MB, fed: true) == 5 * MB, "v1Max foederiert: kleinere file_max bleibt")
    // lokal, Server mit 50 MB und file2
    check(L.plan(size: Int64(50 * MB), fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: false, bigFiles: nil) == .v1, "lokal: bis file_max v1")
    check(L.plan(size: Int64(50 * MB + 1), fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: false, bigFiles: nil) == .v2, "lokal: darueber v2")
    check(L.plan(size: Int64(500 * MB), fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: false, bigFiles: nil) == .v2, "lokal: genau video_max v2")
    check(L.plan(size: Int64(500 * MB + 1), fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: false, bigFiles: nil) == .tooBig(500), "lokal: ueber video_max zu gross")
    check(L.plan(size: Int64(600 * MB), fileMax: 50 * MB, file2: true, videoMax: nil, fed: false, bigFiles: nil) == .tooBig(500), "video_max fehlt: 500 MB")
    check(L.plan(size: Int64(20 * MB), fileMax: 50 * MB, file2: true, videoMax: 10 * MB, fed: false, bigFiles: nil) == .v1, "video_max kleiner als file_max: v1 bleibt")
    // Server ohne file2: nur v1
    check(L.plan(size: Int64(50 * MB + 1), fileMax: 50 * MB, file2: false, videoMax: nil, fed: false, bigFiles: nil) == .serverOld(50), "ohne file2: nur bis file_max")
    // foederiert
    check(L.plan(size: 10_485_760, fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: true, bigFiles: true) == .v1, "foed: bis 10 MiB v1")
    check(L.plan(size: 10_485_761, fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: true, bigFiles: true) == .v2, "foed: ueber 10 MiB v2 (auch wenn file_max groesser)")
    check(L.plan(size: 10_485_761, fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: true, bigFiles: false) == .peerOld(10), "foed ohne big_files: nur bis 10 MiB")
    check(L.plan(size: 10_485_761, fileMax: 50 * MB, file2: false, videoMax: nil, fed: true, bigFiles: true) == .serverOld(10), "foed, eigener Server ohne file2: 10 MiB")
    check(L.plan(size: Int64(501 * MB), fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: true, bigFiles: true) == .tooBig(500), "foed: ueber video_max")
    // Videos folgen derselben Regel
    check(VideoSendLogic.plan(size: 10_485_761, fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: true, bigFiles: true) == .v2, "Video foed: ueber 10 MiB v2")
    check(VideoSendLogic.plan(size: Int64(20 * MB), fileMax: 50 * MB, file2: true, videoMax: 500 * MB, fed: true, bigFiles: false) == .peerOld(10), "Video foed ohne big_files: 10 MiB")
    // Anhaengen: ab v1Max als Datei (nicht in den Speicher)
    check(!L.copyToFile(size: Int64(50 * MB), v1Max: 50 * MB) && L.copyToFile(size: Int64(50 * MB + 1), v1Max: 50 * MB), "als Datei kopieren erst ueber v1Max")
    check(L.mb(10_485_760) == 10 && L.mb(1) == 1, "MB-Anzeige")
    // Anzeige: v2 ohne Video ist eine Datei (QuickLook), kein Video
    let pdf2 = ChatFileRef(id: "a", key: "k", name: "gross.pdf", mime: "application/pdf", size: 60 * MB, x: 2, cs: 262_144)
    check(pdf2.isV2 && !pdf2.isVideo, "v2-PDF ist kein Video")
    let png2 = ChatFileRef(id: "b", key: "k", name: "gross.png", mime: "image/png", size: 60 * MB, x: 2, cs: 262_144)
    check(ChatStd.mediaKind(png2) == .file && ChatStd.mediaKind(pdf2) == .file, "v2-Bild/Datei in der Medienuebersicht als Datei")
    check(png2.dict["x"] as? Int == 2 && png2.dict["cs"] as? Int == 262_144, "v2-Felder im Anhang")
}
