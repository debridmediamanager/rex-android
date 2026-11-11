package com.debridmediamanager.rex

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aRestartKeepsTheLastRunAsThePreviousOne() {
        val directory = folder.newFolder("logs")
        LogStore(directory).apply { start(); add("first run 1"); add("first run 2") }.read(previous = false)
        val second = LogStore(directory).apply { start() }
        assertEquals("first run 1\nfirst run 2", second.read(previous = true))
        assertEquals("", second.read(previous = false))
        second.add("second run")
        assertEquals("second run", second.read(previous = false))
        // A run that said nothing leaves the previous one in place.
        val third = LogStore(directory).apply { start() }
        assertEquals("second run", third.read(previous = true))
        assertEquals("second run", LogStore(directory).apply { start() }.read(previous = true))
    }

    @Test fun aRunKeepsItsNewestLinesWithinBounds() {
        val directory = folder.newFolder("logs")
        val store = LogStore(directory, maxLines = 100, maxBytes = 1_000_000).apply { start() }
        repeat(1_000) { store.add("line $it") }
        val kept = store.read(previous = false).lines()
        assertEquals(100, kept.size)
        assertEquals("line 999", kept.last())
        // Two halves of at most 50 lines on disk, not every line ever written.
        assertTrue(directory.listFiles()!!.sumOf { it.readLines().size } <= 100)

        val bytes = LogStore(folder.newFolder("bytes"), maxLines = 10_000, maxBytes = 1_000).apply { start() }
        repeat(500) { bytes.add("x".repeat(20) + it) }
        val text = bytes.read(previous = false)
        assertTrue(text.length <= 1_000)
        assertTrue(text.endsWith("x".repeat(20) + 499))
    }
}
