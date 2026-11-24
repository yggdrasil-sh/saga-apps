package io.github.yggdrasil_sh.saga

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yggdrasil_sh.saga.ui.common.FirstStepsPrompt
import io.github.yggdrasil_sh.saga.ui.nav.Dest
import io.github.yggdrasil_sh.saga.ui.today.TodayScreen
import org.junit.Test
import org.junit.runner.RunWith

/** 2.6 (Bugs-DB 2031): die Frage "Möchtest du eine kurze Einführung?" ueber Heute (Beispielserver aus Es2031ShotsTest) */
@RunWith(AndroidJUnit4::class)
class Fs2031ShotsTest : Es2031ShotsTest() {
    @Test override fun screens() {
        shot("frage") { nav, chrome ->
            TodayScreen(app.repo, nav, chrome)
            FirstStepsPrompt(app.repo, accountKey = "shot", current = Dest.Today, blocked = false)
        }
    }
}
