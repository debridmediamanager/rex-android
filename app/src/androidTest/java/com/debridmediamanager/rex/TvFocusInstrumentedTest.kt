package com.debridmediamanager.rex

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket

/** Where a remote's focus lands, driven with D-pad keys as on a TV. */
@OptIn(ExperimentalTestApi::class)
class TvFocusInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!check()) { assertTrue(what, System.currentTimeMillis() < until); Thread.sleep(200) }
    }
    private fun select(label: String) = compose.onNodeWithText(label).requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }

    // Start rex leaves the page with step 1, and step 2 opened with nothing focused: a remote had to
    // press a direction before anything lit up (emulator, 0.3.1, 2026-11-14).
    @Test fun setupStepTwoOpensWithAButtonFocused() {
        val runtime = context.runtime
        val (port, wanted, setup) = Triple(runtime.port, runtime.wanted, runtime.setupDone)
        try {
            runtime.port = ServerSocket(0).use { it.localPort }
            compose.setContent { CompositionLocalProvider(LocalTelevision provides true) { SetupScreen({}, busy = false) {} } }
            select("Start rex")
            compose.onNode(hasText("Skip for now") or hasText("Done")).assertIsFocused()
        } finally {
            RexService.send(context, RexService.STOP)
            waitFor("rex did not stop") { !Status.rex.value.running }
            runtime.port = port; runtime.setupDone = setup; runtime.wanted = wanted
        }
    }
}
