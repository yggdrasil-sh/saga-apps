package io.github.yggdrasil_sh.saga.data

import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.util.UiText
import io.github.yggdrasil_sh.saga.util.UiTextException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/*
 * Design-Runde Android 1 (NAV-06): Fehler beim Pruefen der Server-Adresse unterscheidbar machen - kein Netz, Adresse
 * nicht gefunden (DNS), Server antwortet nicht, kein Saga-Server, sonst der bisherige Text (Zertifikat, https, ...).
 * probe() haengt die Ursache an seine UiTextException (initCause), daher wird die ganze Kette betrachtet.
 */
object LoginErrors {
    enum class Kind { OFFLINE, NOT_FOUND, NO_ANSWER, NOT_SAGA, OTHER }

    fun classify(e: Throwable, online: Boolean): Kind {
        val chain = generateSequence(e) { it.cause }.take(8).toList()
        if (chain.any { it is UiTextException && it.text == UiText.Res(R.string.login_not_saga) }) return Kind.NOT_SAGA
        if (chain.any { it is UnknownHostException }) return if (online) Kind.NOT_FOUND else Kind.OFFLINE
        if (chain.any { it is SocketTimeoutException || it is ConnectException || it is NoRouteToHostException || it is ServerUnreachableException }) {
            return if (online) Kind.NO_ANSWER else Kind.OFFLINE
        }
        if (!online && chain.any { it is IOException }) return Kind.OFFLINE
        return Kind.OTHER
    }

    /** Text am Feld; OTHER = der bisherige Text ([fallback]) */
    fun text(kind: Kind, fallback: UiText): UiText = when (kind) {
        Kind.OFFLINE -> UiText.Res(R.string.r1_login_err_offline)
        Kind.NOT_FOUND -> UiText.Res(R.string.r1_login_err_not_found)
        Kind.NO_ANSWER -> UiText.Res(R.string.r1_login_err_no_answer)
        Kind.NOT_SAGA -> UiText.Res(R.string.login_not_saga)
        Kind.OTHER -> fallback
    }
}
