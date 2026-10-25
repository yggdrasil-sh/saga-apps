package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.ServiceItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kachel-Symbole (Server 2.5): icon_url bevorzugt, sonst der selfh.st-Name wie bisher, sonst Anfangsbuchstabe */
class ServiceIconTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun iconUrlWins() {
        val s = json.decodeFromString<ServiceItem>("""{"name":"YT","icon":"","key":"bm3","icon_url":"/api/services/favicon/bm3?h=ab12"}""")
        assertEquals("api/services/favicon/bm3?h=ab12", s.iconPath())
    }

    @Test
    fun olderServerUsesName() {
        val s = json.decodeFromString<ServiceItem>("""{"name":"NC","icon":"nextcloud","key":"s_nc"}""")
        assertEquals("api/services/icon/nextcloud", s.iconPath())
    }

    @Test
    fun nothingOrForeign() {
        assertNull(ServiceItem(name = "X").iconPath())
        assertNull(ServiceItem(name = "X", iconUrl = "https://evil.example/x.png").iconPath())
        assertNull(ServiceItem(name = "X", icon = "../x").iconPath())
    }
}
