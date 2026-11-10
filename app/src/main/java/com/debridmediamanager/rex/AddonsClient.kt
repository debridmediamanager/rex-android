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
import java.util.concurrent.TimeUnit

/** The addon host's management API listens here, on loopback only. */
const val ADDONS_PORT = 32410

@Serializable data class HostStatus(val version: String = "", val rex: RexLink = RexLink(), val addons: List<Addon> = emptyList())
@Serializable data class RexLink(val url: String = "", val reachable: Boolean = false)
@Serializable data class Addon(
    val id: String, val name: String = id, val version: String = "", val description: String = "",
    val enabled: Boolean = true, val state: String = "idle", val items: Long = 0, val held: Long = 0,
    @SerialName("last_sync") val lastSync: Long? = null, val error: String? = null,
)
@Serializable data class ConfigField(
    val key: String, val label: String = key, val type: String = "text",
    val default: JsonElement? = null, val required: Boolean = false,
)

/** An addon's settings as its manifest declares them, with what is set now (secrets masked). */
data class AddonSettings(val name: String, val fields: List<ConfigField>, val values: Map<String, JsonElement>)

class ApiFailure(val status: Int, message: String) : IOException(message)

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val jsonType = "application/json".toMediaType()

/** The addon host's management API (rex-addons `--listen`). Every call blocks: run it off the main thread. */
class AddonsClient(
    base: String = "http://127.0.0.1:$ADDONS_PORT",
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val base = base.toHttpUrl()

    fun status(): HostStatus = json.decodeFromString(call("GET", url("api", "status")))

    fun settings(id: String): AddonSettings {
        val answer = json.parseToJsonElement(call("GET", url("api", "addons", id))).jsonObject
        // The manifest as installed declares the fields; "config" holds each one's value now
        // (saved or default, a saved secret masked).
        val manifest = answer["manifest"]?.jsonObject ?: JsonObject(emptyMap())
        return AddonSettings(
            (manifest["name"] as? JsonPrimitive)?.contentOrNull ?: id,
            (manifest["config"] as? JsonArray).orEmpty().map { json.decodeFromJsonElement(ConfigField.serializer(), it) },
            (answer["config"] as? JsonObject).orEmpty().filterValues { it !is JsonNull })
    }

    fun install(manifestUrl: String) { call("POST", url("api", "addons"), buildJsonObject { put("url", manifestUrl) }) }
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

    private fun call(method: String, url: HttpUrl, body: JsonObject? = null): String {
        val request = Request.Builder().url(url).method(method, body?.toString()?.toRequestBody(jsonType)).build()
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
