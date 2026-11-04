package io.github.yggdrasil_sh.saga

import io.github.yggdrasil_sh.saga.chat.ChatAttach
import io.github.yggdrasil_sh.saga.chat.ChatAttach.Route
import io.github.yggdrasil_sh.saga.chat.ChatLimits
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.5: grosse Datei-Anhaenge im Chat - Weg v1 / saga-file/v2 / zu gross, v1 ueber Servergrenzen hoechstens 10 MiB */
class BigFilesChatTest {
    private val mib = 1048576L
    private val old = ChatLimits(fileMax = 10 * mib)                                       // Server ohne file2
    private val prod = ChatLimits(fileMax = 50 * mib, file2 = true, videoMax = 500 * mib)  // ATTACH_MAX_MB 50

    @Test
    fun v1LimitIsFileMaxLocallyAndAtMost10MibFederated() {
        assertEquals(50 * mib, ChatAttach.v1Max(prod, remote = false))
        assertEquals(10 * mib, ChatAttach.v1Max(prod, remote = true))
        assertEquals(5 * mib, ChatAttach.v1Max(ChatLimits(fileMax = 5 * mib), remote = true))
        assertEquals(10485760L, ChatAttach.FED_V1_MAX)
    }

    @Test
    fun localChat() {
        assertEquals(Route.V1, ChatAttach.route(50 * mib, prod, false, null))
        assertEquals(Route.V2, ChatAttach.route(50 * mib + 1, prod, false, null))
        assertEquals(Route.V2, ChatAttach.route(500 * mib, prod, false, null))
        assertEquals(Route.TOO_BIG, ChatAttach.route(500 * mib + 1, prod, false, null))
        assertEquals(500L, ChatAttach.limitMb(Route.TOO_BIG, prod, false))
        assertEquals(500 * mib, ChatAttach.maxSize(prod, false, null))
    }

    @Test
    fun serverWithoutFile2StaysV1() {
        assertEquals(Route.V1, ChatAttach.route(10 * mib, old, false, null))
        assertEquals(Route.NO_FILE2, ChatAttach.route(10 * mib + 1, old, false, null))
        assertEquals(10L, ChatAttach.limitMb(Route.NO_FILE2, old, false))
        assertEquals(10 * mib, ChatAttach.maxSize(old, false, null))
    }

    @Test
    fun federatedChat() {
        // v1 nur bis 10 MiB, auch wenn der eigene Server 50 MB nimmt
        assertEquals(Route.V1, ChatAttach.route(10 * mib, prod, true, true))
        assertEquals(Route.V2, ChatAttach.route(10 * mib + 1, prod, true, true))
        assertEquals(Route.V2, ChatAttach.route(40 * mib, prod, true, true))
        assertEquals(Route.TOO_BIG, ChatAttach.route(500 * mib + 1, prod, true, true))
        // ohne big_files (Gegenueber 2.3 bzw. Feld fehlt): nur v1 bis 10 MiB
        assertEquals(Route.PEER_OLD, ChatAttach.route(10 * mib + 1, prod, true, false))
        assertEquals(Route.PEER_OLD, ChatAttach.route(40 * mib, prod, true, null))
        assertEquals(Route.V1, ChatAttach.route(5 * mib, prod, true, false))
        assertEquals(10L, ChatAttach.limitMb(Route.PEER_OLD, prod, true))
        assertEquals(10 * mib, ChatAttach.maxSize(prod, true, false))
        assertEquals(500 * mib, ChatAttach.maxSize(prod, true, true))
    }

    @Test
    fun videoMaxUnknownMeansNoUpperBound() {
        val l = ChatLimits(fileMax = 10 * mib, file2 = true, videoMax = 0)
        assertEquals(Route.V2, ChatAttach.route(2000 * mib, l, false, null))
        assertEquals(10 * mib, ChatAttach.maxSize(l, false, null))
    }
}
