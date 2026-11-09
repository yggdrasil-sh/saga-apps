package io.github.yggdrasil_sh.saga.call

/**
 * Design-Runde 5 (Anruf-Oberflaeche): reine Regeln der Knopfleiste, der Audio-Ausgabe, der Infozeile und des
 * "Mehr"-Blatts - ohne Android/Compose, damit sie per Unit-Test pruefbar sind.
 */

/** Art einer Audio-Ausgabe im Anruf (Hoerer, Lautsprecher, Bluetooth, Kabel/USB/Hoergeraet) */
enum class AudioKind { EARPIECE, SPEAKER, BLUETOOTH, WIRED }

/** Eine waehlbare Ausgabe: id = AudioDeviceInfo.id (0 = unbekannt), name nur bei Bluetooth (Geraetename) */
data class AudioRoute(val id: Int, val kind: AudioKind, val name: String = "")

/** Ausgaben des Geraets im Anruf; active = id der aktiven Ausgabe; earpiece = Geraet hat einen Hoerer (kein Tablet) */
data class AudioRoutes(val routes: List<AudioRoute> = emptyList(), val active: Int? = null, val earpiece: Boolean = true)

internal object CallControls {
    /** CH-17: Untergrenze der Beschriftung (sp) - kleiner wird es nicht, dann eine Reihe mehr */
    const val MIN_LABEL_SP = 11f

    /** CH-19: welcher Audio-Knopf in der Leiste steht */
    enum class AudioBtn { NONE, TOGGLE, PICKER }

    /** CH-18: feste Knoepfe der Leiste (Reihenfolge = Anzeige) */
    enum class Btn { MIC, CAM, AUDIO, PLAYBACK, MORE, HANGUP }

    /** CH-18: Eintraege im "Mehr"-Blatt */
    enum class More { SHARE, SWITCH_CAM, LAYOUT, PEOPLE, SELF_SIZE, FULLSCREEN, DETAILS }

    /**
     * CH-19: mehr als zwei Ausgaben (z. B. Hoerer, Lautsprecher, Bluetooth) -> "Audio" mit Auswahl; ohne Hoerer (Tablet)
     * gibt es nichts umzuschalten -> kein Knopf (mit Bluetooth am Tablet: Auswahl zwischen Lautsprecher und Bluetooth);
     * sonst der Umschalter "Lautsprecher". Ohne Liste (Android < 12) entscheidet nur der Hoerer.
     */
    fun audioButton(r: AudioRoutes): AudioBtn {
        val outs = r.routes.distinctBy { it.kind to it.name }
        if (outs.isEmpty()) return if (r.earpiece) AudioBtn.TOGGLE else AudioBtn.NONE
        val earpiece = outs.any { it.kind == AudioKind.EARPIECE }
        return when {
            outs.size > 2 -> AudioBtn.PICKER
            !earpiece -> if (outs.size >= 2) AudioBtn.PICKER else AudioBtn.NONE
            else -> AudioBtn.TOGGLE
        }
    }

    /** 2.5 (9.11.): Telecom-Weg (CallAudioState.ROUTE_*) einer Ausgabe - EARPIECE 1, BLUETOOTH 2, WIRED_HEADSET 4, SPEAKER 8 */
    fun telecomRoute(k: AudioKind): Int = when (k) {
        AudioKind.EARPIECE -> 1
        AudioKind.BLUETOOTH -> 2
        AudioKind.WIRED -> 4
        AudioKind.SPEAKER -> 8
    }

    /** 2.5 (9.11.): Android 14+ CallEndpoint.TYPE_* - EARPIECE 1, BLUETOOTH 2, WIRED_HEADSET 3, SPEAKER 4 */
    fun endpointType(k: AudioKind): Int = when (k) {
        AudioKind.EARPIECE -> 1
        AudioKind.BLUETOOTH -> 2
        AudioKind.WIRED -> 3
        AudioKind.SPEAKER -> 4
    }

    /**
     * 2.5 (9.11.): Ein-/Ausstecken waehrend des Anrufs - nur umschalten, wenn ein Headset dazukam oder die aktive Ausgabe
     * verschwand; sonst bleibt die Wahl des Nutzers stehen (vorher stellte jedes Geraete-Ereignis, auch der Aufruf direkt
     * nach dem Anmelden des Rueckrufs, den Ton wieder aufs Headset bzw. den Lautsprecher zurueck)
     */
    fun reroute(first: Boolean, headsetAdded: Boolean, activeRemoved: Boolean): Boolean = !first && (headsetAdded || activeRemoved)

    /** CH-19: Ausgaben fuer das Auswahl-Blatt - doppelte Eintraege (SCO und BLE desselben Geraets) einmal, feste Reihenfolge */
    fun pickerRoutes(r: AudioRoutes): List<AudioRoute> =
        r.routes.distinctBy { it.kind to it.name }.sortedBy { it.kind.ordinal }

    /** Ist die Ausgabe aktiv? Ohne bekannte id: die erste ihrer Art (Doppel-Eintraege) */
    fun isActive(r: AudioRoutes, route: AudioRoute): Boolean {
        val act = r.routes.firstOrNull { it.id == r.active } ?: return false
        return act.kind == route.kind && act.name == route.name
    }

    /** CH-18: Knopfleiste - Mikrofon, Kamera, Audio (bzw. "Ton an/aus" am Begleitgeraet), Mehr, Auflegen */
    fun bar(audio: AudioBtn, playback: Boolean): List<Btn> = buildList {
        add(Btn.MIC)
        add(Btn.CAM)
        if (playback) add(Btn.PLAYBACK) else if (audio != AudioBtn.NONE) add(Btn.AUDIO)
        add(Btn.MORE)
        add(Btn.HANGUP)
    }

    /**
     * CH-18/CH-27: Inhalt des "Mehr"-Blatts. connected: Bildschirm teilen; cam: Kamera wechseln; meeting: Layout
     * (wenn sinnvoll), Teilnehmende, Groesse des eigenen Bilds (mit eigenem Bild), Vollbild (wenn jemand teilt).
     * "Verbindungsdetails" immer (CH-15).
     */
    fun moreItems(
        connected: Boolean, cam: Boolean, layout: Boolean = false, people: Boolean = false, selfSize: Boolean = false,
        fullscreen: Boolean = false,
    ): List<More> = buildList {
        if (connected) add(More.SHARE)
        if (cam) add(More.SWITCH_CAM)
        if (layout) add(More.LAYOUT)
        if (people) add(More.PEOPLE)
        if (selfSize) add(More.SELF_SIZE)
        if (fullscreen) add(More.FULLSCREEN)
        add(More.DETAILS)
    }

    /**
     * R5-Fix: Sicherheitszeile in Infozeile und Pille immer kurz - geprueft "Ende-zu-Ende-verschluesselt", sonst
     * "Verschluesselt"; "Sicherheitsnummer nicht geprueft" steht nur im Blatt "Verbindungsdetails".
     */
    fun securityShort(verified: Boolean): Int =
        if (verified) io.github.yggdrasil_sh.saga.R.string.call_e2e else io.github.yggdrasil_sh.saga.R.string.r5_call_encrypted_short

    /** Langform im Blatt "Verbindungsdetails" */
    fun securityLong(verified: Boolean): Int =
        if (verified) io.github.yggdrasil_sh.saga.R.string.call_e2e else io.github.yggdrasil_sh.saga.R.string.r5_call_unverified

    /** CH-15: Hinweis "Schwache Verbindung" in der Infozeile nur bei mittlerer oder schwacher Qualitaet */
    fun weak(quality: String): Boolean = quality == "mid" || quality == "bad"

    /**
     * CH-15: TURN-Hinweis im Blatt "Verbindungsdetails": ohne TURN-Server (Server sagt es) und nicht ueber Relais, wenn
     * die Verbindung schwach ist - sonst stoert er nicht.
     */
    fun turnHint(hasTurn: Boolean?, path: String, quality: String): Boolean = hasTurn == false && path != "relay" && weak(quality)

    /** Ergebnis der Reihen-Aufteilung: Knoepfe je Reihe (erste Reihe voll, Rest darunter) und gemeinsame Schriftgroesse */
    data class Plan(val perRow: Int, val sp: Float, val hyphenate: Boolean = false) {
        fun rows(n: Int): List<Int> = if (n <= 0) emptyList() else (0 until n).chunked(perRow).map { it.size }
    }

    /**
     * CH-17: gleiche Schrift in einer Reihe. rowFont(perRow) liefert die groesste Schrift, bei der ALLE Beschriftungen
     * in die Spalte passen (null = passt nicht einmal mit MIN_LABEL_SP). Gesucht wird die groesste Zahl Knoepfe je Reihe
     * (hoechstens maxPerRow), bei der das gilt; der Rest wandert in die naechste Reihe. Passt es auch einzeln nicht:
     * halbe Reihe mit Untergrenze und Silbentrennung.
     */
    fun plan(n: Int, maxPerRow: Int = n, rowFont: (perRow: Int) -> Float?): Plan {
        if (n <= 0) return Plan(1, MIN_LABEL_SP)
        val top = maxPerRow.coerceIn(1, n)
        for (k in top downTo 1) {
            val sp = rowFont(k)
            // 2 Reihen sollen reichen: bei mehr als zwei Reihen lieber trennen als die Leiste hoch machen
            if (sp != null && sp >= MIN_LABEL_SP && (n + k - 1) / k <= 2) return Plan(k, sp)
        }
        return Plan(((n + 1) / 2).coerceAtMost(top), MIN_LABEL_SP, hyphenate = true)
    }

    /**
     * Groesste Schrift zwischen max und MIN_LABEL_SP (0,5-sp-Schritte), bei der fits() fuer alle Beschriftungen gilt;
     * null, wenn auch die Untergrenze nicht passt.
     */
    fun rowFont(max: Float, fits: (Float) -> Boolean): Float? {
        var sp = maxOf(max, MIN_LABEL_SP)
        while (sp >= MIN_LABEL_SP) {
            if (fits(sp)) return sp
            sp -= 0.5f
        }
        return null
    }
}

/** Feldtest: Display am Ohr aus (Naeherungssensor) - reine Entscheidung, siehe CallActivity.updateProximity */
internal object CallProximity {
    /** Sicherung gegen ein Wakelock-Leck: spaetestens nach 4 h gibt das System die Sperre frei */
    const val TIMEOUT_MS = 4L * 60 * 60 * 1000

    /**
     * Naeherungs-Sperre halten? Nur im laufenden Anruf (ausgehend klingelnd, verbindend, verbunden - nie beim noch nicht
     * angenommenen Klingeln), ohne eigenes Video, ohne Bildschirmfreigabe, ohne Videoraster, mit Ausgabe = Hoerer und
     * nur, solange die Anruf-Ansicht im Vordergrund und nicht im Bild-im-Bild ist.
     */
    fun shouldUseProximity(
        phase: CallPhase, cam: Boolean, sharing: Boolean, videoGrid: Boolean, earpiece: Boolean, foreground: Boolean, pip: Boolean,
    ): Boolean = phase in ACTIVE && !cam && !sharing && !videoGrid && earpiece && foreground && !pip

    private val ACTIVE = setOf(CallPhase.RINGING, CallPhase.CONNECTING, CallPhase.CONNECTED)

    /**
     * Laeuft der Ton ueber den Hoerer? Mit Geraeteliste (Android 12+) entscheidet die aktive Ausgabe; ohne Liste
     * (aelter): kein Lautsprecher und Geraet mit Hoerer.
     */
    fun onEarpiece(r: AudioRoutes, speaker: Boolean): Boolean {
        if (r.routes.isEmpty()) return !speaker && r.earpiece
        val act = r.routes.firstOrNull { it.id == r.active } ?: return false
        return act.kind == AudioKind.EARPIECE
    }

    /** Meeting-Videoraster: Kachel-/Knotenansicht, in der jemand Kamera oder Bildschirm zeigt */
    fun videoGrid(st: CallUiState): Boolean {
        val room = st.room ?: return false
        if (!room.mesh && room.horn == null) return false
        return st.meet.any { it.cam || it.screen } || room.tiles.any { it.video || it.screen } || room.horn?.tiles?.any { it.video } == true
    }
}
