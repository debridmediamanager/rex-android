package com.debridmediamanager.rex

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The last lines rex, the addon host and the app said, with secrets hidden as they arrive. Once
 * [persistTo] is called they also go to app storage, so a crash or a reboot keeps them.
 */
object Logs {
    private val lines = ArrayDeque<String>()
    @Volatile private var store: LogStore? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized fun add(source: String, line: String) {
        val token = AddonsToken.current
        val text = "${stamp.format(Date())} $source: ${redact(if (token == null) line else line.replace(token, HIDDEN))}"
        lines.addLast(text)
        while (lines.size > 500) lines.removeFirst()
        store?.add(text)
    }
    @Synchronized fun all(): List<String> = lines.toList()

    /** Keeps this run's lines in [directory] from now on; the last run's become the previous run. */
    @Synchronized fun persistTo(directory: File) { if (store == null) store = LogStore(directory).also { it.start() } }

    /** This run's lines, or the previous run's, as kept on disk ("" before [persistTo]). */
    fun run(previous: Boolean): String = store?.read(previous).orEmpty()
}

/**
 * This run's and the previous run's log lines in app storage, each up to about [maxLines] lines
 * or [maxBytes] bytes, newest kept. Lines are written by a thread of its own, a burst at a time,
 * so callers never wait on storage and flash is not written line by line.
 */
class LogStore(private val directory: File, private val maxLines: Int = 2_000, private val maxBytes: Long = 1_000_000) {
    // A run is two halves: when the current half is full it becomes the older one.
    private fun file(run: String, half: Int) = File(directory, if (half == 0) "$run.log" else "$run.1.log")
    // Lines, and the latches read() waits on until every line queued before it is written.
    private val queue = LinkedBlockingQueue<Any>(10_000)
    private var writer: Writer? = null
    private var lines = 0
    private var bytes = 0L

    /** Moves the last run's lines aside as the previous run, then writes this run's as they come. */
    fun start() {
        rotate()
        thread(isDaemon = true, name = "rex-logs") {
            while (true) {
                val batch = mutableListOf(queue.take()).also { queue.drainTo(it) }
                synchronized(this) { write(batch.filterIsInstance<String>()) }
                batch.filterIsInstance<CountDownLatch>().forEach(CountDownLatch::countDown)
            }
        }
    }

    /** Queues a line; dropped only when storage falls 10,000 lines behind. */
    fun add(line: String) { queue.offer(line) }

    @Synchronized fun rotate() {
        directory.mkdirs()
        // A run that wrote nothing (the app opened and closed) leaves the previous one in place.
        if (file("current", 0).length() + file("current", 1).length() == 0L) return
        for (half in 0..1) file("previous", half).delete()
        for (half in 0..1) file("current", half).takeIf { it.exists() }?.renameTo(file("previous", half))
    }

    /** Writes a burst of lines, then flushes once. */
    private fun write(batch: List<String>) {
        if (batch.isEmpty()) return
        runCatching {
            for (line in batch) {
                val size = line.toByteArray().size + 1L
                if (writer == null || lines >= maxLines / 2 || bytes + size > maxBytes / 2) roll()
                writer!!.write(line); writer!!.write("\n")
                lines++; bytes += size
            }
            writer?.flush()
        }.onFailure { writer = null }
    }

    private fun roll() {
        runCatching { writer?.close() }
        val current = file("current", 0)
        if (current.length() > 0) { file("current", 1).delete(); current.renameTo(file("current", 1)) }
        directory.mkdirs()
        writer = BufferedWriter(FileWriter(current, true), 16 * 1024)
        lines = 0; bytes = 0
    }

    /** A run's lines, oldest first, cut to the newest [maxLines] and [maxBytes]. */
    fun read(previous: Boolean): String {
        if (!previous) CountDownLatch(1).also { queue.put(it) }.await(5, TimeUnit.SECONDS)
        return synchronized(this) { readRun(previous) }
    }

    private fun readRun(previous: Boolean): String {
        val run = if (previous) "previous" else "current"
        val all = (1 downTo 0).flatMap { half -> file(run, half).takeIf { it.exists() }?.readLines().orEmpty() }
        val kept = ArrayDeque<String>()
        var size = 0L
        for (line in all.asReversed()) {
            size += line.toByteArray().size + 1
            if (kept.size >= maxLines || size > maxBytes) break
            kept.addFirst(line)
        }
        return kept.joinToString("\n")
    }
}

private const val HIDDEN = "[hidden]"
private val secretParameter = Regex(
    """(?i)(?<=[?&;\s]|^)((?:x-plex-)?token|access_token|api_?key|apikey|key|secret|password|passwd|auth|signature|sig)=([^&\s"'<>]+)""")
private val secretHeader = Regex("""(?i)\b(authorization:\s*(?:bearer|basic)\s+|x-plex-token:\s*)\S+""")
private val bearer = Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]{8,}""")
private val credentialsInUrl = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^/\s:@]+:[^/\s@]+@""")
private val secretJson = Regex("""(?i)("(?:[a-z_]*token|[a-z_]*key|[a-z_]*secret|password)"\s*:\s*")[^"]+(")""")
// A URL's path, up to its query or fragment: Stremio addons and the like put keys in it.
private val urlPath = Regex("""(?i)\b([a-z][a-z0-9+.-]*://[^/\s?#"'<>]+)(/[^\s?#"'<>]*)""")
// `name=value`, where the value is more than base64's `=` padding.
private val namedSegment = Regex("""([^=]+)=(.*[^=].*)""")
// A long run of base64url (or percent-encoded) text; file names have dots or spaces in them.
private val opaqueSegment = Regex("""[A-Za-z0-9_\-=%]{24,}""")
// Words joined by - or _, such as an archive.org identifier or a slug, are names rather than keys.
private val words = Regex("""(?:[A-Za-z]+|\d+)(?:[-_](?:[A-Za-z]+|\d+))+""")
private val ipv4 = Regex("""(?<![\w.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?!\.?\d)""")

private fun hidePath(path: String): String = path.split('/').joinToString("/") { segment ->
    namedSegment.matchEntire(segment)?.let { "${it.groupValues[1]}=$HIDDEN" }
        ?: if (opaqueSegment.matches(segment) && !words.matches(segment)) HIDDEN else segment
}

/** Home and other non-public addresses say nothing about who someone is; a public one does. */
private fun hideAddress(match: MatchResult): String {
    val octets = match.groupValues.drop(1).map(String::toInt)
    if (octets.any { it > 255 }) return match.value
    val (a, b) = octets
    val private = a == 10 || a == 127 || a == 0 || a >= 224 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) || (a == 100 && b in 64..127)
    return if (private) match.value else "$a.$b.x.x"
}

/** Hides tokens, keys and passwords in a log line, Plex's, an addon's or a source's, and public IP addresses. */
fun redact(line: String): String = line
    .replace(secretParameter) { "${it.groupValues[1]}=$HIDDEN" }
    .replace(secretHeader) { "${it.groupValues[1]}$HIDDEN" }
    .replace(bearer) { "${it.groupValues[1]}$HIDDEN" }
    .replace(credentialsInUrl) { "${it.groupValues[1]}$HIDDEN@" }
    .replace(secretJson) { "${it.groupValues[1]}$HIDDEN${it.groupValues[2]}" }
    .replace(urlPath) { it.groupValues[1] + hidePath(it.groupValues[2]) }
    .replace(ipv4, ::hideAddress)
