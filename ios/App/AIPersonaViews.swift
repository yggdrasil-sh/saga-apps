import SwiftUI

// Persona der KI (Saga 2.1): Name + Profilbild ueberall, wo die App KI-Funktionen anbietet. Bild ueber
// /api/ai/avatar (wie Profilbilder: AvatarCache, ETag/If-None-Match), ohne Bild ein neutrales Symbol (✦ auf Gold).
// Aeltere Server ohne Persona: bisherige Beschriftung ("KI-Assistent", "Frag deine Seiten"). Logik: AILogic.swift

/// Rundes Profilbild der KI; ohne Bild (oder solange es laedt) das neutrale Symbol
struct AIAvatarView: View {
    var persona: AIPersona?
    var size: CGFloat = 24
    @Environment(\.displayScale) private var scale
    @ObservedObject private var cache = AvatarCache.shared
    @State private var img: PlatformImage?
    private var shown: String? { AvatarLogic.current(persona?.avatar, changes: cache.changes) }
    var body: some View {
        ZStack {
            Circle().fill(Color.gold)
            Image(systemName: "sparkle").font(.system(size: size * 0.5, weight: .semibold)).foregroundStyle(.white)
            if let img { Image(platformImage: img).resizable().scaledToFill() }
        }
        .frame(width: size, height: size).clipShape(Circle())
        .accessibilityHidden(true)
        .task(id: shown) {
            let px = AvatarLogic.step(points: Double(size), scale: Double(scale)), avatar = shown
            guard avatar != nil else { img = nil; return }
            img = AvatarCache.shared.cached(avatar, px: px)
            if img == nil || !AvatarCache.shared.isFresh(avatar, px: px) { img = await AvatarCache.shared.image(avatar, px: px) }
        }
    }
}

/// Kopf einer KI-Antwort: Bild + Name (+ "gefragt von …")
struct AIWhoLine: View {
    var persona: AIPersona?
    var name: String
    var askedBy: String?
    var size: CGFloat = 18
    var body: some View {
        HStack(spacing: 5) {
            AIAvatarView(persona: persona, size: size)
            Text(verbatim: name).font(.caption.weight(.semibold))
            if let askedBy { Text("gefragt von \(askedBy)").font(.caption).foregroundStyle(.secondary) }
        }
        .accessibilityElement(children: .combine)
    }
}

extension Store {
    /// Name der KI (Persona) - nil bei aelteren Servern
    var aiName: String? { aiPersona?.name }
    /// Einstieg in den Assistenten: "Frage Marin" bzw. bisher "Frag deine Seiten"
    var aiAskLabel: String { aiName.map { String(localized: "Frage \($0)") } ?? String(localized: "Frag deine Seiten") }
    /// Menue/Knopf "KI-Assistent" bzw. "Frage Marin"
    var aiMenuLabel: String { aiName.map { String(localized: "Frage \($0)") } ?? String(localized: "KI-Assistent") }
    /// "Marin denkt nach …" bzw. bisher "Denkt nach …"
    var aiThinking: String { aiName.map { String(localized: "\($0) denkt nach …") } ?? String(localized: "Denkt nach …") }
}
