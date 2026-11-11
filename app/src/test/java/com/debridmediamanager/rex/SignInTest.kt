package com.debridmediamanager.rex

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * What the app shows for each sign-in state, from host 0.3.0's contract. No host answers it yet: the
 * fixtures in resources/host/sign-in-*.json and signed-in.json are hand-written from it.
 */
class SignInTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun fixture(name: String) = javaClass.getResource("/host/$name.json")!!.readText()
    private fun addon(name: String) = json.decodeFromString(Addon.serializer(), fixture(name))

    private val expiresAt = 1794330000L
    private val before = expiresAt - 245
    private val after = expiresAt

    // Waiting for a sign-in is not a problem with the addon: the status line says what to do.
    @Test fun anAddonWaitingForSignInSaysSoInItsStatusLine() {
        assertTrue(addonSummary(Addon(id = "youtube", state = "error", errorKind = "sign_in")).startsWith("Sign in needed"))
        assertTrue(addonSummary(Addon(id = "youtube", state = "error", errorKind = "would_remove")).startsWith("Problem"))
    }

    // A host older than 0.3.0 sends no sign_in at all: such an addon needs no account.
    @Test fun anOlderHostNeedsNoSignIn() {
        val addon = json.decodeFromString(HostStatus.serializer(), fixture("status")).addons.single()
        assertNull(addon.signIn)
        assertEquals(AccountView(), accountView(addon, before))
    }

    @Test fun anAddonWithoutAccountsShowsItsOwnError() {
        val addon = addon("sign-in-none").copy(error = "example.com: connection refused")
        assertEquals(AccountView(error = "example.com: connection refused"), accountView(addon, before))
        assertTrue(accountView(addon.copy(errorKind = "would_remove"), before).force)
    }

    @Test fun nobodySignedInAsksToSignIn() {
        // The raw "nobody is signed in" gives way to the button that fixes it.
        assertEquals(AccountView("Sign in to start.", signIn = "Sign in to Example", canSync = false), accountView(addon("sign-in-needed"), before))
    }

    @Test fun aWaitingCodeReopensUntilItRunsOut() {
        val addon = addon("sign-in-pending")
        assertEquals(AccountView("Waiting for you to enter the code…", signIn = "Enter the code", waiting = true, canSync = false),
            accountView(addon, before))
        assertEquals(AccountView("The code ran out.", signIn = "Get a new code", canSync = false), accountView(addon, after))
    }

    @Test fun signedInOffersSignOut() {
        val addon = addon("signed-in")
        assertEquals(AccountView("Signed in as film.fan@example.com", signOut = true), accountView(addon, before))
        // Rex's removal guard can still be overridden once someone is signed in.
        assertTrue(accountView(addon.copy(errorKind = "would_remove", error = "rex refused"), before).force)
        assertEquals("Signed in", accountView(addon.copy(signIn = SignIn(account = "")), before).line)
    }

    @Test fun aFailedSignInSaysWhatWentWrong() {
        assertEquals(AccountView("Sign in to start.", "That didn't work: the code was denied", "Sign in", canSync = false),
            accountView(addon("sign-in-failed"), before))
        // Another problem than the missing account still shows.
        val offline = addon("sign-in-needed").copy(error = "example.com: connection refused", errorKind = null)
        assertEquals("example.com: connection refused", accountView(offline, before).error)
    }

    @Test fun neverForceASyncWithoutAnAccount() {
        for (name in listOf("sign-in-needed", "sign-in-pending", "sign-in-failed")) {
            val addon = addon(name).copy(errorKind = "would_remove")
            assertFalse(name, syncAnyway(addon))
            assertFalse(name, accountView(addon, before).force)
            assertFalse(name, accountView(addon, before).canSync)
        }
    }

    @Test fun theAppReopensOnlyALiveCode() {
        val host = HostStatus(addons = listOf(addon("signed-in"), addon("sign-in-needed"), addon("sign-in-pending").copy(id = "waiting")))
        assertEquals("waiting", waitingSignIn(host, before))
        assertNull(waitingSignIn(host, after))
        // A code left over while someone is signed in already is not worth showing.
        val signedIn = addon("signed-in")
        assertNull(waitingSignIn(HostStatus(addons = listOf(signedIn.copy(signIn = signedIn.signIn!!.copy(pending = PendingSignIn("X", "u", after))))), before))
    }

    @Test fun theCountdownReadsAsMinutes() {
        assertEquals("4:05", countdown(245))
        assertEquals("0:09", countdown(9))
        assertEquals("15:00", countdown(900))
        assertEquals("0:00", countdown(-3))
    }


    // Host 0.5.0's Spotify pairing: no code, a device to pick in the Spotify app. Hand-written fixtures.
    @Test fun aSpotifyPairingSaysPickItInSpotify() {
        val addon = addon("spotify-pending")
        assertEquals(AccountView("Waiting for you to pick it in Spotify…", signIn = "Connect Spotify", waiting = true, canSync = false),
            accountView(addon, before))
        assertEquals(AccountView("Spotify didn't see a pick in time.", signIn = "Try again", canSync = false), accountView(addon, after))
        val idle = addon.copy(signIn = addon.signIn!!.copy(pending = null), error = null)
        assertEquals(AccountView("Connect to start.", signIn = "Connect Spotify", canSync = false), accountView(idle, before))
        assertEquals(AccountView("Connected as music.fan", signOut = true), accountView(addon("spotify-connected"), before))
        assertEquals("Connected", signedInAs(true, ""))
        // A waiting pairing comes back on screen when the app starts, as a waiting code does.
        assertEquals("spotify", waitingSignIn(HostStatus(addons = listOf(addon)), before))
    }

    @Test fun theMulticastLockIsHeldOnlyWhileSpotifyCanPickThisDevice() {
        val pairing = addon("spotify-pending")
        assertTrue(wantsMulticast(HostStatus(addons = listOf(addon("sign-in-none"), pairing)), before))
        assertFalse("ran out", wantsMulticast(HostStatus(addons = listOf(pairing)), after))
        assertFalse("connected", wantsMulticast(HostStatus(addons = listOf(addon("spotify-connected"))), before))
        assertFalse("cancelled", wantsMulticast(HostStatus(addons = listOf(pairing.copy(signIn = pairing.signIn!!.copy(pending = null)))), before))
        // A code sign-in needs nothing from the local network.
        assertFalse("code", wantsMulticast(HostStatus(addons = listOf(addon("sign-in-pending"))), before))
        assertFalse("no host", wantsMulticast(null, before))
    }

    @Test fun anUnmetRequirementSaysWhatToDoInOneLine() {
        val host = json.decodeFromString(HostStatus.serializer(), fixture("status-requires"))
        val (youtube, spotify, lyrics) = host.addons
        assertNull(unmetNeed(youtube, host.addons))
        assertEquals("Sign in to the YouTube addon first.", unmetNeed(spotify, host.addons))
        // The first unmet one is enough.
        assertEquals("Sign in to the YouTube addon first.", unmetNeed(lyrics, host.addons))
        val need = { reason: String? -> unmetNeed(Addon("x", requires = listOf(Requirement("youtube", true), Requirement("youtube", false, reason))), host.addons) }
        assertEquals("Needs the YouTube addon turned on.", need("disabled"))
        assertEquals("Needs the YouTube addon: install it and sign in.", need("not installed"))
        assertEquals("Needs the YouTube addon.", need(null))
        // Not installed, so only its id names it.
        assertEquals("Needs the Youtube addon: install it and sign in.", unmetNeed(spotify.copy(requires = listOf(Requirement("youtube", false, "not installed"))), emptyList()))
        assertNull(unmetNeed(addon("spotify-connected"), host.addons))
    }
}
