import Foundation

// Chat-Medien 1: Bildergalerie (Liste, Start, Blaettern, Zaehler), Antwort-Zitat (Beschriftung, Vorschaubild),
// Weiterleiten von Videos
func mediaViewerTests() {
    typealias G = ChatGalleryLogic
    typealias R = ChatReplyPreview
    let img1 = ChatFileRef(id: "i1", key: "k", name: "a.jpg", mime: "image/jpeg", size: 9, w: 40, h: 30)
    let img2 = ChatFileRef(id: "i2", key: "k", name: "b.png", mime: "image/png", size: 9)
    var thumbOnly = ChatFileRef(id: "i3", key: "k", name: "c.bin", mime: "application/octet-stream", size: 9)
    thumbOnly.thumb = ChatThumbRef(id: "t3", key: "k", w: 32, h: 24, size: 5)
    let vid = ChatFileRef(id: "v1", key: "k", name: "film.mp4", mime: "video/mp4", size: 9, dur: 3)
    var vidPoster = vid; vidPoster.id = "v2"; vidPoster.thumb = ChatThumbRef(id: "t4", key: "k", w: 32, h: 18, size: 5)
    let big = ChatFileRef(id: "b1", key: "k", name: "gross.png", mime: "image/png", size: 9, x: 2, cs: 262_144)
    let pdf = ChatFileRef(id: "p1", key: "k", name: "brief.pdf", mime: "application/pdf", size: 9)
    let newer = ChatFileRef(id: "n1", key: "k", name: "x.jpg", mime: "image/jpeg", size: 9, x: 3)
    var voice = ChatFileRef(id: "s1", key: "k", name: "", mime: "audio/ogg", size: 9); voice.voice = true

    // Was ist ein Galerie-Bild (wie im Verlauf als Bild gezeigt)
    check(G.isImage(img1) && G.isImage(img2), "Bild-MIME: Galerie")
    check(G.isImage(thumbOnly), "mit Vorschaubild: Galerie")
    check(!G.isImage(vid) && !G.isImage(vidPoster), "Video: keine Galerie")
    check(!G.isImage(big), "v2-Bild (grosse Datei, QuickLook): keine Galerie")
    check(!G.isImage(pdf) && !G.isImage(voice), "Datei/Sprachnachricht: keine Galerie")
    check(!G.isImage(newer), "neueres Format: keine Galerie")

    // Liste in Verlaufsreihenfolge, doppelte Kennungen einmal
    let list = G.images([[img1], [pdf, vid], [], [img2, thumbOnly], [img1]])
    check(list.map(\.id) == ["i1", "i2", "i3"], "Reihenfolge der Nachrichten, ohne Video/Datei, ohne Doppelte")
    check(G.images([]).isEmpty, "leer")

    // Start beim angetippten Bild
    check(G.open(list, at: img2).index == 1 && G.open(list, at: img2).list.count == 3, "Start beim angetippten Bild")
    check(G.open(list, at: img1).index == 0, "erstes Bild")
    let other = ChatFileRef(id: "x9", key: "k", name: "alt.jpg", mime: "image/jpeg", size: 9)
    let solo = G.open(list, at: other)
    check(solo.list.map(\.id) == ["x9"] && solo.index == 0, "nicht geladen (Medien-Blatt): nur dieses Bild")

    // Blaettern am Rand stehen bleiben
    check(G.step(0, by: 1, count: 3) == 1 && G.step(2, by: 1, count: 3) == 2, "vor, am Ende stehen")
    check(G.step(0, by: -1, count: 3) == 0 && G.step(2, by: -1, count: 3) == 1, "zurueck, am Anfang stehen")
    check(G.step(5, by: 0, count: 0) == 0, "leer: 0")

    // Zaehler "n / m"
    check(G.counter(0, count: 3) == "1 / 3" && G.counter(2, count: 3) == "3 / 3", "Zaehler")
    check(G.counter(0, count: 1) == nil, "ein Bild: kein Zaehler")
    check(G.counter(3, count: 3) == nil && G.counter(-1, count: 3) == nil, "ausserhalb: kein Zaehler")

    // Blaettern nur ohne Zoom
    check(G.canPage(scale: 1) && G.canPage(scale: 1.005), "ohne Zoom: blaettern")
    check(!G.canPage(scale: 1.5) && !G.canPage(scale: 6), "gezoomt: nicht blaettern")

    // Antwort-Zitat: Beschriftung
    check(R.label(text: "  Hallo ", files: [img1]) == .text("Hallo"), "Text geht vor")
    check(R.label(text: "", files: [img1]) == .photo, "nur Bild: Foto")
    check(R.label(text: " \n", files: [thumbOnly]) == .photo, "nur Leerzeichen + Bild mit Vorschau: Foto")
    check(R.label(text: "", files: [vid]) == .video, "nur Video: Video")
    check(R.label(text: "", files: [voice]) == .voice, "Sprachnachricht")
    check(R.label(text: "", files: [pdf]) == .file("brief.pdf"), "Datei: Name")
    check(R.label(text: "", files: [big]) == .file("gross.png"), "grosse Datei (v2-Bild): Name")
    check(R.label(text: "", files: []) == .none, "nichts")

    // Antwort-Zitat: Vorschaubild nie aus Video/grosser Datei selbst
    check(R.thumbFile([img1])?.id == "i1", "Bild: Vorschaubild")
    check(R.thumbFile([vid]) == nil, "Video ohne Poster: kein Vorschaubild")
    check(R.thumbFile([vidPoster])?.id == "v2", "Video mit Poster: Poster")
    check(R.thumbFile([big]) == nil, "grosse Datei ohne th: kein Vorschaubild")
    check(R.thumbFile([voice, pdf, img2])?.id == "i2", "erster Anhang mit Vorschaubild")

    // Weiterleiten: Videos werden neu hochgeladen, Sprachnachrichten bleiben ein Hinweis
    typealias L = ChatSelectLogic
    check(L.Att.video("f.mp4").forwardable, "Video weiterleitbar")
    check(!L.Att.voice.forwardable, "Sprachnachricht nicht weiterleitbar")
    let t0 = Date(timeIntervalSince1970: 0)
    check(L.forward(L.Item(id: "v", seq: 1, mine: true, name: "X", date: t0, text: "Film", atts: [.video("f.mp4")])) == L.Forward(text: "Film", upload: [0], skipped: 0),
          "Video wird neu hochgeladen")
}
