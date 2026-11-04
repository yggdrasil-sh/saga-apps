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
}
