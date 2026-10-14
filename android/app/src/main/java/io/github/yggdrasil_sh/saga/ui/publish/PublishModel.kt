package io.github.yggdrasil_sh.saga.ui.publish

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yggdrasil_sh.saga.R
import io.github.yggdrasil_sh.saga.data.NameCheck
import io.github.yggdrasil_sh.saga.data.NameSaved
import io.github.yggdrasil_sh.saga.data.PublicNameInfo
import io.github.yggdrasil_sh.saga.data.Publish
import io.github.yggdrasil_sh.saga.data.PublishState
import io.github.yggdrasil_sh.saga.data.PublishVia
import io.github.yggdrasil_sh.saga.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Schnittstelle fuer "Veroeffentlichen" (SagaRepository; in Tests eine Attrappe) */
interface PublishBackend {
    suspend fun publishState(id: Int): PublishState
    suspend fun publish(id: Int, body: JsonObject): PublishState
    suspend fun unpublish(id: Int): PublishState
    suspend fun publicName(): PublicNameInfo
    suspend fun checkPublicName(name: String): NameCheck
    suspend fun setPublicName(name: String): NameSaved
    fun friendly(e: Throwable): UiText
}

enum class StatusKind { OK, ERROR, INFO }

data class Status(val text: UiText, val kind: StatusKind)

/** Pruefung des oeffentlichen Namens beim Tippen (wie im Web: "✓ Frei" bzw. Meldung des Servers) */
sealed interface NameStatus {
    data object Idle : NameStatus
    data object Checking : NameStatus
    data object Free : NameStatus
    data object Saved : NameStatus
    data class Bad(val text: UiText) : NameStatus
}

/** Was der Dialog zeigt - rein aus Zustand + Eingabe abgeleitet (Unit-Tests) */
data class PublishView(
    val name: String,
    /** Admin hat Veroeffentlichen abgeschaltet */
    val disabledWarning: Boolean,
    /** oeffentlich ueber eine Elternseite (nur solange nicht selbst veroeffentlicht) */
    val via: PublishVia?,
    @param:StringRes val switchLabel: Int,
    val switchOn: Boolean,
    val switchEnabled: Boolean,
    /** beendet (Benutzer) bzw. zurueckgezogen (Admin) - Zeitpunkt in Sekunden */
    val withdrawnAt: Double?,
    val withdrawnByAdmin: Boolean,
    /**
     * Link-Karte direkt unter dem Schalter: veroeffentlicht der oeffentliche Link (aktiv: Kopieren, Oeffnen, Teilen),
     * sonst die kuenftige Adresse gedaempft; null = keine Karte (oeffentlich ueber eine Elternseite)
     */
    val linkUrl: String?,
    val linkActive: Boolean,
    /** Profilseite: zweite Zeile "Profiladresse: …/@name" mit Kopieren */
    val profileAddress: String?,
    /** Adresse und Optionen zeigen (ueber eine Elternseite erst, wenn "Zusätzlich …" an ist) */
    val showSettings: Boolean,
    /** Ueberschrift "Gilt, sobald die Seite öffentlich ist" ueber den Optionen (solange privat) */
    val optionsPending: Boolean,
    /**
     * "Adresse ändern" + Hinweis "Wird erst mit „Adresse ändern“ übernommen." - nur veroeffentlicht und wenn die
     * Eingabe von der gespeicherten Adresse abweicht
     */
    val showChangeAddress: Boolean,
    val changeAddressEnabled: Boolean,
    val showStop: Boolean,
    val profileHint: UiText,
    val home: Boolean,
) {
    companion object {
        fun of(st: PublishState, slugInput: String, base: String): PublishView {
            val on = st.published
            val newSlug = Publish.norm(slugInput)
            val changed = on && newSlug != st.slug
            val via = if (!on) st.publishedVia else null
            val name = st.name.orEmpty()
            return PublishView(
                name = name,
                disabledWarning = !st.enabled,
                via = via,
                switchLabel = if (via != null) R.string.publish_also_own else R.string.publish_public,
                switchOn = on,
                switchEnabled = st.enabled || on,
                withdrawnAt = if (!on) st.withdrawnAt else null,
                withdrawnByAdmin = !on && st.withdrawnAt != null && st.withdrawnBy == "admin",
                linkUrl = when {
                    on -> st.url
                    via != null -> null
                    else -> "$base/@$name/${newSlug.ifEmpty { "…" }}"
                },
                linkActive = on && st.url != null,
                profileAddress = if (on && st.profile) st.profileUrl ?: "$base/@$name" else null,
                showSettings = via == null,
                optionsPending = !on,
                showChangeAddress = changed,
                changeAddressEnabled = changed && newSlug.isNotEmpty(),
                showStop = on,
                profileHint = st.profileOther?.let { UiText.Res(R.string.publish_profile_replaces, it.title) }
                    ?: UiText.Res(R.string.publish_profile_hint),
                home = on && st.home,
            )
        }
    }
}

enum class PublishStep { NAME, MAIN }

/**
 * Dialog "Veroeffentlichen" einer Seite (wie static/pages.js): ohne oeffentlichen Namen erst der Namensschritt,
 * dann Schalter, Adresse und Optionen. Vor dem Veroeffentlichen sind die Optionen nur lokal; danach wird jede
 * Aenderung sofort gespeichert. onChange meldet jeden neuen Zustand (Hinweis "Oeffentlich" im Seitenkopf).
 */
class PublishModel(
    val id: Int,
    private val backend: PublishBackend,
    private val fallbackBase: String,
    private val onChange: (PublishState) -> Unit = {},
) {
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<UiText?>(null)
        private set
    /** Server kennt /publish nicht (aelter) - Menuepunkt ausblenden */
    var unsupported by mutableStateOf(false)
        private set
    var state by mutableStateOf<PublishState?>(null)
        private set
    var step by mutableStateOf(PublishStep.MAIN)
        private set
    var nameInput by mutableStateOf("")
        private set
    var nameStatus by mutableStateOf<NameStatus>(NameStatus.Idle)
        private set
    var slugInput by mutableStateOf("")
        private set
    var children by mutableStateOf(false)
        private set
    var indexable by mutableStateOf(false)
        private set
    var profile by mutableStateOf(false)
        private set
    var status by mutableStateOf<Status?>(null)
    /** Fehler zur Adresse - steht direkt am Feld (rot, Text darunter), nicht in der Statuszeile */
    var slugError by mutableStateOf<UiText?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    val base: String get() = Publish.publicBase(state, fallbackBase)
    val view: PublishView? get() = state?.let { PublishView.of(it, slugInput, base) }
    val newSlug: String get() = Publish.norm(slugInput)
    val canContinue: Boolean get() = nameStatus == NameStatus.Free && !busy
    val namePreview: String get() = "$base/@${Publish.norm(nameInput).ifEmpty { "…" }}"

    suspend fun load() {
        loading = true
        loadError = null
        try {
            show(backend.publishState(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (Publish.unsupported(e)) unsupported = true
            loadError = backend.friendly(e)
        } finally {
            loading = false
        }
    }

    private fun show(st: PublishState) {
        apply(st)
        if (st.name.isNullOrEmpty()) toNameStep(st.suggestedName, null) else step = PublishStep.MAIN
    }

    private fun apply(st: PublishState) {
        state = st
        slugInput = st.slug
        children = st.children
        indexable = st.indexable
        profile = st.profile
        onChange(st)
    }

    private fun toNameStep(suggested: String?, error: UiText?) {
        step = PublishStep.NAME
        if (suggested != null || nameInput.isEmpty()) nameInput = suggested.orEmpty()
        nameStatus = error?.let { NameStatus.Bad(it) } ?: if (Publish.norm(nameInput).isEmpty()) NameStatus.Idle else NameStatus.Checking
    }

    // ---------------------------------------------------------------- Namensschritt

    fun editName(v: String) {
        nameInput = v
        nameStatus = if (Publish.norm(v).isEmpty()) NameStatus.Idle else NameStatus.Checking
    }

    /** Pruefen (gueltig + frei), ohne zu speichern - die Oberflaeche ruft das 250 ms nach dem letzten Tippen auf */
    suspend fun checkName() {
        val v = Publish.norm(nameInput)
        nameStatus = checkedName(backend, v, current = { Publish.norm(nameInput) }) ?: return
    }

    suspend fun confirmName() {
        val v = Publish.norm(nameInput)
        if (v.isEmpty() || busy) return
        busy = true
        try {
            backend.setPublicName(v)
            val st = backend.publishState(id)
            apply(st)
            step = PublishStep.MAIN
            status = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            nameStatus = NameStatus.Bad(backend.friendly(e))
        } finally {
            busy = false
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    fun editSlug(v: String) {
        slugInput = v
        slugError = null
        status = null
    }

    /** Schalter "Oeffentlich": an = mit Adresse und Optionen veroeffentlichen; aus = beenden (Rueckfrage vorher) */
    suspend fun setPublic(on: Boolean) {
        if (on) {
            put(buildJsonObject {
                put("slug", newSlug)
                put("children", children)
                put("indexable", indexable)
                put("profile", profile)
            }, UiText.Res(R.string.publish_saved))
        } else {
            stop()
        }
    }

    /** "children" | "indexable" | "profile": vor dem Veroeffentlichen nur merken, danach sofort speichern */
    suspend fun setOption(key: String, value: Boolean) {
        when (key) {
            "children" -> children = value
            "indexable" -> indexable = value
            "profile" -> profile = value
            else -> return
        }
        if (state?.published == true) put(buildJsonObject { put(key, value) }, UiText.Res(R.string.publish_saved))
    }

    suspend fun changeSlug() {
        val st = state ?: return
        if (!st.published || newSlug == st.slug || newSlug.isEmpty()) return
        put(buildJsonObject { put("slug", newSlug) }, UiText.Res(R.string.publish_address_saved))
    }

    suspend fun stop() {
        if (busy) return
        busy = true
        try {
            apply(backend.unpublish(id))
            // kein zweites "beendet": der Hinweis "Veröffentlichung beendet am …" steht schon im Dialog
            status = null
            slugError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = Status(backend.friendly(e), StatusKind.ERROR)
        } finally {
            busy = false
        }
    }

    fun linkCopied() {
        status = Status(UiText.Res(R.string.publish_link_copied), StatusKind.OK)
    }

    private suspend fun put(body: JsonObject, done: UiText) {
        val st = state ?: return
        if (busy) return
        if (!st.enabled) {
            status = Status(UiText.Res(R.string.publish_disabled), StatusKind.ERROR)
            return
        }
        busy = true
        try {
            val ns = backend.publish(id, body)
            apply(ns)
            slugError = null
            status = Status(ns.replacedProfile?.let { UiText.Res(R.string.publish_saved_replaced, it.title) } ?: done, StatusKind.OK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val need = Publish.needName(e)
            when {
                need != null -> toNameStep(need.suggested, backend.friendly(e))
                Publish.slugError(e, sentSlug = body.containsKey("slug")) -> {
                    slugError = backend.friendly(e)
                    status = null
                }
                else -> status = Status(backend.friendly(e), StatusKind.ERROR)
            }
            // Optionen auf den Stand des Servers zuruecksetzen (Schalter zeigen nichts Ungespeichertes)
            if (st.published) {
                children = st.children
                indexable = st.indexable
                profile = st.profile
            }
        } finally {
            busy = false
        }
    }
}

/**
 * Gemeinsame Namenspruefung (Dialog und Einstellungen). current = Eingabe jetzt; hat sie sich waehrend der
 * Anfrage geaendert, gilt das Ergebnis nicht mehr (null).
 */
internal suspend fun checkedName(backend: PublishBackend, v: String, current: () -> String): NameStatus? {
    if (v.isEmpty()) return NameStatus.Idle
    val r = try {
        backend.checkPublicName(v)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return if (current() == v) NameStatus.Bad(backend.friendly(e)) else null
    }
    if (current() != v) return null
    return if (r.ok) NameStatus.Free else NameStatus.Bad(UiText.Raw(r.error.orEmpty()))
}

/** Einstellungen -> Konto: Block "Oeffentlicher Name" (eigener Knopf, Pruefung beim Tippen, Rueckfrage beim Aendern) */
class PublicNameModel(private val backend: PublishBackend) {
    var info by mutableStateOf<PublicNameInfo?>(null)
        private set
    /** Server ohne /api/account/public-name: Block ausblenden */
    var hidden by mutableStateOf(false)
        private set
    var input by mutableStateOf("")
        private set
    var status by mutableStateOf<NameStatus>(NameStatus.Idle)
        private set
    var busy by mutableStateOf(false)
        private set

    val canSave: Boolean get() = status == NameStatus.Free && !busy
    /** Name schon gesetzt, wird geaendert und es gibt oeffentliche Seiten -> alte Links gehen nicht mehr */
    val needsConfirm: Boolean
        get() = info?.name?.let { it.isNotEmpty() && Publish.norm(input) != it } == true && (info?.published ?: 0) > 0

    /**
     * Live beim Tippen "Neue Adresse: …/@neu (bisher …/@alt)": (neu, bisher) - nur wenn schon ein Name gesetzt ist
     * und die Eingabe davon abweicht (nicht bei ungueltigem/vergebenem Namen - dann steht der Fehler am Feld)
     */
    val newAddress: Pair<String, String>?
        get() {
            val p = info ?: return null
            val old = p.name?.takeIf { it.isNotEmpty() } ?: return null
            val oldUrl = p.url ?: return null
            val n = Publish.norm(input)
            val cut = oldUrl.lastIndexOf("/@")
            if (n.isEmpty() || n == old || cut < 0 || status is NameStatus.Bad) return null
            return "${oldUrl.substring(0, cut)}/@$n" to oldUrl
        }

    suspend fun load() {
        try {
            val p = backend.publicName()
            info = p
            input = p.name.orEmpty()
            hidden = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            hidden = true
        }
    }

    fun edit(v: String) {
        input = v
        val n = Publish.norm(v)
        status = if (n.isEmpty() || n == info?.name) NameStatus.Idle else NameStatus.Checking
    }

    suspend fun check() {
        val v = Publish.norm(input)
        if (v == info?.name) {
            status = NameStatus.Idle
            return
        }
        status = checkedName(backend, v, current = { Publish.norm(input) }) ?: return
    }

    suspend fun save() {
        val v = Publish.norm(input)
        if (v.isEmpty() || busy) return
        busy = true
        try {
            val r = backend.setPublicName(v)
            info = (info ?: PublicNameInfo()).copy(name = r.name, url = r.url)
            input = r.name
            status = NameStatus.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = NameStatus.Bad(backend.friendly(e))
        } finally {
            busy = false
        }
    }
}
