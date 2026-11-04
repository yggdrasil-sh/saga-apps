package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.database.DbLive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.11.: eingebettete Datenbanken laden live nach (db.rows / note.changed) */
class LiveDbTest {
    @Test
    fun dbRowsOnlyForThisDatabase() {
        assertTrue(DbLive.rowsChanged("db.rows", null, 1466, 1466))
        assertFalse(DbLive.rowsChanged("db.rows", null, 7, 1466))
        assertFalse(DbLive.rowsChanged("db.rows", 1466, null, 1466))
    }

    @Test
    fun noteChangedOfTheDatabase() {
        assertTrue(DbLive.rowsChanged("note.changed", 1466, null, 1466))
        assertFalse(DbLive.rowsChanged("note.changed", 1479, null, 1466))
        assertFalse(DbLive.rowsChanged("notes.tree", 1466, 1466, 1466))
    }

    // 4.11.: Mitteilung des offenen Chats im Vordergrund nicht zeigen
    @Test
    fun hideNotificationOfOpenChat() {
        val n = io.github.yggdrasil_sh.saga.live.NotifLogic
        assertTrue(n.hideWhileOpen("chat.message", "/chats#c12", 12))
        assertTrue(n.hideWhileOpen("chat.mention", "/chats#12", 12))
        assertFalse(n.hideWhileOpen("chat.message", "/chats#c13", 12))
        assertFalse(n.hideWhileOpen("chat.message", "/chats#c12", null))
        assertFalse(n.hideWhileOpen("call", "/chats#call-ab12", 12))
        assertFalse(n.hideWhileOpen("comment.new", "/notes#12", 12))
    }
}
