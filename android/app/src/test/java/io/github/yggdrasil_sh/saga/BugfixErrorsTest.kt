package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.data.SagaRepository
import io.github.yggdrasil_sh.saga.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** BUG-071: Netzfehler nie als roher englischer Ausnahmetext mit interner Adresse */
class BugfixErrorsTest {
    @Test
    fun ioExceptionIsTranslated() {
        assertEquals(UiText.Res(R.string.error_network), SagaRepository.friendlyText(IOException("unexpected end of stream on http://localhost:18151/...")))
        assertEquals(UiText.Res(R.string.error_network), SagaRepository.friendlyText(IOException()))
        assertEquals(UiText.Res(R.string.error_timeout), SagaRepository.friendlyText(SocketTimeoutException("x")))
    }
}
