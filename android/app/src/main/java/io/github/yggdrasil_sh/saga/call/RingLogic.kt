package io.github.yggdrasil_sh.saga.call

/**
 * Klingeln eingehender Anrufe (2.5): Die App klingelt selbst (CallRinger: Klingelton in Schleife + wiederholte
 * Vibration), die Benachrichtigung kommt ueber einen Kanal ohne eigenen Ton. Grund: Kanal-Einstellungen sind nach dem
 * ersten Anlegen eingefroren (alte Installationen), und Android 15/16 daempft wiederholte Toene einer App
 * (Notification Cooldown) - dann klang der Anruf bei geschlossener App nur einmal.
 * Reine Entscheidungen ohne Android (Unit-Tests: RingLogicTest); die Werte entsprechen den Android-Konstanten.
 */
object RingLogic {
    // AudioManager.RINGER_MODE_*
    const val RINGER_SILENT = 0
    const val RINGER_VIBRATE = 1
    const val RINGER_NORMAL = 2
    // NotificationManager.INTERRUPTION_FILTER_*
    const val FILTER_UNKNOWN = 0
    const val FILTER_ALL = 1
    const val FILTER_PRIORITY = 2
    const val FILTER_NONE = 3
    const val FILTER_ALARMS = 4

    /** Vibrationsmuster: 1 s an, 1 s aus (wie die Telefon-App), wiederholt ab Index 0 */
    val PATTERN = longArrayOf(0, 1000, 1000)
    /** Sicherheitsnetz: laenger klingelt es nie (der Server beendet nach ~45 s) */
    const val MAX_RING_MS = 120_000L
    /** Ohne Vordergrund-Dienst (UnifiedPush aus dem Hintergrund): Empfaenger so lange offen halten (Broadcast-Frist 60 s) */
    const val HOLD_MAX_MS = 55_000L
    /** Abfrage des Anrufzustands, solange es ohne Live-Kanal klingelt (Gegenseite legt auf, anderes Geraet nimmt an) */
    const val POLL_MS = 3_000L

    data class Mode(val sound: Boolean, val vibrate: Boolean) {
        val any: Boolean get() = sound || vibrate
    }

    /**
     * Ton und Vibration nach Geraet: Stumm -> nichts, Vibration -> nur Vibration, Normal -> beides (Lautstaerke und
     * Vibrationsstaerke fuer Anrufe regelt das System ueber USAGE_NOTIFICATION_RINGTONE bzw. USAGE_RINGTONE).
     * Bitte nicht stoeren: "Keine Unterbrechungen"/"Nur Wecker" -> nichts; "Nur wichtige" -> nur, wenn Anrufe von allen
     * durchkommen (Saga-Anrufe haben keinen Kontakt im Adressbuch).
     */
    fun mode(ringerMode: Int, filter: Int, priorityCallsFromAnyone: Boolean): Mode {
        val dndBlocks = when (filter) {
            FILTER_NONE, FILTER_ALARMS -> true
            FILTER_PRIORITY -> !priorityCallsFromAnyone
            else -> false
        }
        if (dndBlocks) return Mode(sound = false, vibrate = false)
        return when (ringerMode) {
            RINGER_SILENT -> Mode(sound = false, vibrate = false)
            RINGER_VIBRATE -> Mode(sound = false, vibrate = true)
            else -> Mode(sound = true, vibrate = true)
        }
    }

    /** Klingeln nur mit sichtbarer Oberflaeche: Benachrichtigung erlaubt oder App im Vordergrund (Anrufbildschirm) */
    fun shouldRing(canNotify: Boolean, foreground: Boolean): Boolean = canNotify || foreground

    /** Kein zweites Klingeln fuer denselben Anruf (ein Ringer, eine Tonquelle - auch mit offenem Anrufbildschirm) */
    fun shouldStart(current: String?, id: String): Boolean = id.isNotEmpty() && current != id

    /** Klingelt die Einladung noch? (Abfrage ohne Live-Kanal) - 1:1: Zustand ringing; Gruppe: eigene Einladung und kein eigenes Geraet dabei */
    fun stillRinging(info: CallInfo, me: String): Boolean =
        if (info.kind == CallRoom.KIND_GROUP) info.state != "ended" && info.invite == "ringing" && !CallRoom.stopRinging(info.legs, me)
        else info.state == "ringing" && info.role == "callee"

    /** Verpasst anzeigen, wenn die Gegenseite vor dem Annehmen aufgelegt hat */
    fun missed(info: CallInfo): Boolean = info.state == "ended" && info.reason == "cancelled"

    /** Lokales Zeitlimit des Klingelns (wie bisher: Ablauf laut Server + 3 s, mindestens 5 s) */
    fun ringMs(expiresIn: Double?, ringS: Int?): Long = ((expiresIn ?: (ringS ?: 45).toDouble()).coerceAtLeast(5.0) * 1000).toLong() + 3000

    /**
     * Wie lange der Push-Empfaenger offen bleibt: mit Vordergrund-Dienst gar nicht, sonst bis zum Ende des Klingelns -
     * hoechstens HOLD_MAX_MS (Hintergrund-Broadcast: 60 s Frist), bei einem Vordergrund-Broadcast (FLAG_RECEIVER_FOREGROUND,
     * 10 s Frist, sonst ANR und Prozess-Ende) nur HOLD_FG_MAX_MS ab Empfang.
     */
    fun holdMs(inService: Boolean, ringLeftMs: Long, fgBroadcast: Boolean = false, elapsedMs: Long = 0L): Long = when {
        inService -> 0L
        fgBroadcast -> minOf(ringLeftMs, HOLD_FG_MAX_MS - elapsedMs).coerceAtLeast(0L)
        else -> ringLeftMs.coerceIn(0L, HOLD_MAX_MS - elapsedMs.coerceAtLeast(0L))
    }

    const val HOLD_FG_MAX_MS = 9_000L

    /** Wann der eigene Ton startet */
    enum class Sound { NOW, AFTER_SERVICE, NONE }

    /**
     * Eigener Ton: App sichtbar -> sofort. Im Hintergrund ist er nur hoerbar, wenn der Prozess schon einen im
     * Vordergrund gestarteten Dienst hat (eigene Verbindung): dann erst, wenn der Klingel-Dienst laeuft. Sonst schaltet
     * Android 17 ("Audio-Haertung") die Wiedergabe stumm - auch mit Klingel-Dienst -, und die Mitteilung klingelt ueber
     * den Ersatzkanal (Systemton, FLAG_INSISTENT). Vibration macht der Ringer in jedem Fall.
     */
    fun sound(foreground: Boolean, serviceStarted: Boolean, audibleInBackground: Boolean): Sound = when {
        foreground -> Sound.NOW
        serviceStarted && audibleInBackground -> Sound.AFTER_SERVICE
        else -> Sound.NONE
    }

    /** Ersatzkanal mit Systemton nur, wenn die App selbst nicht hoerbar klingeln kann und das Geraet Ton will */
    fun fallback(sound: Sound, mode: Mode, notified: Boolean): Boolean = notified && sound == Sound.NONE && mode.sound

    /** Festhalten vorbei, es klingelt noch und kein Dienst haelt den Prozess: Ton/Vibration beenden (Prozess kann einfrieren) */
    fun stopAfterHold(ringing: Boolean, inService: Boolean): Boolean = ringing && !inService

    /** Typ des Vordergrund-Dienstes fuer das Klingeln: phoneCall (ab Android 10; braucht MANAGE_OWN_CALLS), davor keiner */
    fun serviceType(sdk: Int): Int = if (sdk >= 29) 4 /* ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL */ else 0
}
