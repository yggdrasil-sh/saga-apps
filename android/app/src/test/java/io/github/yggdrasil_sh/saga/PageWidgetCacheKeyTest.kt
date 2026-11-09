package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.ui.page.PageWidgetCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Zwischenspeicher der Seiten-Widget-Werte: nie zwischen Servern oder Konten teilen */
class PageWidgetCacheKeyTest {
    @Test
    fun keyPerServerAccountAndPage() {
        val a = PageWidgetCache.key("https://a.example", "uid:1", "anna", 5)
        assertEquals("https://a.example|uid:1|5", a)
        assertNotEquals(a, PageWidgetCache.key("https://b.example", "uid:1", "anna", 5))
        assertNotEquals(a, PageWidgetCache.key("https://a.example", "uid:2", "anna", 5))   // gleicher Name, anderes Konto
        assertNotEquals(a, PageWidgetCache.key("https://a.example", "uid:1", "anna", -5))
    }

    @Test
    fun noKeyWithoutServerOrAccount() {
        assertNull(PageWidgetCache.key("", "uid:1", "anna", 5))
        assertNull(PageWidgetCache.key("https://a.example", "", "", 5))
        assertEquals("https://a.example|anna|5", PageWidgetCache.key("https://a.example", "", "anna", 5))
    }
}
