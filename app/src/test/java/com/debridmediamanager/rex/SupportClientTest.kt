package com.debridmediamanager.rex

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.zip.GZIPInputStream

/** The upload against a stand-in for logs.debridmediamanager.com, as its contract describes it. */
class SupportClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = SupportClient(server.url("/").toString(), appVersion = "1.2.3")
    @After fun stop() = server.shutdown()

    private val host = HostStatus(version = "0.2.0", rex = RexLink("http://127.0.0.1:32400", true), addons = listOf(
        Addon("stremio", "Stremio addons", version = "1.0.1", items = 40, held = 2, lastSync = 1794328362,
            error = "fetch https://torrentio.example/realdebrid=SECRETKEY/manifest.json failed", repo = "github-com-x-y"),
        Addon("internet-archive", "Internet Archive", version = "1.0.0", enabled = false, items = 100)))

    private fun bundle() = supportBundle("1.2.3", linkedMapOf("App" to "1.2.3 (github)", "Port" to "32400"), host, "",
        currentRun = "11-11 10:00:00 rex: started\n11-11 10:00:01 addons: GET https://a.example/eyJ0b2tlbiI6ImFiYzEyMyIsInNlcnZpY2UiOiJyZCJ9/manifest.json",
        previousRun = "", now = Instant.parse("2026-11-11T10:00:02.5Z"))

    @Test fun theBundleHoldsInfoAddonsAndBothRunsRedacted() {
        val bundle = bundle()
        assertEquals("2026-11-11T10:00:02Z", bundle.createdAt)
        assertEquals(listOf("addons.txt", "current-run.log", "previous-run.log"), bundle.files.map { it.name })
        val addons = bundle.files[0].text
        assertTrue(addons, addons.contains("stremio 1.0.1\n  repository: github-com-x-y\n  enabled: true\n  state: idle\n  titles: 40, held: 2\n  last sync: 2026-11-10T16:32:42Z\n"))
        assertTrue(addons, addons.contains("internet-archive 1.0.0\n  repository: built into the app\n  enabled: false"))
        assertTrue(addons, addons.contains("error: fetch https://torrentio.example/realdebrid=[hidden]/manifest.json failed"))
        assertFalse(bundle.toString().contains("SECRETKEY"))
        assertTrue(bundle.files[1].text.endsWith("GET https://a.example/[hidden]/manifest.json"))
        assertEquals("The addon host is not running.", addonsReport(null, ""))
    }

    @Test fun sendPostsGzippedJsonAndReadsTheCode() {
        server.enqueue(MockResponse().setResponseCode(201)
            .setBody("""{"code":"K7QM3XPD","display":"K7QM-3XPD","expires_at":"2026-11-18T10:00:02Z","ttl_days":7}"""))
        val receipt = client.send(bundle())
        assertEquals(SupportReceipt("K7QM3XPD", "K7QM-3XPD", "2026-11-18T10:00:02Z", 7), receipt)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/bundles", request.path)
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        assertEquals("rex-android", request.getHeader("X-Support-App"))
        assertEquals("1.2.3", request.getHeader("X-Support-Version"))
        val sent = Json.parseToJsonElement(GZIPInputStream(request.body.inputStream()).readBytes().decodeToString()).jsonObject
        assertEquals(1, sent["schema"]!!.jsonPrimitive.int)
        assertEquals("rex-android", sent["app"]!!.jsonPrimitive.content)
        assertEquals("1.2.3", sent["version"]!!.jsonPrimitive.content)
        assertEquals("2026-11-11T10:00:02Z", sent["created_at"]!!.jsonPrimitive.content)
        // Labels keep their order: support sees them as a table in it.
        assertEquals(listOf("App", "Port"), sent["info"]!!.jsonObject.keys.toList())
        assertEquals(listOf("addons.txt", "current-run.log", "previous-run.log"),
            sent["files"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("", sent["files"]!!.jsonArray[2].jsonObject["text"]!!.jsonPrimitive.content)
    }

    private fun failure(response: MockResponse): String {
        server.enqueue(response)
        return assertThrows(SupportFailure::class.java) { client.send(bundle()) }.message!!
    }

    @Test fun eachRefusalSaysWhatHappened() {
        assertEquals("The support server turned them down (400). It said: bundle is not valid JSON",
            failure(MockResponse().setResponseCode(400).setBody("""{"error":"bundle is not valid JSON"}""")))
        assertEquals("The support server turned them down (411). It said: length required",
            failure(MockResponse().setResponseCode(411).setBody("""{"error":"length required"}""")))
        assertEquals("These logs are too big to send. It said: bundle over 25 MB",
            failure(MockResponse().setResponseCode(413).setBody("""{"error":"bundle over 25 MB"}""")))
        assertEquals("Too many sends for now. Try again in 10 minutes.",
            failure(MockResponse().setResponseCode(429).setHeader("Retry-After", "600").setBody("""{"error":"slow down"}""")))
        assertEquals("Too many sends for now. Try again later.", failure(MockResponse().setResponseCode(429)))
        assertEquals("The support server turned them down (502).", failure(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>")))
        assertEquals("The support server sent an answer this app can't read.", failure(MockResponse().setResponseCode(201).setBody("ok")))
    }

    @Test fun anUnreachableServerSaysSo() {
        server.shutdown()
        val message = assertThrows(SupportFailure::class.java) { client.send(bundle()) }.message!!
        assertTrue(message, message.startsWith("Can't reach the support server."))
    }

    @Test fun retryAfterReadsAsTime() {
        assertEquals("in a minute", retryIn(30))
        assertEquals("in 2 minutes", retryIn(91))
        assertEquals("in 2 hours", retryIn(7200))
    }
}
