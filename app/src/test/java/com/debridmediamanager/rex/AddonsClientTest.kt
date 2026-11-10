package com.debridmediamanager.rex

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The client against a stand-in for rex-addons' management API, as the contract describes it. */
class AddonsClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = AddonsClient(server.url("/").toString().removeSuffix("/"))
    @After fun stop() = server.shutdown()

    private fun fixture(name: String) = javaClass.getResource("/host/$name")!!.readText()

    // Answered by rex-addons 1dd7a64 on a Pixel 6 after the bundled addon's first sync.
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
    }

    @Test fun statusKeepsWhatAFailingAddonSaysAndIgnoresNewFields() {
        server.enqueue(MockResponse().setBody("""
            {"version":"0.2.0","rex":{"url":"http://127.0.0.1:32500","reachable":false},"uptime":12,
             "addons":[{"id":"broken","name":"Broken","enabled":false,"state":"error","items":0,"held":3,
               "last_sync":null,"error":"rex refused a sync that removes more than half","homepage":"x"}]}
        """.trimIndent()))
        val addon = client.status().addons.single()
        assertEquals("error", addon.state)
        assertEquals(3L, addon.held)
        assertNull(addon.lastSync)
        assertEquals("rex refused a sync that removes more than half", addon.error)
    }

    @Test fun settingsReadTheRealHostsAnswer() {
        server.enqueue(MockResponse().setBody(fixture("internet-archive.json")))
        val settings = client.settings("internet-archive")
        assertEquals("/api/addons/internet-archive", server.takeRequest().path)
        assertEquals("Internet Archive", settings.name)
        assertEquals(listOf(ConfigField("count", "How many films", "number", JsonPrimitive(100))), settings.fields)
        assertEquals(mapOf("count" to "100"), initialForm(settings))
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
        client.install("https://example.org/addon/manifest.json")
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
        assertEquals(buildJsonObject { put("url", "https://example.org/addon/manifest.json") }, Json.parseToJsonElement(requests[0].body.readUtf8()))
        assertEquals(buildJsonObject { put("enabled", false) }, Json.parseToJsonElement(requests[1].body.readUtf8()))
        assertEquals(buildJsonObject { put("q", "dogs") }, Json.parseToJsonElement(requests[3].body.readUtf8()))
        assertTrue(requests[0].getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun theHostsErrorIsWhatThePersonSees() {
        // What rex-addons 1dd7a64 answered for a link with no addon behind it.
        val refusal = "https://example.org/nothing/manifest.json: https://example.org/nothing/manifest.json: status code 404"
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"$refusal"}"""))
        val failure = assertThrows(ApiFailure::class.java) { client.install("https://example.org/nothing/manifest.json") }
        assertEquals(400, failure.status)
        assertEquals(refusal, failure.message)
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        assertEquals("The addon host answered 502", assertThrows(ApiFailure::class.java) { client.sync("a") }.message)
    }
}
