package com.debridmediamanager.rex

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val PRODUCT = "rex for Android"
private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

/** rex's own answer about itself: whether a Plex account has claimed it. Throws while rex is not up. */
fun rexClaimed(rexUrl: String): Boolean = Regex("""claimed="(\d)"""").find(rexIdentity(rexUrl))?.groupValues?.get(1) == "1"

/** The version rex reports, as any Plex server does. Throws while rex is not up. */
fun rexVersion(rexUrl: String): String? = Regex("""\bversion="([^"]+)"""").find(rexIdentity(rexUrl))?.groupValues?.get(1)

/** The server token rex keeps once claimed, which only this app can read; null before that. */
fun rexToken(rexDirectory: java.io.File): String? = runCatching {
    Json.parseToJsonElement(java.io.File(rexDirectory, "claim_state.json").readText()).jsonObject["token"]?.jsonPrimitive?.contentOrNull
}.getOrNull()

/** The name rex goes by now: a Plex app's Friendly name setting changes it after the first start. */
fun rexName(rexUrl: String, token: String?): String? {
    if (token == null) return null
    return http.newCall(Request.Builder().url("$rexUrl/").header("Accept", "application/json").header("X-Plex-Token", token).build()).execute().use {
        if (!it.isSuccessful) throw IOException("rex answered ${it.code}")
        Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject["MediaContainer"]?.jsonObject?.get("friendlyName")?.jsonPrimitive?.contentOrNull
    }
}

private fun rexIdentity(rexUrl: String): String =
    http.newCall(Request.Builder().url("$rexUrl/identity").build()).execute().use {
        if (!it.isSuccessful) throw IOException("rex answered ${it.code}")
        it.body?.string().orEmpty()
    }

/**
 * Plex's PIN sign-in, used once to claim rex. The account token it yields asks plex.tv for a
 * claim code and is then dropped: rex keeps only the server token the claim gives it.
 */
class PlexSignIn(private val runtime: Runtime) {
    data class Pin(val id: Long, val code: String)

    private val clientId: String get() = runtime.preferences.getString("plex_client_id", null)
        ?: UUID.randomUUID().toString().also { runtime.preferences.edit().putString("plex_client_id", it).apply() }

    private fun request(url: String) = Request.Builder().url(url).header("Accept", "application/json")
        .header("X-Plex-Product", PRODUCT).header("X-Plex-Client-Identifier", clientId)
        .header("X-Plex-Device-Name", runtime.serverName)

    private fun json(builder: Request.Builder): JsonObject = http.newCall(builder.build()).execute().use {
        if (!it.isSuccessful) throw IOException("plex.tv answered ${it.code}")
        Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
    }

    /** A four-character code for plex.tv/link. */
    fun pin(): Pin {
        val answer = json(request("https://plex.tv/api/v2/pins").post("".toRequestBody()))
        return Pin(answer["id"]!!.jsonPrimitive.long, answer["code"]!!.jsonPrimitive.content)
    }

    /** The page that signs in with the code already filled in, for a device with a browser. */
    fun authUrl(pin: Pin): String = "https://app.plex.tv/auth#?clientID=$clientId&code=${pin.code}" +
        "&context%5Bdevice%5D%5Bproduct%5D=${PRODUCT.replace(" ", "%20")}"

    /** Waits until the PIN is linked, then claims the local rex with that account. */
    suspend fun claimWhenLinked(pin: Pin, timeoutMillis: Long = 10 * 60_000): String {
        val until = System.currentTimeMillis() + timeoutMillis
        var token: String? = null
        while (token == null) {
            check(System.currentTimeMillis() < until) { "The Plex code expired. Ask for a new one." }
            delay(2_000)
            token = json(request("https://plex.tv/api/v2/pins/${pin.id}"))["authToken"]
                ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        val claim = json(request("https://plex.tv/api/claim/token.json").header("X-Plex-Token", token))["token"]!!.jsonPrimitive.content
        val url = "${runtime.rexUrl}/myplex/claim".toHttpUrl().newBuilder().addQueryParameter("token", claim).build()
        http.newCall(Request.Builder().url(url).post("".toRequestBody()).build()).execute().use {
            if (!it.isSuccessful) throw IOException("rex refused the claim (${it.code})")
        }
        return "Signed in. Open a Plex app and pick ${runtime.serverName}."
    }
}

/**
 * The sign-in in progress. It lives outside any screen: the person leaves the app for the browser,
 * and a rotated or recreated screen must not end the wait for the claim.
 */
object PlexClaim {
    val code = MutableStateFlow("")
    val outcome = MutableStateFlow("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Asks plex.tv for a code, waits for it in the background and returns the page that signs in with it. */
    fun begin(runtime: Runtime): String {
        val signIn = PlexSignIn(runtime)
        val pin = signIn.pin()
        code.value = pin.code
        outcome.value = ""
        scope.launch {
            outcome.value = try { signIn.claimWhenLinked(pin) }
                catch (e: Exception) { if (e is CancellationException) throw e; e.message ?: "The sign-in did not finish. Try again." }
            code.value = ""
        }
        return signIn.authUrl(pin)
    }
}
