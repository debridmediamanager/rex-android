package com.debridmediamanager.rex

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** The client against a stand-in for rex-addons' management API, as the contract describes it. */
class AddonsClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = AddonsClient(server.url("/").toString().removeSuffix("/"))
    @After fun stop() = server.shutdown()

    private fun fixture(name: String) = javaClass.getResource("/host/$name")!!.readText()

    // Answered by rex-addons 1dd7a64 on a Pixel 6 after the bundled addon's first sync, with the
    // fields host 0.2.0 adds (error_kind, hosts) written in by hand until a 0.2.0 host is recorded.
    @Test fun statusReadsTheRealHostsAnswer() {
        server.enqueue(MockResponse().setBody(fixture("status.json")))
        val status = client.status()
        assertEquals("/api/status", server.takeRequest().path)
        assertTrue(status.rex.reachable)
        val addon = status.addons.single()
        assertEquals("internet-archive", addon.id)
        assertEquals(100L, addon.items)
        assertEquals(0L, addon.held)
        assertEquals(1794328362L, addon.lastSync)
        assertNull(addon.error)
        assertEquals(listOf("archive.org", "*.archive.org"), addon.hosts)
        assertNull(addon.updateHosts)
        // Hosts before 0.3.0 send no sign_in: such an addon needs no account.
        assertNull(addon.signIn)
    }

    @Test fun statusKeepsWhatAFailingAddonSaysAndIgnoresNewFields() {
        server.enqueue(MockResponse().setBody("""
            {"version":"0.2.0","rex":{"url":"http://127.0.0.1:32500","reachable":false},"uptime":12,
             "addons":[{"id":"broken","name":"Broken","enabled":false,"state":"error","items":0,"held":3,
               "last_sync":null,"error":"rex refused a sync that removes more than half","homepage":"x",
               "error_kind":"would_remove"},
              {"id":"offline","name":"Offline","enabled":true,"state":"error","items":5,"held":0,
               "last_sync":null,"error":"archive.org: connection refused","error_kind":null}]}
        """.trimIndent()))
        val (addon, offline) = client.status().addons
        assertEquals("error", addon.state)
        assertEquals(3L, addon.held)
        assertNull(addon.lastSync)
        assertEquals("rex refused a sync that removes more than half", addon.error)
        // Only rex's removal guard is worth overriding; any other failure just syncs again.
        assertTrue(syncAnyway(addon))
        assertFalse(syncAnyway(offline))
    }

    @Test fun settingsReadTheRealHostsAnswer() {
        server.enqueue(MockResponse().setBody(fixture("internet-archive.json")))
        val settings = client.settings("internet-archive")
        assertEquals("/api/addons/internet-archive", server.takeRequest().path)
        assertEquals("Internet Archive", settings.name)
        assertEquals(listOf(ConfigField("count", "How many films", "number", JsonPrimitive(100))), settings.fields)
        assertEquals(mapOf("count" to "100"), initialForm(settings))
        assertEquals(0L, settings.deleted)
    }

    // Titles deleted in a Plex app stay away until the person brings them back.
    @Test fun deletedTitlesCanBeBroughtBack() {
        server.enqueue(MockResponse().setBody("""
            {"id":"a","name":"A","deleted":3,"manifest":{"id":"a","name":"A","config":[]},"config":{}}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("{}"))
        assertEquals(3L, client.settings("a").deleted)
        client.restore("a")
        server.takeRequest()
        val restore = server.takeRequest()
        assertEquals("POST /api/addons/a/restore", "${restore.method} ${restore.path}")
        assertEquals("{}", restore.body.readUtf8())
    }

    // The shapes docs/api.md gives for a saved secret (masked) and an unset one (null).
    @Test fun aSavedSecretIsMaskedAndAnUnsetOneIsNot() {
        server.enqueue(MockResponse().setBody("""
            {"id":"lists","name":"Lists","manifest":{"id":"lists","name":"Lists","config":[
              {"key":"urls","label":"Links","type":"list","required":true},
              {"key":"token","label":"Token","type":"secret"},
              {"key":"other","label":"Other key","type":"secret"}]},
             "config":{"urls":["https://a.example/1","https://a.example/2"],"token":"********","other":null}}
        """.trimIndent()))
        val settings = client.settings("lists id")
        assertEquals("/api/addons/lists%20id", server.takeRequest().path)
        assertTrue(secretSaved(settings, "token"))
        assertFalse(secretSaved(settings, "other"))
        assertEquals(mapOf("urls" to "https://a.example/1\nhttps://a.example/2", "token" to "", "other" to ""), initialForm(settings))
    }

    @Test fun actionsSendTheContractsRequests() {
        repeat(6) { server.enqueue(MockResponse().setBody("{}")) }
        client.install("r", "a")
        client.enable("a", false)
        client.sync("a")
        client.configure("a", buildJsonObject { put("q", "dogs") })
        client.remove("a")
        client.sync("a", force = true)
        val requests = List(6) { server.takeRequest() }
        assertEquals(listOf("POST /api/addons", "POST /api/addons/a/enabled", "POST /api/addons/a/sync",
            "PUT /api/addons/a/config", "DELETE /api/addons/a", "POST /api/addons/a/sync"), requests.map { "${it.method} ${it.path}" })
        assertEquals("{}", requests[2].body.readUtf8())
        assertEquals(buildJsonObject { put("force", true) }, Json.parseToJsonElement(requests[5].body.readUtf8()))
        assertEquals(buildJsonObject { put("repo", "r"); put("addon", "a") }, Json.parseToJsonElement(requests[0].body.readUtf8()))
        assertEquals(buildJsonObject { put("enabled", false) }, Json.parseToJsonElement(requests[1].body.readUtf8()))
        assertEquals(buildJsonObject { put("q", "dogs") }, Json.parseToJsonElement(requests[3].body.readUtf8()))
        assertTrue(requests[0].getHeader("Content-Type")!!.startsWith("application/json"))
    }

    // The repository contract (rex-addons, 2026-11-10); no host answers it yet, so these are its shapes.
    @Test fun repositoriesAndTheirAddons() {
        server.enqueue(MockResponse().setBody("""
            [{"id":"rex-addons","url":"https://github.com/debridmediamanager/rex-addons","name":"rex addons",
              "description":"The official ones","commit":"1dd7a64","last_fetch":1794328362,"error":null,
              "addons":["internet-archive","stremio"]},
             {"id":"broken","url":"https://git.example/x","name":"","description":"","commit":null,"last_fetch":null,
              "error":"git clone failed: 404","addons":0}]
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            [{"id":"internet-archive","name":"Internet Archive","version":"1.1.0","description":"Films",
              "hosts":["archive.org","*.archive.org","cdn.example.org"],"installed_version":"1.0.0","update_available":true,
              "installed_hosts":["archive.org","*.archive.org"],"installed_repo":null,"compatible":true},
             {"id":"stremio","name":"Stremio","version":"1.0.0","description":"","hosts":["*"],
              "installed_version":null,"update_available":false,"installed_hosts":null,"installed_repo":null,"compatible":false}]
        """.trimIndent()))
        val repos = client.repos()
        assertEquals("/api/repos", server.takeRequest().path)
        assertEquals(listOf(2, 0), repos.map { it.addonCount })
        assertEquals("git clone failed: 404", repos[1].error)
        val offered = client.repoAddons("rex-addons")
        assertEquals("/api/repos/rex-addons/addons", server.takeRequest().path)
        assertTrue(offered[0].updateAvailable)
        assertEquals("1.0.0", offered[0].installedVersion)
        assertNull(offered[1].installedVersion)
        assertEquals(listOf("archive.org", "*.archive.org"), offered[0].installedHosts)
        assertNull(offered[0].installedRepo)
        assertNull(offered[1].installedHosts)
        assertTrue(offered[0].compatible)
        assertFalse(offered[1].compatible)
        assertEquals("archive.org, *.archive.org, cdn.example.org", reach(offered[0].hosts))
        assertEquals("any website", reach(offered[1].hosts))
    }

    @Test fun repositoryActionsSendTheContractsRequests() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"r","url":"https://github.com/u/r","addons":[]}"""))
        repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
        assertEquals("r", client.addRepo("https://github.com/u/r").id)
        client.refreshRepo("r")
        client.install("r", "internet-archive")
        client.removeRepo("r")
        val requests = List(4) { server.takeRequest() }
        assertEquals(listOf("POST /api/repos", "POST /api/repos/r/refresh", "POST /api/addons", "DELETE /api/repos/r"),
            requests.map { "${it.method} ${it.path}" })
        assertEquals(buildJsonObject { put("url", "https://github.com/u/r") }, Json.parseToJsonElement(requests[0].body.readUtf8()))
        assertEquals(buildJsonObject { put("repo", "r"); put("addon", "internet-archive") }, Json.parseToJsonElement(requests[2].body.readUtf8()))
    }

    @Test fun anInstalledAddonSaysWhereItCameFromAndWhetherItCanUpdate() {
        server.enqueue(MockResponse().setBody("""
            {"version":"0.2.0","rex":{"url":"http://127.0.0.1:32400","reachable":true},"addons":[
              {"id":"internet-archive","name":"Internet Archive","enabled":true,"state":"idle","items":100,"held":0,
               "last_sync":1794328362,"error":null,"repo":"rex-addons","update_available":true,
               "hosts":["archive.org"],"update_hosts":["archive.org","cdn.example.org"]},
              {"id":"bundled","name":"Bundled","enabled":true,"state":"idle","items":1,"held":0,"last_sync":null,
               "error":null,"repo":null,"update_available":false}]}
        """.trimIndent()))
        val (fromRepo, bundled) = client.status().addons
        assertEquals("rex-addons", fromRepo.repo)
        assertTrue(fromRepo.updateAvailable)
        assertEquals(listOf("archive.org", "cdn.example.org"), fromRepo.updateHosts)
        assertNull(bundled.repo)
    }

    // A restarted host demands its new token, so the client reads the current one on each call.
    @Test fun everyCallCarriesTheHostsCurrentToken() {
        var token: String? = "first"
        val client = AddonsClient(server.url("/").toString().removeSuffix("/"), token = { token })
        repeat(2) { server.enqueue(MockResponse().setBody(fixture("status.json"))) }
        client.status()
        token = "second"
        client.status()
        assertEquals(listOf("Bearer first", "Bearer second"), List(2) { server.takeRequest().getHeader("Authorization") })
    }

    @Test fun eachHostStartGetsAFreshRandomToken() {
        val first = AddonsToken.fresh()
        val second = AddonsToken.fresh()
        assertTrue(first, Regex("[0-9a-f]{64}").matches(first))
        assertNotEquals(first, second)
        assertEquals(second, AddonsToken.current)
    }

    // The host clones the repository before it answers, which a slow device takes a while for.
    @Test fun addingARepositoryWaitsLongerThanOtherCalls() {
        val quick = okhttp3.OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        val client = AddonsClient(server.url("/").toString().removeSuffix("/"), quick)
        server.enqueue(MockResponse().setBody("""{"id":"r","url":"https://github.com/u/r"}""").setHeadersDelay(1, TimeUnit.SECONDS))
        assertEquals("r", client.addRepo("https://github.com/u/r").id)
        server.enqueue(MockResponse().setBody("[]").setHeadersDelay(1, TimeUnit.SECONDS))
        assertThrows(SocketTimeoutException::class.java) { client.repos() }
    }

    @Test fun aShortRepositoryLinkBecomesTheFullOne() {
        assertEquals("https://github.com/debridmediamanager/rex-addons", repoLink(" github.com/debridmediamanager/rex-addons/ "))
        assertEquals("https://github.com/user/repo", repoLink("user/repo"))
        assertEquals("https://gitlab.com/group/sub/repo", repoLink("gitlab.com/group/sub/repo"))
        assertEquals("https://git.example.org/me/addons.git", repoLink("https://git.example.org/me/addons.git"))
        assertNull(repoLink("http://github.com/user/repo"))
        assertNull(repoLink("just words"))
        assertNull(repoLink("repo"))
        assertNull(repoLink(""))
    }

    @Test fun theHostsErrorIsWhatThePersonSees() {
        // The wording rex-addons 1dd7a64 used for a link with nothing behind it.
        val refusal = "https://example.org/nothing/manifest.json: https://example.org/nothing/manifest.json: status code 404"
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"$refusal"}"""))
        val failure = assertThrows(ApiFailure::class.java) { client.addRepo("https://example.org/nothing") }
        assertEquals(400, failure.status)
        assertEquals(refusal, failure.message)
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        assertEquals("The addon host answered 502", assertThrows(ApiFailure::class.java) { client.sync("a") }.message)
    }

    // Host 0.3.0's sign-in contract. No host answers it yet: these fixtures are hand-written from it.
    private fun addon(name: String): Addon {
        server.enqueue(MockResponse().setBody(fixture("$name.json")))
        return client.addon("example").also { assertEquals("GET /api/addons/example", server.takeRequest().let { "${it.method} ${it.path}" }) }
    }

    @Test fun eachSignInShapeReads() {
        val expiresAt = 1794330000L
        assertNull(addon("sign-in-none").signIn)
        assertEquals(SignIn(label = "Sign in to Example"), addon("sign-in-needed").signIn)
        assertEquals(SignIn("Sign in to Example", pending = PendingSignIn("ABCD-EFGH", "https://example.com/device", expiresAt)),
            addon("sign-in-pending").signIn)
        assertEquals("film.fan@example.com", addon("signed-in").signIn?.account)
        assertEquals(SignIn(error = "the code was denied"), addon("sign-in-failed").signIn)
    }

    @Test fun signingInAndOutSendTheContractsRequests() {
        server.enqueue(MockResponse().setBody(fixture("sign-in-pending.json")))
        server.enqueue(MockResponse().setBody(fixture("sign-in-needed.json")))
        assertEquals("ABCD-EFGH", client.signIn("example").signIn?.pending?.code)
        assertNull(client.signOut("example").signIn?.pending)
        val (start, stop) = List(2) { server.takeRequest() }
        assertEquals("POST /api/addons/example/sign-in", "${start.method} ${start.path}")
        assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(start.body.readUtf8()))
        assertEquals("DELETE /api/addons/example/sign-in", "${stop.method} ${stop.path}")
    }

    @Test fun signInRefusalsSayWhy() {
        for ((status, reason) in listOf(400 to "example needs no sign-in", 409 to "example is turned off", 502 to "example.com: device code refused")) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"$reason"}"""))
            val failure = assertThrows(ApiFailure::class.java) { client.signIn("example") }
            assertEquals(status, failure.status)
            assertEquals(reason, failure.message)
        }
    }

    // Host 0.5.0's Spotify pairing and requirements. No host answers them yet: the fixtures are hand-written.
    @Test fun aSpotifyPairingCarriesTheDeviceNameAndNoCode() {
        server.enqueue(MockResponse().setBody(fixture("spotify-pending.json")))
        val pairing = client.signIn("spotify").signIn!!
        assertEquals("POST /api/addons/spotify/sign-in", server.takeRequest().let { "${it.method} ${it.path}" })
        assertTrue(pairing.spotify)
        assertEquals(PendingSignIn(expiresAt = 1794330000, device = "Pixel 6"), pairing.pending)
        assertEquals("music.fan", addon("spotify-connected").signIn?.account)
        assertEquals(listOf(Requirement("youtube", ok = true)), addon("spotify-connected").requires)
    }

    // Before 0.5.0 a sign-in has no kind (a code) and an addon needs no other addon.
    @Test fun olderHostsMeanACodeAndNoRequirements() {
        for (name in listOf("sign-in-pending", "signed-in", "sign-in-needed")) {
            val addon = addon(name)
            assertNull(name, addon.signIn?.kind)
            assertFalse(name, addon.signIn!!.spotify)
            assertEquals(name, emptyList<Requirement>(), addon.requires)
        }
        server.enqueue(MockResponse().setBody(fixture("status.json")))
        assertEquals(emptyList<Requirement>(), client.status().addons.single().requires)
    }

    @Test fun statusReadsEachAddonsRequirements() {
        server.enqueue(MockResponse().setBody(fixture("status-requires.json")))
        val (youtube, spotify, lyrics) = client.status().addons
        assertEquals("code", youtube.signIn?.kind)
        assertEquals(emptyList<Requirement>(), youtube.requires)
        assertTrue(spotify.signIn!!.spotify)
        assertEquals(listOf(Requirement("youtube", ok = false, reason = "sign in needed")), spotify.requires)
        assertEquals(listOf("sign in needed", "not installed"), lyrics.requires.map { it.reason })
    }

    // Answered by rex-addons 0afc3bb (0.8.0) on a Fire TV Stick 4K Max, for a test addon with a select, help
    // text and a host setting typed as a file server on the LAN, whose listing has a repeated id and an absolute path.
    @Test fun hostSettingsWarningsAndWhatThePersonAllowed() {
        server.enqueue(MockResponse().setBody(fixture("status-0.8.json")))
        val addon = client.status().addons.single { it.id == "lan-films" }
        assertEquals(listOf("192.168.88.39:18765"), addon.grantedHosts)
        assertEquals(2L, addon.items)
        assertEquals(Warnings(2, listOf("items(): beach repeats an id or a path; left out",
            "items(): \"broken\" has the path \"/absolute.mp4\", which is not relative; left out")), addon.warnings)
        server.enqueue(MockResponse().setBody(fixture("lan-films.json")))
        val settings = client.settings("lan-films")
        assertEquals(listOf("192.168.88.39:18765"), settings.grantedHosts)
        assertEquals("Can reach only what you allowed: 192.168.88.39:18765.", reachLine(settings.hosts, settings.grantedHosts, asks = true))
        val (quality, server) = settings.fields
        assertEquals(ConfigField("quality", "Highest quality", "select", JsonPrimitive("1080"), help = "Pick lower on a slow network",
            options = listOf(SelectOption("1080", "1080p"), SelectOption("720", "720p"))), quality)
        assertEquals(ConfigField("server", "Server", "host", required = true, help = "Such as 192.168.1.20:8096"), server)
        assertEquals(mapOf("quality" to "720", "server" to "192.168.88.39:18765"), initialForm(settings))
    }

    @Test fun anUpdateSaysWhichAddressSettingsItAdds() {
        server.enqueue(MockResponse().setBody("""
            {"version":"0.8.0","rex":{"url":"","reachable":true},"addons":[
              {"id":"a","update_available":true,"update_hosts":["a.example"],"update_host_settings":["Server"]},
              {"id":"b","update_available":false,"update_hosts":null,"update_host_settings":null}]}
        """.trimIndent()))
        val (a, b) = client.status().addons
        assertEquals(listOf("Server"), a.updateHostSettings)
        assertNull(b.updateHostSettings)
        assertEquals(Warnings(), b.warnings)
        assertEquals(emptyList<String>(), b.grantedHosts)
    }

    // The same addon's log on that Fire TV, from its install to its first sync.
    @Test fun theLogAsksForItsLastLines() {
        server.enqueue(MockResponse().setBody(fixture("lan-films-log.json")))
        val log = client.log("lan-films")
        assertEquals("GET /api/addons/lan-films/log?lines=300", server.takeRequest().let { "${it.method} ${it.path}" })
        assertEquals(6, log.size)
        assertEquals(AddonLogLine(1794492018, "[lan-films] synced in 0.5 s (every title): 2 items in rex, 0 held"), log.last())
    }
}
