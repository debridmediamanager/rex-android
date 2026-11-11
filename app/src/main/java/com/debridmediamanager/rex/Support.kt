package com.debridmediamanager.rex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

const val SUPPORT_URL = "https://logs.debridmediamanager.com"

@Serializable data class BundleFile(val name: String, val text: String)

/** What "Send logs to support" sends, exactly as the preview shows it. */
@Serializable data class SupportBundle(
    val schema: Int = 1,
    val app: String = "rex-android",
    val version: String,
    @SerialName("created_at") val createdAt: String,
    /** Label to value, shown to support as a table in this order. */
    val info: Map<String, String>,
    val files: List<BundleFile>,
)

/** The server's answer: the code the person posts, in [display] form, and how long it is kept. */
@Serializable data class SupportReceipt(
    val code: String, val display: String = code,
    @SerialName("expires_at") val expiresAt: String = "", @SerialName("ttl_days") val ttlDays: Int = 7,
)

class SupportFailure(message: String) : IOException(message)

/**
 * A support bundle: [info] (label to value, in order), what each addon is doing, and this run's and
 * the previous run's logs, redacted again with today's rules. Nothing else: no settings values, no
 * tokens, no Plex account.
 */
fun supportBundle(version: String, info: Map<String, String>, host: HostStatus?, hostDetail: String,
    currentRun: String, previousRun: String, now: Instant = Instant.now()): SupportBundle {
    fun clean(text: String) = text.lines().joinToString("\n", transform = ::redact)
    return SupportBundle(version = version, createdAt = now.truncatedTo(ChronoUnit.SECONDS).toString(), info = info,
        files = listOf(
            BundleFile("addons.txt", addonsReport(host, hostDetail)),
            BundleFile("current-run.log", clean(currentRun)),
            BundleFile("previous-run.log", clean(previousRun)),
        ))
}

/** Each addon as the host's status reports it, one block per addon. */
fun addonsReport(host: HostStatus?, detail: String): String {
    if (host == null) return detail.ifBlank { "The addon host is not running." }
    if (host.addons.isEmpty()) return "No addons installed."
    return host.addons.joinToString("\n\n") { addon ->
        listOfNotNull(
            "${addon.id} ${addon.version}".trim(),
            "  repository: ${addon.repo ?: "built into the app"}",
            "  enabled: ${addon.enabled}",
            "  state: ${addon.state}",
            "  titles: ${addon.items}, held: ${addon.held}",
            "  last sync: ${addon.lastSync?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() } ?: "never"}",
            // Whether, never who, and never the code.
            "  signed in: ${addon.signIn?.let { if (it.account != null) "yes" else "no" } ?: "not needed"}",
            addon.signIn?.let { "  sign-in kind: ${it.kind ?: "code"}" },
            addon.requires.takeIf { it.isNotEmpty() }?.joinToString(", ", "  requires: ") {
                "${it.id} " + if (it.ok) "met" else "not met (${it.reason ?: "no reason given"})"
            },
            addon.error?.takeIf { it.isNotBlank() }?.let { "  error: ${redact(it)}" + (addon.errorKind?.let { kind -> " ($kind)" } ?: "") },
        ).joinToString("\n")
    }
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The support server's upload. [send] blocks: run it off the main thread. */
class SupportClient(
    private val base: String = SUPPORT_URL,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build(),
) {
    fun send(bundle: SupportBundle): SupportReceipt {
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json.encodeToString(SupportBundle.serializer(), bundle).toByteArray()) }
        }.toByteArray()
        val request = Request.Builder().url("${base.removeSuffix("/")}/v1/bundles")
            .post(gzipped.toRequestBody("application/json".toMediaType()))
            .header("Content-Encoding", "gzip")
            .header("X-Support-App", bundle.app)
            .header("X-Support-Version", appVersion)
            .build()
        val response = try { http.newCall(request).execute() }
            catch (e: IOException) { throw SupportFailure("Can't reach the support server. Check the connection and try again. (${e.message ?: e.javaClass.simpleName})") }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.code == 201) return runCatching { json.decodeFromString(SupportReceipt.serializer(), text) }
                .getOrElse { throw SupportFailure("The support server sent an answer this app can't read.") }
            val said = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val because = said?.let { reason -> " It said: $reason" }.orEmpty()
            throw SupportFailure(when (it.code) {
                429 -> "Too many sends for now. Try again ${it.header("Retry-After")?.toLongOrNull()?.let(::retryIn) ?: "later"}."
                413 -> "These logs are too big to send.$because"
                else -> "The support server turned them down (${it.code}).$because"
            })
        }
    }
}

/** Retry-After seconds as "in 5 minutes". */
fun retryIn(seconds: Long): String = when {
    seconds < 90 -> "in a minute"
    seconds < 90 * 60 -> "in ${(seconds + 59) / 60} minutes"
    else -> "in ${(seconds + 3599) / 3600} hours"
}
