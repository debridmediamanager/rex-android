package com.debridmediamanager.rex

/** The last lines rex, the addon host and the app said, with secrets hidden as they arrive. */
object Logs {
    private val lines = ArrayDeque<String>()
    @Synchronized fun add(source: String, line: String) {
        val token = AddonsToken.current
        lines.addLast("$source: ${redact(if (token == null) line else line.replace(token, HIDDEN))}")
        while (lines.size > 500) lines.removeFirst()
    }
    @Synchronized fun all(): List<String> = lines.toList()
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
