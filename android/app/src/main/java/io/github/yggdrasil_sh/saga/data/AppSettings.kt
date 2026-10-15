package io.github.yggdrasil_sh.saga.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

private val Context.sagaDataStore by preferencesDataStore(name = "settings")

/** Einstellungen, die Anmeldung und Kopplung brauchen (fuer Tests austauschbar) */
interface ServerSettings {
    val server: StateFlow<String>
    /** Name des angemeldeten Kontos (vom Server bei Anmeldung/Erneuerung gemeldet) */
    val account: StateFlow<String>
    /** Schluessel des Kontos fuer Entwuerfe (AccountKey: stabile Kennung vom Server oder eingefrorener Name) */
    val accountKey: StateFlow<String>
    suspend fun setServer(value: String)
    suspend fun setAccount(value: String)
    suspend fun setAccountKey(value: String)
    suspend fun clearRecent()
}

/**
 * Einfache Einstellungen in DataStore: Server-Adresse, Kontoname und zuletzt besuchte Seiten.
 * load() liest sie einmal beim Start (im Hintergrund); danach als StateFlow sofort verfuegbar.
 */
class AppSettings(context: Context) : ServerSettings {
    private val ds = context.sagaDataStore
    private val keyServer = stringPreferencesKey("server")
    private val keyAccount = stringPreferencesKey("account")
    private val keyRecent = stringPreferencesKey("recent")
    private val keyAccountKey = stringPreferencesKey("account_key")

    private val _server = MutableStateFlow("")
    override val server: StateFlow<String> = _server
    private val _account = MutableStateFlow("")
    override val account: StateFlow<String> = _account
    private val _accountKey = MutableStateFlow("")
    override val accountKey: StateFlow<String> = _accountKey
    private val _recent = MutableStateFlow<List<Long>>(emptyList())
    val recent: StateFlow<List<Long>> = _recent

    /** Einmal beim Start (nicht auf dem Haupt-Thread aufrufen) */
    suspend fun load() {
        val p: Preferences = ds.data.first()
        _server.value = p[keyServer] ?: ""
        _account.value = p[keyAccount] ?: ""
        _accountKey.value = p[keyAccountKey] ?: ""
        _recent.value = parseIds(p[keyRecent])
    }

    override suspend fun setServer(value: String) {
        _server.value = value
        ds.edit { it[keyServer] = value }
    }

    override suspend fun setAccount(value: String) {
        if (_account.value == value) return
        _account.value = value
        ds.edit { it[keyAccount] = value }
    }

    override suspend fun setAccountKey(value: String) {
        if (_accountKey.value == value) return
        _accountKey.value = value
        ds.edit { it[keyAccountKey] = value }
    }

    suspend fun visited(id: Long) {
        val l = (listOf(id) + _recent.value.filter { it != id }).take(8)
        _recent.value = l
        ds.edit { it[keyRecent] = l.joinToString(",") }
    }

    override suspend fun clearRecent() {
        _recent.value = emptyList()
        ds.edit { it.remove(keyRecent) }
    }

    private fun parseIds(s: String?): List<Long> = s?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
}
