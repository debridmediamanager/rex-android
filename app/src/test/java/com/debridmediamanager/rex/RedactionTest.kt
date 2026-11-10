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
