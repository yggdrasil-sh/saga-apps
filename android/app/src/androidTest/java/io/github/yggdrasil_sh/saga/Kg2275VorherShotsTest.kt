package io.github.yggdrasil_sh.saga

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * 2.6 K-G: dieselben Ansichten ohne Pruefungen - auf dem Stand vor K-G (Vorher-Bilder) und danach lauffaehig. Nur APIs, die
 * es vor K-G schon gab. Bilder: kg-<ansicht>-<stand>.png (stand per -e stand vorher|nachher, Standard vorher).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Kg2275VorherShotsTest : Kg2275Base() {
    private val stand get() = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("stand") ?: "vorher"

    @Test fun a_teilenAuswahl() {
        launchMain(shareIntent(sharedText, "Vereinsnachrichten Juli")).use {
            assertNotNull("Teilen-Dialog", text("Als Aufgabe", 15000))
            snap("teilen-auswahl-$stand")
        }
    }

    @Test fun b_planKarteUndDetail() {
        detailShot(5, "plan-detail-$stand")
        detailShot(6, "detail-bitte-$stand")
    }

    @Test fun c_widgetKlein() {
        widgetSnapshot()
        widgetShot("widget-klein-$stand", dark = false)
    }

    @Test fun d_ortSaga() {
        launchMain(mainIntent()).use {
            val tab = find(15000) { it.text?.toString() == "Saga" || it.contentDescription?.toString() == "Saga" }
            assertNotNull("Leiste: Saga", tab)
            click(tab!!)
            val chip = text("Als Auftrag", 15000)
            assertNotNull("Ort Saga", chip)
            click(chip!!)                                                    // "Als Auftrag" an (wie nach dem Teilen)
            snap("saga-$stand")
        }
    }
}
