package com.debridmediamanager.rex

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A process that exits with [code] at once, or with a negative code runs until destroyed (or never, if [stubborn]). */
private class FakeProcess(private val code: Int, private val output: String = "", private val stubborn: Boolean = false) : Process() {
    @Volatile var destroyed = false
    @Volatile var started = false
    private val exit = CountDownLatch(if (code >= 0) 0 else 1)
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    // Like a real pipe, a running process's output ends only when the process does.
    override fun getInputStream(): InputStream {
        started = true
        return if (code >= 0) output.byteInputStream() else object : InputStream() { override fun read(): Int { exit.await(); return -1 } }
    }
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun waitFor(): Int { exit.await(); return code }
    override fun waitFor(timeout: Long, unit: TimeUnit) = exit.await(timeout, unit)
    override fun exitValue(): Int = if (exit.count == 0L) code else throw IllegalThreadStateException()
    override fun destroy() { destroyed = true; if (!stubborn) exit.countDown() }
    fun kill() = exit.countDown()
}

class SupervisorTest {
    @Test fun backoffDoublesUpToItsCapAndResetsAfterAStableRun() {
        val backoff = Backoff(initial = 1_000, max = 8_000, stable = 60_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), List(5) { backoff.after(10) })
        assertEquals("a process that ran a minute starts again quickly", 1_000L, backoff.after(60_000))
        assertEquals(2_000L, backoff.after(0))
    }

    @Test fun anExitedProcessStartsAgainAfterGrowingWaits() = runTest {
        val waits = mutableListOf<Long>()
        val reasons = mutableListOf<String>()
        val output = mutableListOf<String>()
        var starts = 0
        val job = launch {
            supervise(start = { starts++; FakeProcess(3, "line $starts\n") }, onOutput = { output += it },
                onExit = { reason, wait -> reasons += reason; waits += wait },
                io = StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime })
        }
        advanceTimeBy(1_000 + 2_000 + 4_000 + 1)
        job.cancelAndJoin()
        assertEquals(4, starts)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), waits)
        assertEquals("exited with code 3", reasons.first())
        assertEquals(listOf("line 1", "line 2", "line 3", "line 4"), output)
    }

    @Test fun aProcessThatCannotStartIsRetriedWithItsReason() = runTest {
        val reasons = mutableListOf<String>()
        val job = launch {
            supervise(start = { error("permission denied") }, onOutput = {}, onExit = { reason, _ -> reasons += reason },
                io = StandardTestDispatcher(testScheduler), clock = { testScheduler.currentTime })
        }
        advanceTimeBy(1_001)
        job.cancelAndJoin()
        assertEquals(listOf("permission denied", "permission denied"), reasons)
    }

    @Test fun cancellingStopsTheRunningProcess() = runBlocking {
        val process = FakeProcess(-1)
        val job = CoroutineScope(Dispatchers.Default).launch { supervise(start = { process }, onOutput = {}, onExit = { _, _ -> }) }
        withTimeout(5_000) { while (!process.started) delay(10) }
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(process.destroyed)
    }

    @Test fun aProcessThatIgnoresSigtermIsKilled() = runBlocking {
        val process = FakeProcess(-1, stubborn = true)
        var killed = false
        val job = CoroutineScope(Dispatchers.Default).launch {
            supervise(start = { process }, onOutput = {}, onExit = { _, _ -> }, kill = { killed = true; process.kill() })
        }
        withTimeout(5_000) { while (!process.started) delay(10) }
        withTimeout(10_000) { job.cancelAndJoin() }
        assertTrue(process.destroyed)
        assertTrue("SIGTERM was ignored for 5 s, so the supervisor kills it", killed)
    }

    @Test fun aListeningPortIsInUse() {
        ServerSocket().use { listener ->
            listener.bind(InetSocketAddress(0))
            assertTrue(portInUse(listener.localPort))
        }
        val free = ServerSocket(0).use { it.localPort }
        assertFalse(portInUse(free))
    }

    @Test fun aPortOnlyOnLoopbackIsInUseToo() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { listener ->
            assertTrue("rex binds every address, so a loopback listener blocks it", portInUse(listener.localPort))
        }
    }

    @Test fun portsAreNumbersAboveTheSystemRange() {
        assertEquals(32500, parsePort(" 32500 "))
        assertNull(parsePort("80"))
        assertNull(parsePort("70000"))
        assertNull(parsePort("plex"))
    }
}
