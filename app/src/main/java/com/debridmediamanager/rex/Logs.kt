package com.debridmediamanager.rex

/** The last lines rex, the addon host and the app said, with secrets hidden as they arrive. */
object Logs {
    private val lines = ArrayDeque<String>()
    @Synchronized fun add(source: String, line: String) {
        lines.addLast("$source: ${redact(line)}")
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

/** Hides tokens, keys and passwords in a log line: Plex's, an addon's or a source's. */
fun redact(line: String): String = line
    .replace(secretParameter) { "${it.groupValues[1]}=$HIDDEN" }
    .replace(secretHeader) { "${it.groupValues[1]}$HIDDEN" }
    .replace(bearer) { "${it.groupValues[1]}$HIDDEN" }
    .replace(credentialsInUrl) { "${it.groupValues[1]}$HIDDEN@" }
    .replace(secretJson) { "${it.groupValues[1]}$HIDDEN${it.groupValues[2]}" }
