package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.blocks.RenderPreview
import io.github.yggdrasil_sh.saga.data.Gallery
import io.github.yggdrasil_sh.saga.data.GeoPlace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folgearbeiten aus dem Labor 2.11.: Galerie fragt nach dem Wetter-Ort (ask_weather), Mathe ohne Vorschaubild */
class LabFollowupTest {
    @Test fun previewPathAndAsk() {
        assertEquals("api/gallery/home-dashboard?lang=de", Gallery.previewPath("home-dashboard", "de-DE"))
        assertEquals("api/gallery/a%2Fb", Gallery.previewPath("a/b", ""))
        assertTrue(Gallery.parseAskWeather("""{"id":"x","ask_weather":true,"preview":{}}"""))
        assertFalse(Gallery.parseAskWeather("""{"id":"x","ask_weather":false}"""))
        assertFalse(Gallery.parseAskWeather("""{"id":"x"}"""))                 // aelterer Server
        assertFalse(Gallery.parseAskWeather("""{"ask_weather":"true"}"""))
        assertFalse(Gallery.parseAskWeather("kaputt"))
    }

    @Test fun geocode() {
        assertNull(Gallery.geocodePath("H"))
        assertNull(Gallery.geocodePath("  "))
        assertNull(Gallery.geocodePath("x".repeat(81)))
        assertEquals("api/widgets/geocode?q=Bad%20T%C3%B6lz", Gallery.geocodePath(" Bad Tölz "))
        val r = Gallery.parseGeocode("""{"results":[
            {"name":"Hamburg","admin":"Hamburg","country":"Deutschland","lat":53.55,"lon":9.99},
            {"name":"Hamburg","admin":"New York","country":"USA","lat":42.72,"lon":-78.83},
            {"name":"","lat":1,"lon":2},
            {"name":"Kaputt","lat":"53","lon":9},
            {"name":"Weit","lat":95,"lon":9}
        ],"error":null}""")
        assertEquals(2, r.size)
        assertEquals("Hamburg (Deutschland)", r[0].label)                       // admin = name: nicht doppelt
        assertEquals("Hamburg, New York (USA)", r[1].label)
        assertEquals(emptyList<GeoPlace>(), Gallery.parseGeocode("""{"error":"disabled"}"""))
        assertEquals(emptyList<GeoPlace>(), Gallery.parseGeocode("kaputt"))
        val many = (1..12).joinToString(",") { """{"name":"O$it","lat":1,"lon":2}""" }
        assertEquals(8, Gallery.parseGeocode("""{"results":[$many]}""").size)
    }

    @Test fun useBodyWithPlace() {
        val b = Gallery.useBody(null, true, GeoPlace("Hamburg", "Hamburg", "Deutschland", 53.55, 9.99))
        val w = b["weather_place"]!!.jsonObject
        assertEquals("Hamburg", w["place"]!!.jsonPrimitive.content)
        assertEquals(53.55, w["lat"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(9.99, w["lon"]!!.jsonPrimitive.double, 1e-9)
        assertNull(Gallery.useBody(null, true)["weather_place"])               // Ueberspringen: wie bisher
    }

    private fun doc(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    @Test fun missingRender() {
        assertTrue(RenderPreview.missingRender(doc("""{"blocks":[{"type":"math","data":{"tex":"\\frac{a}{b}"}}]}""")))
        assertFalse(RenderPreview.missingRender(doc("""{"blocks":[{"type":"math","data":{"tex":"x","render":{"png":"/files/abc","h":"1"}}}]}""")))
        assertFalse(RenderPreview.missingRender(doc("""{"blocks":[{"type":"math","data":{"tex":"  "}}]}""")))
        assertTrue(RenderPreview.missingRender(doc("""{"blocks":[{"type":"code","data":{"code":"graph TD; A-->B","language":"mermaid"}}]}""")))
        assertFalse(RenderPreview.missingRender(doc("""{"blocks":[{"type":"code","data":{"code":"x = 1","language":"python"}}]}""")))
        // in Spalten und Toggles
        assertTrue(RenderPreview.missingRender(doc("""{"blocks":[{"type":"columns","data":{"cols":[{"blocks":[]},{"blocks":[{"type":"math","data":{"tex":"y"}}]}]}}]}""")))
        assertTrue(RenderPreview.missingRender(doc("""{"blocks":[{"type":"toggle","data":{"blocks":[{"type":"math","data":{"tex":"y"}}]}}]}""")))
        assertFalse(RenderPreview.missingRender(doc("""{"blocks":[{"type":"paragraph","data":{"text":"a"}}]}""")))
        assertFalse(RenderPreview.missingRender(doc("""{}""")))
    }
}
