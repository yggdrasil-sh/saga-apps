package io.github.yggdrasil_sh.saga.call

import io.github.yggdrasil_sh.saga.data.ApiException
import kotlinx.coroutines.CancellationException

/**
 * 2.6 (Bugs-DB 2387): Test-Naht fuer die Verdrahtung in CallManager - checkRing (wohin geht ein eingehender Anruf),
 * holdTake ("Beenden und annehmen"), hangupConfirmed (wann gilt das Ende als bestaetigt) und holdFreed (Gespraech hier
 * zu Ende). Reine Entscheidungen ohne Android; CallManager fuehrt nur aus. Referenz: static/calls.js (holdBar, holdTake,
 * holdFree, busyElsewhere).
 */
object HoldFlow {

    // ------------------------------------------------------------ checkRing

    /** Was mit einem eingehenden Anruf (GET /api/calls/{id}) passiert */
    sealed interface Route {
        /** nichts tun (schon hier, vorbei, nicht meiner) */
        data object Ignore : Route
        /** nur die Leiste "Gruppenanruf laeuft" auffrischen (stille Einladung) */
        data object Refresh : Route
        /** Anklopf- bzw. stille Leiste statt Klingeln */
        data class Hold(val info: CallInfo, val mode: HoldBar.Mode, val why: String?) : Route
        /** telefoniere hier schon, Anruf ohne waiting: besetzt ablehnen */
        data object DeclineBusy : Route
        /** 1:1 klingeln */
        data class Ring(val info: CallInfo) : Route
        /** Gruppen-Einladung klingeln; [clearGone]: war hier schon vorbei und klingelt erneut */
        data class RingGroup(val info: CallInfo, val clearGone: Boolean) : Route
    }

    /**
     * Entscheidung von CallManager.checkRing. [inGone]/[goneAtMs]: hier schon vorbei/abgelehnt (und wann),
     * [curId]/[ringId]: eigenes Gespraech bzw. Klingeln hier, [inCallHere]: eigenes Gespraech laeuft hier (nicht beendet).
     */
    fun route(
        info: CallInfo, callId: String, inGone: Boolean, goneAtMs: Long?, curId: String?, ringId: String?, inCallHere: Boolean,
        me: String, nowMs: Long,
    ): Route {
        if (curId == callId || ringId == callId) return Route.Ignore
        if (info.kind == CallRoom.KIND_GROUP) {
            // stille Einladung (Nicht stoeren) - nie klingeln; 1:1 ueber Knoten zurueckgehalten -> stille Leiste
            if (info.quiet || info.silent) {
                return if (info.dm && info.quiet && info.invite == "ringing" && info.mine.isEmpty()) Route.Hold(info, HoldBar.Mode.QUIET, "dnd") else Route.Refresh
            }
            // 1:1 ueber Knoten klopft an (ich telefoniere)
            if (info.dm && info.waiting && info.invite == "ringing") {
                return Route.Hold(info.copy(peer = info.legs.firstOrNull()?.person() ?: info.peer), HoldBar.Mode.KNOCK, null)
            }
            // hier schon vorbei/abgelehnt -> nur bei erneutem Klingeln (spaeterer Beginn invite_since)
            if (inGone && !Invites.ringsAgain(info, goneAtMs, nowMs)) return Route.Ignore
            if (info.invite != "ringing" || CallRoom.stopRinging(info.legs, me)) return Route.Ignore
            val left = Invites.ringLeftS(info)
            if (left != null && left <= 0) return Route.Ignore
            val from = info.legs.firstOrNull()?.person() ?: info.peer
            return Route.RingGroup(info.copy(peer = from, state = "ringing", role = "callee", expiresIn = left ?: info.expiresIn), inGone)
        }
        if (info.state != "ringing" || info.role != "callee" || inGone) return Route.Ignore
        return when (RingLogic.incoming(info.waiting, info.quiet, inCallHere, ringId != null)) {
            RingLogic.Incoming.QUIET -> Route.Hold(info, HoldBar.Mode.QUIET, "dnd")
            RingLogic.Incoming.KNOCK, RingLogic.Incoming.KNOCK_ELSEWHERE -> Route.Hold(info, HoldBar.Mode.KNOCK, null)
            RingLogic.Incoming.BUSY -> Route.DeclineBusy
            RingLogic.Incoming.RING -> Route.Ring(info)
        }
    }

    // ------------------------------------------------------------ busyElsewhere

    /**
     * Laeuft ein eigenes Gespraech auf einem anderen Geraet? (GET /api/calls/active, wie static/calls.js busyElsewhere)
     * [skip]: der wartende Anruf selbst und das Gespraech hier.
     * [ended]: das gerade hier beendete Gespraech - der Server kann es noch kurz als aktiv melden (samt diesem Geraet [myDev]
     * in `mine`). Es zaehlt nur, solange ein ANDERES eigenes Geraet darin ist (Bugs-DB 2393: Gruppenanruf, in dem das
     * zweite Geraet weiter spricht; ebenso 1:1 mit Begleitgeraet bzw. nach der Uebergabe) - sonst bot die stille Leiste
     * faelschlich "Annehmen" an.
     */
    fun busyElsewhere(calls: List<CallInfo>, skip: Set<String?>, ended: String? = null, myDev: String = ""): Boolean = calls.any { x ->
        x.id !in skip && x.state == "active" && when {
            ended != null && x.id == ended -> x.mine.any { it != myDev }
            x.kind == CallRoom.KIND_GROUP && !x.dm -> x.mine.isNotEmpty()
            else -> x.role == "caller" || x.role == "callee" || x.mine.isNotEmpty()
        }
    }

    // ------------------------------------------------------------ holdFreed

    /** Gespraech hier zu Ende, Leiste stand im Anruf */
    sealed interface Freed {
        /** keine Leiste aus dem Gespraech hier bzw. "Beenden und annehmen" laeuft gerade */
        data object None : Freed
        /** stille Leiste bleibt still - erneut pruefen, ob woanders ein eigenes Gespraech laeuft (erst dann "Annehmen") */
        data class Recheck(val bar: HoldBar) : Freed
        /** wartender Anruf klingelt jetzt normal mit dem Rest der Klingelzeit */
        data class RingAgain(val info: CallInfo, val leftS: Double) : Freed
        /** Rest zu kurz: Leiste weg, nicht mehr klingeln */
        data object Drop : Freed
    }

    fun freed(h: HoldBar?, taking: Boolean, nowMs: Long): Freed {
        if (h == null || !h.here || taking) return Freed.None
        if (h.mode == HoldBar.Mode.QUIET) return Freed.Recheck(h.copy(here = false, elsewhere = false, checking = true))
        val left = RingLogic.freedLeftS(h.totalS, nowMs - h.at) ?: return Freed.Drop
        return Freed.RingAgain(h.info, left)
    }

    /** Ergebnis der erneuten Pruefung uebernehmen - nur, wenn noch dieselbe Leiste wartet (sonst null = nichts aendern) */
    fun rechecked(now: HoldBar?, callId: String, elsewhere: Boolean): HoldBar? =
        now?.takeIf { it.callId == callId && it.checking }?.copy(checking = false, elsewhere = elsewhere)

    // ------------------------------------------------------------ hangupConfirmed / holdTake

    /**
     * Ende bestaetigt? [post] = POST /api/calls/{id}/end. Kein Aufruf noetig (schon im Beenden, ohne id): ja.
     * 404/410: Anruf ist schon vorbei - ja. Andere Fehler (Netz, 5xx, 403): nein.
     */
    suspend fun endConfirmed(needed: Boolean, post: suspend () -> Unit): Boolean {
        if (!needed) return true
        return try {
            post()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            e.code == 404 || e.code == 410
        } catch (_: Exception) {
            false
        }
    }

    sealed interface Take {
        /** annehmen (kein eigenes Gespraech hier bzw. Ende bestaetigt) */
        data object Accept : Take
        /** Ende nicht sicher: nicht annehmen, Meldung; [leftS] != null -> wartender Anruf klingelt wieder */
        data class Failed(val leftS: Double?) : Take
    }

    /**
     * "Beenden und annehmen": [hangup] = eigenes Gespraech hier mit bestaetigtem Ende auflegen ([inCallHere] false: keins
     * mehr da -> gleich annehmen, ohne aufzulegen). Erst nach bestaetigtem Ende annehmen - sonst liefen zwei Gespraeche.
     */
    suspend fun take(h: HoldBar, inCallHere: Boolean, hangup: suspend () -> Boolean, nowMs: () -> Long): Take {
        if (!inCallHere) return Take.Accept
        if (hangup()) return Take.Accept
        return Take.Failed(RingLogic.freedLeftS(h.totalS, nowMs() - h.at))
    }
}
