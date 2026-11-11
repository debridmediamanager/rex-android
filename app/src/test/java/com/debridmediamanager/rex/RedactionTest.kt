package com.debridmediamanager.rex

import org.junit.Assert.*
import org.junit.Test

class RedactionTest {
    @Test fun plexTokensInUrlsAreHidden() {
        assertEquals("GET /library/sections?X-Plex-Token=[hidden]&type=1",
            redact("GET /library/sections?X-Plex-Token=abcDEF123xyz&type=1"))
        assertEquals("POST http://127.0.0.1:32400/myplex/claim?token=[hidden]",
            redact("POST http://127.0.0.1:32400/myplex/claim?token=claim-AbC123"))
    }

    @Test fun addonKeysAndPasswordsAreHidden() {
        assertEquals("fetch https://api.example.org/search?q=cats&api_key=[hidden]",
            redact("fetch https://api.example.org/search?q=cats&api_key=0123456789abcdef"))
        assertEquals("set apikey=[hidden] password=[hidden]", redact("set apikey=s3cr3t password=hunter2"))
        assertEquals("""config {"token":"[hidden]","name":"x"}""", redact("""config {"token":"tok-123","name":"x"}"""))
        assertEquals("""saved {"api_key": "[hidden]"}""", redact("""saved {"api_key": "k-999"}"""))
    }

    @Test fun headersAndUrlCredentialsAreHidden() {
        assertEquals("Authorization: Bearer [hidden]", redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc"))
        assertEquals("X-Plex-Token: [hidden]", redact("X-Plex-Token: zzz111"))
        assertEquals("source at https://[hidden]@media.example.org/dav", redact("source at https://user:pa55@media.example.org/dav"))
    }

    @Test fun keysInsideUrlPathsAreHidden() {
        assertEquals("GET https://torrentio.example/realdebrid=[hidden]/manifest.json",
            redact("GET https://torrentio.example/realdebrid=KEY/manifest.json"))
        assertEquals("open https://addon.example/providers=[hidden]/stream/movie/tt0111161.json",
            redact("open https://addon.example/providers=yts,eztv|realdebrid=ABCDEF123/stream/movie/tt0111161.json"))
        // A base64 config segment, with and without its padding, and a percent-encoded one.
        assertEquals("https://addon.example/[hidden]/manifest.json",
            redact("https://addon.example/eyJ0b2tlbiI6ImFiYzEyMyIsInNlcnZpY2UiOiJyZCJ9/manifest.json"))
        assertEquals("https://plex.example/session/[hidden]", redact("https://plex.example/session/550e8400-e29b-41d4-a716-446655440000"))
        assertEquals("https://addon.example/[hidden]/catalog.json", redact("https://addon.example/eyJ0b2tlbiI6ImFiYzEyMyJ9==/catalog.json"))
        assertEquals("https://addon.example/[hidden]/manifest.json",
            redact("https://addon.example/%7B%22token%22%3A%22abc123%22%7D/manifest.json"))
        // The query is still handled by the parameter rule.
        assertEquals("https://cdn.example/dl/[hidden]/Some.Film.2020.mkv?token=[hidden]",
            redact("https://cdn.example/dl/AbCdEfGhIjKlMnOpQrStUvWxYz012345/Some.Film.2020.mkv?token=t0k"))
    }

    @Test fun fileNamesAndShortSegmentsStayReadable() {
        listOf(
            "GET https://archive.org/download/night_of_the_living_dead/Night.of.the.Living.Dead.1968.mp4",
            "fetch https://example.org/films/Some%20Film%20(1950).mkv",
            "https://real-debrid.com/d/ABCDEFGHIJKLM",
            "https://example.org/watch/the-shawshank-redemption-1994/Night_of_the_Living_Dead_1968",
            "rex: GET http://127.0.0.1:32400/library/metadata/42?includeExtras=1",
        ).forEach { assertEquals(it, redact(it)) }
    }

    @Test fun publicAddressesLoseTheirLastTwoParts() {
        assertEquals("rex-direct points at 203.0.x.x:32400", redact("rex-direct points at 203.0.113.45:32400"))
        assertEquals("from 8.8.x.x and 95.91.x.x", redact("from 8.8.8.8 and 95.91.209.7"))
        listOf("192.168.1.20", "10.0.0.5", "172.16.4.2", "172.31.255.1", "127.0.0.1", "100.85.222.1", "169.254.1.1",
            "0.0.0.0", "239.255.255.250").forEach { assertEquals("at $it:1900", redact("at $it:1900")) }
        // Versions and things that are no address stay as they are.
        listOf("Chrome/120.0.6099.109", "v1.2.3.4", "999.1.1.1", "1.2.3.4.5").forEach { assertEquals(it, redact(it)) }
    }

    @Test fun theAddonHostsTokenNeverReachesTheLogs() {
        val token = AddonsToken.fresh()
        Logs.add("addons", "environment REX_ADDONS_TOKEN=$token")
        assertFalse(Logs.all().last().contains(token))
    }

    @Test fun ordinaryLinesStayAsTheyAre() {
        val lines = listOf(
            "rex: listening on 0.0.0.0:32400",
            "GET /library/metadata/42?includeExtras=1 200",
            "monkey=banana keyboard: ready",
            "transcoder: h264_mediacodec encode=true",
        )
        lines.forEach { assertEquals(it, redact(it)) }
    }

    @Test fun logsKeepTheLastLinesRedacted() {
        repeat(510) { Logs.add("rex", "line $it token=t$it") }
        val lines = Logs.all()
        assertEquals(500, lines.size)
        assertEquals("rex: line 509 token=[hidden]", lines.last())
        assertTrue(lines.none { it.contains("token=t") })
    }
}
