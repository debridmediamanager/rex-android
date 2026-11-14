package com.debridmediamanager.rex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ButtonsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    // An addon's card on a phone: the row used to scroll sideways with nothing to say so, which hid Log and Remove.
    @Test fun everyButtonOfAnAddonShowsOnANarrowPhone() {
        val labels = listOf("Sign in to YouTube", "Update", "Sync now", "Settings", "Log", "Sign out", "Remove")
        compose.setContent { Box(Modifier.width(320.dp)) { Buttons { labels.forEach { Action(it) {} } } } }
        labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    // "no public git repository there" used to stay on screen over every page opened afterwards.
    @Test fun pressingAButtonTakesTheLastNoticeAway() {
        var notice by mutableStateOf("no public git repository there")
        var pressed = false
        compose.setContent { CompositionLocalProvider(LocalDismissNotice provides { notice = "" }) { Action("Addons") { pressed = true } } }
        compose.onNodeWithText("Addons").performClick()
        compose.runOnIdle { assertEquals("", notice); assertEquals(true, pressed) }
    }
}
