package io.github.yggdrasil_sh.saga.ui.settings

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import io.github.yggdrasil_sh.saga.chat.ContactExportLogic

// Saga 2.6 (Bugs-DB 2027b, W2): System-Dialog "Kontakt hinzufuegen/ergaenzen" vorausgefuellt oeffnen. Saga braucht dafuer
// keine Kontakte-Berechtigung: die Kontakte-App zeigt Auswahl (neu oder vorhanden) und Editor, gesichert wird nur dort.
// Was uebertragen wird: ContactExportLogic.prefill.

object ContactExportUi {
    /** ACTION_INSERT_OR_EDIT: Liste mit "Neuer Kontakt" und vorhandenen Kontakten (insertOrEdit=false: gleich neu) */
    fun intent(p: ContactExportLogic.Prefill, insertOrEdit: Boolean): Intent {
        val i = if (insertOrEdit) {
            Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
        } else {
            Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        }
        p.name?.let { i.putExtra(ContactsContract.Intents.Insert.NAME, it) }
        val data = ArrayList<ContentValues>()
        for (r in p.rows) {
            data += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, r.mime)
                if (r.mime == ContactExportLogic.PHONE_MIME) {
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, r.value)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, r.type)
                } else {
                    put(ContactsContract.CommonDataKinds.Email.ADDRESS, r.value)
                    put(ContactsContract.CommonDataKinds.Email.TYPE, r.type)
                }
            }
        }
        i.putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, data)
        i.putExtra(FINISH_ON_SAVE, true)                                   // nach dem Sichern zurueck zu Saga
        return i
    }

    /** Oeffnen; ohne Kontakte-App false (Hinweis zeigt der Aufrufer) */
    fun open(context: Context, p: ContactExportLogic.Prefill): Boolean {
        for (edit in listOf(true, false)) {
            try {
                context.startActivity(intent(p, edit).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    /** Extra der Kontakte-App (seit 4.0.3): nach "Sichern" die aufrufende App wieder zeigen */
    const val FINISH_ON_SAVE = "finishActivityOnSaveCompleted"
}
