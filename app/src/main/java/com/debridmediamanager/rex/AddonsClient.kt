package com.debridmediamanager.rex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** The addon host's management API listens here, on loopback only. */
const val ADDONS_PORT = 32410

/**
 * The secret the running addon host demands on every call: a fresh one each time the app starts
 * the host, handed over in its environment only (never its arguments, which other apps can read).
 */
object AddonsToken {
    const val ENV = "REX_ADDONS_TOKEN"
    @Volatile var current: String? = null
        private set
    fun fresh(): String = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }.also { current = it }
}

@Serializable data class HostStatus(val version: String = "", val rex: RexLink = RexLink(), val addons: List<Addon> = emptyList())
@Serializable data class RexLink(val url: String = "", val reachable: Boolean = false)
@Serializable data class Addon(
    val id: String, val name: String = id, val version: String = "", val description: String = "",
    val enabled: Boolean = true, val state: String = "idle", val items: Long = 0, val held: Long = 0,
    @SerialName("last_sync") val lastSync: Long? = null, val error: String? = null,
    /** "would_remove" when rex refused the last sync for removing more than half of the titles. */
    @SerialName("error_kind") val errorKind: String? = null,
    val repo: String? = null, @SerialName("update_available") val updateAvailable: Boolean = false,
    /** The websites the installed copy can reach, and those the offered update could. */
    val hosts: List<String> = emptyList(), @SerialName("update_hosts") val updateHosts: List<String>? = null,
)
/** A git repository addons are installed from. */
@Serializable data class Repo(
    val id: String, val url: String, val name: String = "", val description: String = "", val commit: String? = null,
    @SerialName("last_fetch") val lastFetch: Long? = null, val error: String? = null, val addons: JsonElement? = null,
) {
    /** How many addons it offers ("addons" may be the list or its count). */
    val addonCount: Int get() = (addons as? JsonArray)?.size ?: (addons as? JsonPrimitive)?.intOrNull ?: 0
}
/** An addon a repository offers, and whether this device has it. */
@Serializable data class RepoAddon(
    val id: String, val name: String = id, val version: String = "", val description: String = "",
    val hosts: List<String> = emptyList(), @SerialName("installed_version") val installedVersion: String? = null,
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("installed_hosts") val installedHosts: List<String>? = null,
    /** The repository the installed copy came from; null for the copy built into the app. */
    @SerialName("installed_repo") val installedRepo: String? = null,
    /** False when it needs a newer addon host than this app carries; installing it is refused. */
    val compatible: Boolean = true,
)
@Serializable data class ConfigField(
    val key: String, val label: String = key, val type: String = "text",
    val default: JsonElement? = null, val required: Boolean = false,
)

/**
 * An addon's settings as its manifest declares them, with what is set now (secrets masked), and how
 * many of its titles the person deleted in a Plex app (rex keeps them out until they come back).
 */
data class AddonSettings(val name: String, val fields: List<ConfigField>, val values: Map<String, JsonElement>, val deleted: Long = 0)

class ApiFailure(val status: Int, message: String) : IOException(message)

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val jsonType = "application/json".toMediaType()

/** The addon host's management API (rex-addons `--listen`). Every call blocks: run it off the main thread. */
class AddonsClient(
    base: String = "http://127.0.0.1:$ADDONS_PORT",
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val token: () -> String? = { AddonsToken.current },
) {
    private val base = base.toHttpUrl()
    // Adding a repository answers only after the host cloned it, which takes a slow device a while.
    private val patient by lazy { http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build() }

    fun status(): HostStatus = json.decodeFromString(call("GET", url("api", "status")))

    fun settings(id: String): AddonSettings {
        val answer = json.parseToJsonElement(call("GET", url("api", "addons", id))).jsonObject
        // The manifest as installed declares the fields; "config" holds each one's value now
        // (saved or default, a saved secret masked).
        val manifest = answer["manifest"]?.jsonObject ?: JsonObject(emptyMap())
        return AddonSettings(
            (manifest["name"] as? JsonPrimitive)?.contentOrNull ?: id,
            (manifest["config"] as? JsonArray).orEmpty().map { json.decodeFromJsonElement(ConfigField.serializer(), it) },
            (answer["config"] as? JsonObject).orEmpty().filterValues { it !is JsonNull },
            (answer["deleted"] as? JsonPrimitive)?.longOrNull ?: 0)
    }
    /** Brings back the titles the person deleted in a Plex app, and syncs. */
    fun restore(id: String) { call("POST", url("api", "addons", id, "restore"), JsonObject(emptyMap())) }

    fun repos(): List<Repo> = json.decodeFromString(call("GET", url("api", "repos")))
    /** Adds a repository by its git link; the host answers why when it refuses one. */
    fun addRepo(link: String): Repo = json.decodeFromString(call("POST", url("api", "repos"), buildJsonObject { put("url", link) }, patient))
    /** Its installed addons stay, without further updates. */
    fun removeRepo(id: String) { call("DELETE", url("api", "repos", id)) }
    fun refreshRepo(id: String) { call("POST", url("api", "repos", id, "refresh"), JsonObject(emptyMap())) }
    fun repoAddons(id: String): List<RepoAddon> = json.decodeFromString(call("GET", url("api", "repos", id, "addons")))
    /** Installs an addon from a repository, or updates it when it is installed already. */
    fun install(repo: String, addon: String) {
        call("POST", url("api", "addons"), buildJsonObject { put("repo", repo); put("addon", addon) })
    }
    fun remove(id: String) { call("DELETE", url("api", "addons", id)) }
    fun configure(id: String, values: JsonObject) { call("PUT", url("api", "addons", id, "config"), values) }
    /** [force] lets the sync remove more than half of the addon's titles, which rex otherwise refuses. */
    fun sync(id: String, force: Boolean = false) {
        call("POST", url("api", "addons", id, "sync"), buildJsonObject { if (force) put("force", true) })
    }
    fun enable(id: String, enabled: Boolean) {
        call("POST", url("api", "addons", id, "enabled"), buildJsonObject { put("enabled", enabled) })
    }

    private fun url(vararg segments: String): HttpUrl =
        base.newBuilder().apply { segments.forEach(::addPathSegment) }.build()

    private fun call(method: String, url: HttpUrl, body: JsonObject? = null, http: OkHttpClient = this.http): String {
        val request = Request.Builder().url(url).method(method, body?.toString()?.toRequestBody(jsonType))
            .apply { token()?.let { header("Authorization", "Bearer $it") } }.build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // The host says what went wrong in {"error": "..."}; anything else gets the status.
                val reason = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                throw ApiFailure(response.code, reason ?: "The addon host answered ${response.code}")
            }
            return text
        }
    }
}

/**
 * What a person typed as a repository, as the https link the host clones: a short
 * `github.com/user/repo` or even `user/repo` (GitHub) saves typing it all with a remote.
 */
fun repoLink(typed: String): String? {
    val text = typed.trim().removeSuffix("/")
    if (text.isEmpty() || text.any(Char::isWhitespace)) return null
    return when {
        text.startsWith("https://") -> text
        text.contains("://") -> null
        text.split('/').let { it.size == 2 && '.' !in it[0] && it.all(String::isNotEmpty) } -> "https://github.com/$text"
        '.' in text.substringBefore('/') && '/' in text -> "https://$text"
        else -> null
    }
}
