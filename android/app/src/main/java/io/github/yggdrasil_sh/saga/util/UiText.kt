package io.github.yggdrasil_sh.saga.util

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/**
 * Text fuer die Oberflaeche, der ausserhalb von Compose entsteht (Repository, Editor-Zustand):
 * Ressource + Argumente statt fertigem String, damit er erst beim Anzeigen in der aktuellen Sprache
 * aufgeloest wird. Argumente duerfen selbst UiText sein (z. B. "Nicht gespeichert: <Fehler>").
 * Raw = Text, der nicht uebersetzt wird (Meldung des Servers, Name, ...).
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any>) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    data class Raw(val text: String) : UiText

    fun asString(res: Resources): String = when (this) {
        is Raw -> text
        is Res -> if (args.isEmpty()) res.getString(id)
        else res.getString(id, *args.map { if (it is UiText) it.asString(res) else it }.toTypedArray())
    }

    fun asString(context: Context): String = asString(context.resources)
}

/** In Compose: neu aufloesen, wenn sich die Sprache aendert */
@Composable
fun UiText.resolve(): String = asString(LocalResources.current)

/** Fehler mit fertigem, uebersetzbarem Text (z. B. Eingabe pruefen im Repository) */
class UiTextException(val text: UiText) : Exception(text.toString())
