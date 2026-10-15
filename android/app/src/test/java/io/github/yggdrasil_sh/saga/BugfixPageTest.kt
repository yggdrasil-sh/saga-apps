package io.github.yggdrasil_sh.saga

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import io.github.yggdrasil_sh.saga.ui.page.COPY_DRAFT_MAX
import io.github.yggdrasil_sh.saga.ui.page.CopyDraftSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BUG-065: eigener Text nach "Neue Version laden" wird fuer Prozess-Tod gesichert (nicht ueber der Bundle-Grenze) */
class BugfixPageTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test
    fun copyDraftIsSaved() {
        with(CopyDraftSaver) {
            assertEquals("OFF1", scope.save(mutableStateOf("OFF1")))
            assertNull(scope.save(mutableStateOf(null)))
            assertNull(scope.save(mutableStateOf("x".repeat(COPY_DRAFT_MAX + 1))))
            assertEquals("OFF1", restore("OFF1")?.value)
        }
    }
}
