package com.debridmediamanager.rex

import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Plex sign-in on a TV, ported from zurg's Android app, which had this app's sign-in. */
class PlexSignInInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aTelevisionShowsTheCodeOnlyAndCanCancel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pages = instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }, null, true)
        val before = Status.rex.value
        try {
            Status.rex.value = RexState("Running", running = true, reachable = true)
            compose.setContent { CompositionLocalProvider(LocalTelevision provides true) { PlexPanel() } }
            // A TV button answers the remote, not a touch.
            compose.onNodeWithText("Sign in to Plex").performSemanticsAction(SemanticsActions.OnClick)
            // The code comes from plex.tv, so the device needs the internet.
            compose.waitUntil(30_000) {
                PlexClaim.state.value.let { check(it.waiting) { "asking plex.tv for a code failed: ${it.message}" } }
                compose.onAllNodesWithText("plex.tv/link", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            // The code waits on its own: nothing else on the screen is held busy, and it can be cancelled.
            compose.onNodeWithText("Cancel sign-in").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Sign in to Plex").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("a TV opened a web page for the Plex sign-in", 0, pages.hits)
        } finally {
            PlexClaim.cancel()
            Status.rex.value = before
            instrumentation.removeMonitor(pages)
        }
    }

    // On zurg's app, Sign in to Plex and Cancel sign-in were two buttons, so pressing one took it off the screen
    // with the remote's focus, and the focus landed on the side menu (Fire TV Cube, 2026-11-16).
    @Test fun aTelevisionKeepsFocusOnTheSignInButtonWhenItTurnsIntoCancel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val before = Status.rex.value
        try {
            Status.rex.value = RexState("Running", running = true, reachable = true)
            // The side menu comes first, as on Status: it is where the focus went.
            compose.setContent { CompositionLocalProvider(LocalTelevision provides true) { Row { Action("Status") {}; PlexPanel() } } }
            compose.onNodeWithText("Sign in to Plex").performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithText("Sign in to Plex").assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Cancel sign-in").assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Sign in to Plex").assertIsFocused()
        } finally {
            PlexClaim.cancel()
            Status.rex.value = before
        }
    }
}
