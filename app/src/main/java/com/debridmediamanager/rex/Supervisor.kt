package com.debridmediamanager.rex

import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** How long to wait before starting a crashed process again. */
class Backoff(private val initial: Long = 1_000, private val max: Long = 60_000, private val stable: Long = 60_000) {
    private var next = initial

    /** [ranFor] is how long the process lived: one that ran for a while starts again quickly. */
    fun after(ranFor: Long): Long {
        if (ranFor >= stable) next = initial
        return next.also { next = (next * 2).coerceAtMost(max) }
    }
}

/**
 * Starts a process, waits for it to exit and starts it again after [backoff], until cancelled.
 * [whileRunning] runs beside each process (status polls) and is cancelled when it exits.
 * Cancelling stops the process: SIGTERM, then whatever [kill] does if it is still up after 5 s.
 */
suspend fun supervise(
    start: () -> Process,
    onOutput: (String) -> Unit,
    onExit: (reason: String, retryIn: Long) -> Unit,
    whileRunning: suspend (Process) -> Unit = {},
    kill: (Process) -> Unit = { it.destroyForcibly() },
    backoff: Backoff = Backoff(),
    clock: () -> Long = System::currentTimeMillis,
    io: CoroutineDispatcher = Dispatchers.IO,
): Nothing {
    while (true) {
        val started = clock()
        val reason = try {
            val running = withContext(io) { start() }
            coroutineScope {
                val reader = launch(io) { readProcessOutput(running.inputStream, onOutput) }
                val watcher = launch { whileRunning(running) }
                try {
                    val code = runInterruptible(io) { running.waitFor() }
                    watcher.cancel()
                    reader.join()
                    "exited with code $code"
                } finally {
                    // Inside the scope: the reader blocks on the process's output until the process
                    // ends, and the scope waits for the reader, so the process has to stop first.
                    withContext(NonCancellable + io) { stop(running, kill) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.message ?: e.javaClass.simpleName
        }
        val wait = backoff.after(clock() - started)
        onExit(reason, wait)
        delay(wait)
    }
}

private fun stop(process: Process, kill: (Process) -> Unit) {
    process.destroy()
    // Android's destroyForcibly() is destroy() again, one more SIGTERM, so a process that stays
    // up after the first one needs the caller's SIGKILL.
    if (!process.waitFor(5, TimeUnit.SECONDS)) kill(process)
}

internal fun readProcessOutput(input: InputStream, onLine: (String) -> Unit) {
    try {
        input.bufferedReader().useLines { lines -> lines.forEach(onLine) }
    } catch (_: IOException) {
        // Android can close the process pipe while its reader is blocked in read(). The caller
        // owns waitFor and exit handling; this is the end of captured output, not a failure.
    }
}

/** True when something on this device already listens on [port], on any address. */
fun portInUse(port: Int): Boolean {
    try {
        // rex binds with SO_REUSEADDR too, so a closed connection in TIME_WAIT is not a conflict.
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(port)) }
    } catch (_: IOException) { return true }
    // BSD lets that bind sit beside a listener on loopback only; a connection finds one.
    return try {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500) }
        true
    } catch (_: IOException) { false }
}

/** A port rex can listen on, or null. Ports below 1024 need root on Android. */
fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1024..65535 }
