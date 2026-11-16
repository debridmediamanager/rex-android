package com.debridmediamanager.rex

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Plex sign-in that claims rex, against plex.tv and rex played by one MockWebServer. rex's /rex/claim
 * answers are written from its contract ({"claimed", "refused", "refused_since", "owner"}); an older rex
 * answers 401 or 404 there. Ported from zurg's Android app (d52d9b15, fa78ff21), which had this app's sign-in:
 * a patron's rex there was signed out by plex.tv (every call 401) on 2026-11-16 while the app still showed
 * "Open Plex", the page a phone opened refused its code, and a sign-in could not be cancelled.
 */
class PlexSignInTest {
    private val server = MockWebServer()
    private val calls = CopyOnWriteArrayList<String>()
    @Volatile private var linked = false
    @Volatile private var pinLifetime = 900
    @Volatile private var claimStatus = 200
    @Volatile private var claimToken: String? = "unset"
    @Volatile private var identityClaimed = false
    // null: an engine without /rex/claim
    @Volatile private var rexClaim: String? = """{"claimed": false, "refused": false, "refused_since": null, "owner": null}"""
    @Volatile private var missing = 401
    @Volatile private var pinDelayMillis = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                calls += "${request.method} ${path.substringBefore('?')}${if (path.contains("strong=true")) "?strong" else ""}"
                return when {
                    path.startsWith("/api/v2/pins") && request.method == "POST" -> {
                        val strong = path.contains("strong=true")
                        MockResponse().setBody("""{"id": ${if (strong) 2 else 1}, "code": "${if (strong) "s7rongcodefortheauthpage" else "AB12"}", "expiresIn": $pinLifetime}""")
                            .setHeadersDelay(pinDelayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    path.startsWith("/api/v2/pins/") -> MockResponse().setBody(
                        """{"id": 1, "authToken": ${if (linked) "\"account-token\"" else "null"}}""")
                    path.startsWith("/api/claim/token.json") -> MockResponse().setBody("""{"token": "claim-xyz"}""")
                    path.startsWith("/myplex/claim") -> MockResponse().setResponseCode(claimStatus)
                        .also { claimToken = request.getHeader("X-Plex-Token") }
                    // An older rex has no /rex/claim and answers it 401, as it does any path it does not know.
                    path == "/rex/claim" -> rexClaim?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(missing)
                    path == "/identity" -> MockResponse().setBody(
                        """<MediaContainer claimed="${if (identityClaimed) 1 else 0}" friendlyName="Pixel" />""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
    }

    @After fun stop() { scope.cancel(); server.shutdown() }

    private val base get() = server.url("/").toString().trimEnd('/')
    private fun signIn() = PlexSignIn("client", "Pixel", rexUrl = base, plex = base, pollMillis = 20, confirmMillis = 1_000)
    private fun flow() = PlexSignInFlow(scope)
    private fun PlexSignInFlow.settled() = runBlocking { withTimeout(10_000) { state.first { !it.waiting } } }
    private fun PlexSignInFlow.showsCode() = runBlocking { withTimeout(10_000) { state.first { it.code.isNotBlank() } } }

    @Test fun signedInOnlyOnceRexReportsTheClaimWorking() {
        val flow = flow()
        flow.start(signIn(), television = true) {}
        assertEquals("AB12", flow.showsCode().code)
        linked = true
        // rex took the claim but still reports Plex refusing it.
        rexClaim = """{"claimed": true, "refused": true, "refused_since": 1794330000, "owner": "mark"}"""
        assertEquals("rex took the sign-in, but Plex has not accepted it. Sign in again.", flow.settled().message)
        assertTrue(calls.any { it.startsWith("POST /myplex/claim") })

        rexClaim = """{"claimed": true, "refused": false, "refused_since": null, "owner": "mark"}"""
        flow.start(signIn(), television = true) {}
        assertTrue(flow.settled().message.startsWith("Signed in."))
    }

    @Test fun anEngineWithoutRexClaimIsSignedInWhenIdentityFlips() {
        rexClaim = null
        val flow = flow()
        flow.start(signIn(), television = true) {}
        flow.showsCode()
        identityClaimed = true
        linked = true
        assertEquals(PlexSignInState(message = "Signed in. Open a Plex app and pick Pixel.", signedIn = true), flow.settled())
    }

    @Test fun cancelEndsTheWaitAndClaimsNothing() {
        val flow = flow()
        flow.start(signIn(), television = true) {}
        flow.showsCode()
        flow.cancel()
        assertEquals(PlexSignInState(), flow.state.value)
        linked = true
        Thread.sleep(200)
        assertEquals(PlexSignInState(), flow.state.value)
        assertFalse(calls.any { it.startsWith("GET /api/claim") || it.startsWith("POST /myplex/claim") })
    }

    // The request for a code cannot be cancelled; the code it brings back afterwards must not show up.
    @Test fun aCodeArrivingAfterCancelIsDropped() {
        pinDelayMillis = 300
        val flow = flow()
        flow.start(signIn(), television = true) {}
        Thread.sleep(100) // inside the request for a code
        flow.cancel()
        Thread.sleep(600)
        assertEquals(PlexSignInState(), flow.state.value)
    }

    @Test fun anUnusedCodeExpires() {
        pinLifetime = 0
        val flow = flow()
        flow.start(signIn(), television = true) {}
        assertEquals(PlexSignInState(message = "The Plex code expired. Ask for a new one."), flow.settled())
    }

    // The claim goes without a token, so a healthy claimed rex answers 401 (rex fix/refused-claim 61514c8c), not the
    // 409 it gives a request with a valid one.
    @Test fun aHealthyClaimedRexSaysWhyItRefused() {
        linked = true
        for (status in listOf(401, 409)) {
            claimStatus = status
            val flow = flow()
            flow.start(signIn(), television = true) {}
            assertEquals("rex is already signed in to Plex, so it kept that sign-in.", flow.settled().message)
            assertNull(claimToken)
        }
    }

    @Test fun plexUnreachableSaysSo() {
        val flow = flow()
        flow.start(PlexSignIn("client", "Pixel", rexUrl = base, plex = "http://127.0.0.1:1", pollMillis = 20), television = true) {}
        assertEquals("Could not reach Plex. Check this device's internet connection and try again.", flow.settled().message)
    }

    // app.plex.tv/auth refuses a four-character code (measured 2026-11-16), so the page gets a strong PIN
    // while the four-character one stays on screen for plex.tv/link; a TV never opens a page.
    @Test fun aPhoneOpensTheAuthPageWithAStrongPinAndKeepsTheShortCode() {
        val opened = CopyOnWriteArrayList<String>()
        val flow = flow()
        flow.start(signIn(), television = false) { opened += it }
        assertEquals("AB12", flow.showsCode().code)
        runBlocking { withTimeout(5_000) { while (opened.isEmpty()) delay(10) } }
        assertTrue(opened.single(), opened.single().contains("code=s7rongcodefortheauthpage"))
        assertTrue(calls.contains("POST /api/v2/pins?strong"))
        flow.cancel()

        calls.clear(); opened.clear()
        flow.start(signIn(), television = true) { opened += it }
        flow.showsCode()
        assertEquals(listOf("POST /api/v2/pins"), calls.filter { it.startsWith("POST /api/v2/pins") })
        assertTrue(opened.isEmpty())
    }

    @Test fun aRefusedClaimOffersSignInAgain() {
        identityClaimed = true
        rexClaim = """{"claimed": true, "refused": true, "refused_since": 1794330000, "owner": "mark"}"""
        val rex = rexState(base, refused = false)
        assertTrue(rex.refused)
        assertTrue(rex.needsSignIn)
        assertFalse(rex.ready)
        assertEquals("Plex signed this server out. Sign in again.", rex.detail)
    }

    @Test fun anOlderEngineReadsClaimedFromIdentity() {
        rexClaim = null
        for (status in listOf(401, 404)) {
            missing = status
            identityClaimed = true
            assertNull(rexClaim(base))
            val rex = rexState(base, refused = false)
            assertEquals(RexState("Running", "Ready for the Plex apps.", running = true, reachable = true, claimed = true), rex)
            assertFalse(rex.needsSignIn)
            identityClaimed = false
            assertTrue(rexState(base, rex.refused).needsSignIn)
        }
    }

    // Only rex says "refused": a failed read of /rex/claim keeps what was known.
    @Test fun aFailedClaimReadKeepsWhatWasKnown() {
        identityClaimed = true
        rexClaim = "not json"
        assertTrue(rexState(base, refused = true).refused)
        assertFalse(rexState(base, refused = false).refused)
    }
}
