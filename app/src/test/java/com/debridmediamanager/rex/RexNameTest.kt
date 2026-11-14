package com.debridmediamanager.rex

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * rex's name can change after the app first gave it one: a Plex app's Friendly name setting renames
 * it. resources/rex/root-renamed.json is what rex 0f5c046b on an Android 15 emulator answered to
 * `GET /` after `PUT /:/prefs?FriendlyName=Living Room` (2026-11-14; account and machine id replaced).
 */
class RexNameTest {
    @Test fun theNameIsWhatRexAnswersNow() {
        MockWebServer().use { rex ->
            rex.enqueue(MockResponse().setBody(javaClass.getResource("/rex/root-renamed.json")!!.readText()))
            assertEquals("Living Room", rexName(rex.url("").toString().trimEnd('/'), "server-token"))
            val asked = rex.takeRequest()
            assertEquals("/", asked.path)
            assertEquals("server-token", asked.getHeader("X-Plex-Token"))
            assertEquals("application/json", asked.getHeader("Accept"))
        }
    }

    // An unclaimed rex has no token and serves nobody: the app keeps the name it gave.
    @Test fun withoutATokenRexIsNotAsked() = assertNull(rexName("http://127.0.0.1:1", null))

    @Test fun theTokenComesFromRexsClaim() {
        val directory = Files.createTempDirectory("rex").toFile()
        assertNull(rexToken(directory))
        File(directory, "claim_state.json").writeText("""{"machine":"m","token":"server-token","owner":"someone"}""")
        assertEquals("server-token", rexToken(directory))
        File(directory, "claim_state.json").writeText("""{"machine":"m","token":null}""")
        assertNull(rexToken(directory))
    }
}
