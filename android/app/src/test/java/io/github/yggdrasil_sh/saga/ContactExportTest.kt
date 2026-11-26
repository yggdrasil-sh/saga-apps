package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.BookAddr
import io.github.yggdrasil_sh.saga.chat.BookContact
import io.github.yggdrasil_sh.saga.chat.ContactBookLogic
import io.github.yggdrasil_sh.saga.chat.ContactExportLogic
import io.github.yggdrasil_sh.saga.chat.ContactExportLogic.Row
import io.github.yggdrasil_sh.saga.ui.settings.ContactExportUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Saga 2.6 (Bugs-DB 2027b, W2): Vorbefuellung "Zu Kontakten hinzufuegen" (ContactExportLogic). Beispielwerte:
// Wegwerf-Namen, Nummern aus Beispielbereichen, example.org.
class ContactExportTest {
    private val ph = ContactExportLogic.PHONE_MIME
    private val ml = ContactExportLogic.MAIL_MIME
    private fun c(s: String): BookContact = ContactBookLogic.parse(s)!!

    @Test
    fun mimeTypesAndTypeNumbersMatchContactsContract() {
        assertEquals("vnd.android.cursor.item/phone_v2", ph)
        assertEquals("vnd.android.cursor.item/email_v2", ml)
        // Phone.TYPE_HOME 1, MOBILE 2, WORK 3, OTHER 7 · Email.TYPE_HOME 1, WORK 2, OTHER 3, MOBILE 4
        assertEquals(listOf(2, 1, 3, 7, 7, 7), listOf("mobile", "home", "work", "other", null, "pager").map(ContactExportLogic::phoneType))
        assertEquals(listOf(4, 1, 2, 3, 3, 3), listOf("mobile", "home", "work", "other", null, "fax").map(ContactExportLogic::mailType))
        assertEquals("finishActivityOnSaveCompleted", ContactExportUi.FINISH_ON_SAVE)
    }

    @Test
    fun chatContactWithoutSagaAddress() {
        val bert = c(
            """{"id":7,"ver":2,"origin":"chat","name":"Bert Berg","own_name":null,
            "saga":{"ref":"~k3abc","remote":false,"sub":"","ready":true},"form":"informal","salutation":"Lieber Bert",
            "addrs":[{"id":1,"kind":"saga","value":"~k3abc","fixed":true},
            {"id":2,"kind":"e164","value":"+491701234567","type":"mobile"},{"id":3,"kind":"e164","value":"+493012345678","type":"work"},
            {"id":4,"kind":"email","value":"bert@example.org","type":"home"},{"id":5,"kind":"email","value":"b.berg@example.org","type":"work"}]}""",
        )
        val p = ContactExportLogic.prefill(bert)!!
        assertEquals("Bert Berg", p.name)
        assertEquals(listOf(Row(ph, "+491701234567", 2), Row(ph, "+493012345678", 3)), p.phones)
        assertEquals(listOf(Row(ml, "bert@example.org", 1), Row(ml, "b.berg@example.org", 2)), p.mails)
        assertEquals(p.phones + p.mails, p.rows)
        val all = (listOf(p.name.orEmpty()) + p.rows.map { it.value }).joinToString("|")
        assertFalse("nie Saga-Adresse/Anrede/Grussform: $all", all.contains("~k3abc") || all.contains("Lieber Bert") || all.contains("informal"))
        assertTrue(ContactExportLogic.canExport(bert))
    }

    @Test
    fun remoteContactOwnNameFirstNoHost() {
        val oma = c(
            """{"id":8,"ver":1,"origin":"chat","name":"Oma","own_name":"Oma",
            "saga":{"ref":"~p9@saga.example.net","remote":true,"host":"saga.example.net"},
            "addrs":[{"kind":"fed","value":"~p9@saga.example.net","fixed":true},{"kind":"e164","value":"+436641234567","type":"home"}]}""",
        )
        val p = ContactExportLogic.prefill(oma)!!
        assertEquals("Oma", p.name)
        assertEquals(listOf(Row(ph, "+436641234567", 1)), p.rows)
        val own = c("""{"id":9,"ver":1,"origin":"chat","name":"Anna Berg","own_name":"Anni","addrs":[{"kind":"email","value":"a@example.org"}]}""")
        assertEquals("Anni", ContactExportLogic.prefill(own)!!.name)
    }

    @Test
    fun noButtonWithoutNumberOrMail() {
        val bare = c("""{"id":9,"ver":1,"origin":"chat","name":"Cleo","saga":{"ref":"~k3x"},"addrs":[{"kind":"saga","value":"~k3x","fixed":true}]}""")
        assertNull(ContactExportLogic.prefill(bare))
        assertFalse(ContactExportLogic.canExport(bare))
        val broken = c("""{"id":15,"ver":1,"origin":"manual","name":"Kaputt","own_name":"Kaputt","addrs":[{"kind":"e164","value":"12345"},{"kind":"email","value":"kein-at"}]}""")
        assertNull(ContactExportLogic.prefill(broken))
        val fixed = BookContact(id = 17, ver = 1, name = "F", addrs = listOf(BookAddr(kind = "e164", value = "+491701234567", fixed = true)))
        assertNull("feste Werte nie", ContactExportLogic.prefill(fixed))
    }

    @Test
    fun fallbackNameIsNoName() {
        val n = c("""{"id":10,"ver":1,"origin":"manual","name":"+491701234567","own_name":null,"addrs":[{"kind":"e164","value":"+491701234567","type":null}]}""")
        val p = ContactExportLogic.prefill(n)!!
        assertNull(p.name)
        assertEquals(listOf(Row(ph, "+491701234567", 7)), p.rows)
        assertNull(ContactExportLogic.prefill(c("""{"id":11,"ver":1,"origin":"manual","name":"x@example.org","addrs":[{"kind":"email","value":"x@example.org"}]}"""))!!.name)
        assertNull(ContactExportLogic.prefill(c("""{"id":11,"ver":1,"origin":"manual","addrs":[{"kind":"email","value":"x@example.org"}]}"""))!!.name)
        assertNull(ContactExportLogic.prefill(c("""{"id":12,"ver":1,"origin":"manual","name":"–","addrs":[{"kind":"email","value":"y@example.org"}]}"""))!!.name)
        assertNull("Saga-Handle", ContactExportLogic.prefill(c("""{"id":13,"ver":1,"origin":"chat","name":"~k3abc","addrs":[{"kind":"e164","value":"+491701234567"}]}"""))!!.name)
        assertNull("Leerraum", ContactExportLogic.prefill(c("""{"id":14,"ver":1,"origin":"chat","name":"  ","own_name":" ","addrs":[{"kind":"e164","value":"+491701234567"}]}"""))!!.name)
    }

    @Test
    fun specialCharactersInvalidAndDuplicateValues() {
        val odd = c(
            """{"id":14,"ver":1,"origin":"manual","name":"Ågot Øvre-Ström","own_name":"  Ågot \u0007Øvre-Ström \n",
            "addrs":[{"kind":"e164","value":"+4917012345"},{"kind":"e164","value":"+4917012345","type":"work"},
            {"kind":"e164","value":"0170 1234567"},{"kind":"e164","value":"+0123456789"},{"kind":"e164","value":"tel:+491701234567"},
            {"kind":"e164","value":"  "},{"kind":"email","value":"a,b@example.org"},{"kind":"email","value":"a b@example.org"},
            {"kind":"email","value":"a@b@example.org"},{"kind":"email","value":"​z@example.org"},
            {"kind":"email","value":"ÄGOT+tag@Example.org"},{"kind":"email","value":"ägot+tag@example.org"},
            {"kind":"email","value":"Q@EXAMPLE.ORG"},{"kind":"email","value":"q@example.org"},{"kind":"fax","value":"+491701234567"}]}""",
        )
        val p = ContactExportLogic.prefill(odd)!!
        assertEquals("Ågot Øvre-Ström", p.name)
        assertEquals(listOf(Row(ph, "+4917012345", 7)), p.phones)
        assertEquals(listOf("ÄGOT+tag@Example.org", "Q@EXAMPLE.ORG"), p.mails.map { it.value })
        assertEquals("Thai bleibt", "สมชาย ใจดี", ContactExportLogic.clean(" สมชาย\tใจดี "))
        assertEquals(120, ContactExportLogic.clean("x".repeat(300)).length)
        assertTrue(ContactExportLogic.validMail("o'brien+x@example.org"))
        assertFalse(ContactExportLogic.validMail("a@" + "b".repeat(260) + ".org"))
    }

    @Test
    fun atMostTenPerKind() {
        val many = BookContact(
            id = 16, ver = 1, name = "Viele", ownName = "Viele",
            addrs = (0 until 12).map { BookAddr(kind = "e164", value = "+4917012345%02d".format(it), type = "mobile") } +
                (0 until 12).map { BookAddr(kind = "email", value = "m$it@example.org", type = "work") },
        )
        val p = ContactExportLogic.prefill(many)!!
        assertEquals(10, p.phones.size)
        assertEquals(10, p.mails.size)
    }

    @Test
    fun noContactsPermissionInManifest() {
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).firstOrNull { it.exists() }
        assertNotNull("Manifest gefunden", m)
        val t = m!!.readText()
        assertFalse("keine Kontakte-Berechtigung", t.contains("READ_CONTACTS") || t.contains("WRITE_CONTACTS"))
    }
}
