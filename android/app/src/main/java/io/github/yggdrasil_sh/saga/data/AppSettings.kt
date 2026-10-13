package io.github.yggdrasil_sh.saga.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.sagaDataStore by preferencesDataStore(name = "settings")

/**
 * Einfache Einstellungen in DataStore: Server-Adresse und zuletzt besuchte Seiten.
 * Die Werte werden beim Start einmal gelesen und als StateFlow gespiegelt (sofort verfuegbar).
 */
class AppSettings(context: Context) {
    private val ds = context.sagaDataStore
    private val keyServer = stringPreferencesKey("server")
    private val keyRecent = stringPreferencesKey("recent")

    private val initial = runBlocking { ds.data.first() }
    private val _server = MutableStateFlow(initial[keyServer] ?: "")
    val server: StateFlow<String> = _server
    private val _recent = MutableStateFlow(parseIds(initial[keyRecent]))
    val recent: StateFlow<List<Int>> = _recent

    suspend fun setServer(value: String) {
        _server.value = value
        ds.edit { it[keyServer] = value }
    }

    suspend fun visited(id: Int) {
        val l = (listOf(id) + _recent.value.filter { it != id }).take(8)
        _recent.value = l
        ds.edit { it[keyRecent] = l.joinToString(",") }
    }

    suspend fun clearRecent() {
        _recent.value = emptyList()
        ds.edit { it.remove(keyRecent) }
    }

    private fun parseIds(s: String?): List<Int> = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
}
