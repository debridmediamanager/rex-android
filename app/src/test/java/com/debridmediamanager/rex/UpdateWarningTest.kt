package com.debridmediamanager.rex

import org.junit.Assert.*
import org.junit.Test

/** When an update has to ask first, and what it says. */
class UpdateWarningTest {
    private val archive = listOf("archive.org", "*.archive.org")

    @Test fun theSameSitesFromTheSameRepositoryNeedNoQuestion() {
        assertNull(updateWarning(archive, archive.reversed(), "rex-addons", "rex-addons", "rex addons"))
    }

    @Test fun aNewSiteIsNamed() {
        assertEquals("It comes from rex addons. It can reach archive.org, *.archive.org, cdn.example.org, " +
            "new: cdn.example.org. Update only from people you trust.",
            updateWarning(archive, archive + "cdn.example.org", "rex-addons", "rex-addons", "rex addons"))
        assertEquals("It comes from rex addons. It can reach any website, new: any website. Update only from people you trust.",
            updateWarning(archive, listOf("*"), "rex-addons", "rex-addons", "rex addons"))
    }

    @Test fun fewerSitesStillAsk() {
        assertEquals("It comes from rex addons. It can reach archive.org. Update only from people you trust.",
            updateWarning(archive, listOf("archive.org"), "rex-addons", "rex-addons", "rex addons"))
    }

    @Test fun anotherSourceAsksEvenWithTheSameSites() {
        assertEquals("It now comes from Mine instead of rex-addons. It can reach archive.org, *.archive.org. " +
            "Update only from people you trust.", updateWarning(archive, archive, "rex-addons", "mine", "Mine"))
        // A repository taking over the copy built into the app.
        assertEquals("It now comes from Mine instead of the copy built into the app. It can reach archive.org, *.archive.org. " +
            "Update only from people you trust.", updateWarning(archive, archive, null, "mine", "Mine"))
    }

    @Test fun unknownSitesAsk() {
        assertEquals("It comes from rex addons. It doesn't say which websites it can reach. Update only from people you trust.",
            updateWarning(archive, null, "rex-addons", "rex-addons", "rex addons"))
        assertEquals("It comes from rex addons. It can reach archive.org, *.archive.org. Update only from people you trust.",
            updateWarning(null, archive, "rex-addons", "rex-addons", "rex addons"))
    }

    // Host 0.7: an update that adds an address setting asks first, even with the same sites.
    @Test fun aNewAddressSettingAsks() {
        assertEquals("It comes from rex addons. It can reach archive.org, *.archive.org. It asks for an address (Server) " +
            "and can reach whatever you type there. Update only from people you trust.",
            updateWarning(archive, archive, "rex-addons", "rex-addons", "rex addons", listOf("Server")))
        assertNull(updateWarning(archive, archive, "rex-addons", "rex-addons", "rex addons", emptyList()))
    }

    @Test fun reachNamesWhatThePersonAllowed() {
        assertEquals("Can reach archive.org, *.archive.org.", reachLine(archive, emptyList()))
        assertEquals("Can reach only the address you type below.", reachLine(emptyList(), emptyList(), asks = true))
        assertEquals("Can reach only what you allowed: 192.168.1.20:8096.", reachLine(emptyList(), listOf("192.168.1.20:8096")))
        assertEquals("Can reach any website, and what you allowed: nas.local:80, nas.local:443.",
            reachLine(listOf("*"), listOf("nas.local:80", "nas.local:443")))
    }

    @Test fun leftOutEntriesAreAQuietCount() {
        assertNull(leftOut(Warnings()))
        assertEquals("3 left out", leftOut(Warnings(3, listOf("a"))))
    }
}
