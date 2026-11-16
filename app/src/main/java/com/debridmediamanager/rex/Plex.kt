package com.debridmediamanager.rex

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** rex's GET /rex/claim: [refused] means plex.tv answers 401 to the claim's token, so the Plex apps can't use it. */
data class RexClaim(val claimed: Boolean, val refused: Boolean)

/**
 * rex's /rex/claim (rex 80b4eb09 and later), or null from an older rex, which has only /identity. An older rex
 * answers an unknown path 401, claimed or not (measured on a zurg engine 2026-11-16); 404 is what the contract names.
 */
fun rexClaim(rexUrl: String): RexClaim? = http.newCall(Request.Builder().url("$rexUrl/rex/claim").build()).execute().use {
    if (it.code == 404 || it.code == 401) return null
    if (!it.isSuccessful) throw IOException("rex answered ${it.code}")
    val answer = Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
    RexClaim(answer["claimed"]?.jsonPrimitive?.booleanOrNull == true, answer["refused"]?.jsonPrimitive?.booleanOrNull == true)
}

/** What the app shows for a running rex. Throws while rex is not up. A failed /rex/claim read keeps [refused]: only rex says so. */
fun rexState(rexUrl: String, refused: Boolean): RexState {
    val claimed = rexClaimed(rexUrl)
    val signedOut = claimed && runCatching { rexClaim(rexUrl)?.refused == true }.getOrDefault(refused)
    return RexState("Running", when {
        signedOut -> "Plex signed this server out. Sign in again."
        claimed -> "Ready for the Plex apps."
        else -> "Sign in to Plex to see it in the Plex apps."
    }, running = true, reachable = true, claimed = claimed, refused = signedOut)
}

val Runtime.plexClientId: String get() = preferences.getString("plex_client_id", null)
    ?: UUID.randomUUID().toString().also { preferences.edit().putString("plex_client_id", it).apply() }

class PlexSignInFailure(message: String) : Exception(message)

/**
 * Plex's PIN sign-in, used to claim rex (again, once plex.tv signed it out). The account token it yields asks
 * plex.tv for a claim code and is then dropped: rex keeps only the server token the claim gives it.
 */
class PlexSignIn(private val clientId: String, val serverName: String, private val rexUrl: String,
    private val plex: String = "https://plex.tv", private val pollMillis: Long = 2_000, private val confirmMillis: Long = 30_000) {
    constructor(runtime: Runtime) : this(runtime.plexClientId, runtime.serverName, runtime.rexUrl)

    data class Pin(val id: Long, val code: String, val expiresAt: Long)

    private fun request(url: String) = Request.Builder().url(url).header("Accept", "application/json")
        .header("X-Plex-Product", PRODUCT).header("X-Plex-Client-Identifier", clientId)
        .header("X-Plex-Device-Name", serverName)

    private fun json(builder: Request.Builder): JsonObject = http.newCall(builder.build()).execute().use {
        if (!it.isSuccessful) throw PlexSignInFailure(
            if (it.code == 429) "Plex is busy. Try again in a minute." else "Plex answered HTTP ${it.code}. Try again.")
        Json.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
    }

    /**
     * A four-character code for plex.tv/link, or with [strong] a long one for app.plex.tv/auth. That page refuses a
     * four-character code ("We were unable to complete this request", PUT /pins/link 403, measured 2026-11-16) and
     * nobody can type a long one at plex.tv/link, so a phone asks for both.
     */
    fun pin(strong: Boolean = false): Pin {
        val answer = json(request("$plex/api/v2/pins" + if (strong) "?strong=true" else "").post("".toRequestBody()))
        val lifetime = answer["expiresIn"]?.jsonPrimitive?.longOrNull ?: 600
        return Pin(answer["id"]!!.jsonPrimitive.long, answer["code"]!!.jsonPrimitive.content, System.currentTimeMillis() + lifetime * 1000)
    }

    /** The page that signs in with the code already filled in, for a device with a browser. */
    fun authUrl(pin: Pin): String = "https://app.plex.tv/auth#?clientID=$clientId&code=${pin.code}" +
        "&context%5Bdevice%5D%5Bproduct%5D=${PRODUCT.replace(" ", "%20")}"

    /** Waits until one of the PINs is linked and returns its account token. A failed poll is retried until one expires. */
    suspend fun waitForToken(pins: List<Pin>): String {
        while (true) {
            // The first PIN is the code on screen and expires first (900 s against a strong one's 1800 s).
            if (pins.any { System.currentTimeMillis() >= it.expiresAt }) throw PlexSignInFailure("The Plex code expired. Ask for a new one.")
            delay(pollMillis)
            for (pin in pins) runCatching { json(request("$plex/api/v2/pins/${pin.id}"))["authToken"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }

    /** Claims the local rex with that account, then waits until rex itself reports the claim working. */
    suspend fun claim(token: String) {
        val claim = json(request("$plex/api/claim/token.json").header("X-Plex-Token", token))["token"]!!.jsonPrimitive.content
        val url = "$rexUrl/myplex/claim".toHttpUrl().newBuilder().addQueryParameter("token", claim).build()
        http.newCall(Request.Builder().url(url).post("".toRequestBody()).build()).execute().use {
            // This request carries no token: a claimed rex answers it 401 (409 only to a valid token), unless plex.tv refused its claim.
            if (it.code == 409 || it.code == 401) throw PlexSignInFailure("rex is already signed in to Plex, so it kept that sign-in.")
            if (!it.isSuccessful) throw PlexSignInFailure("rex did not take the sign-in (HTTP ${it.code}). Try again.")
        }
        val until = System.currentTimeMillis() + confirmMillis
        while (System.currentTimeMillis() < until) {
            val ready = runCatching { rexClaim(rexUrl)?.let { it.claimed && !it.refused } ?: rexClaimed(rexUrl) }.getOrDefault(false)
            if (ready) return
            delay(pollMillis)
        }
        throw PlexSignInFailure("rex took the sign-in, but Plex has not accepted it. Sign in again.")
    }
}

/** [signedIn]: the last sign-in worked. */
data class PlexSignInState(val code: String = "", val waiting: Boolean = false, val message: String = "", val signedIn: Boolean = false)

/**
 * One Plex sign-in at a time. It lives outside any screen, with its own job: the person leaves the app for the
 * browser or another page, a recreated screen must not end the wait, and it never holds the rest of the app busy.
 */
class PlexSignInFlow(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PlexSignInState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    // A blocking request cannot be cancelled, so a cancelled or replaced job must not write when it returns.
    @Synchronized private fun publish(owner: Job, value: PlexSignInState) { if (owner === job && owner.isActive) mutable.value = value }

    /** [open] gets the browser page on a phone; [television] shows the code only. */
    @Synchronized fun start(signIn: PlexSignIn, television: Boolean, open: suspend (String) -> Unit) {
        if (job?.isActive == true) return
        mutable.value = PlexSignInState(waiting = true, message = "Asking Plex for a code…")
        val me = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]!!
            try {
                val code = signIn.pin()
                // A TV shows the code only: a web page there covers it, and a remote can't sign in on one.
                val page = if (television) null else runCatching { signIn.pin(strong = true) }.getOrNull()
                publish(me, PlexSignInState(code.code, waiting = true))
                page?.let { runCatching { open(signIn.authUrl(it)) } }
                val token = signIn.waitForToken(listOfNotNull(code, page))
                publish(me, PlexSignInState(waiting = true, message = "Signing rex in to Plex…"))
                signIn.claim(token)
                publish(me, PlexSignInState(message = "Signed in. Open a Plex app and pick ${signIn.serverName}.", signedIn = true))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                publish(me, PlexSignInState(message = when (e) {
                    is PlexSignInFailure -> e.message.orEmpty()
                    is IOException -> "Could not reach Plex. Check this device's internet connection and try again."
                    else -> e.message ?: "The Plex sign-in failed. Try again."
                }))
            }
        }
        job = me
        me.start()
    }

    @Synchronized fun cancel() { job?.cancel(); mutable.value = PlexSignInState() }
}

val PlexClaim = PlexSignInFlow(CoroutineScope(SupervisorJob() + Dispatchers.IO))
