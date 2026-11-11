package com.debridmediamanager.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Unix seconds. */
fun epochNow() = System.currentTimeMillis() / 1000

/** What an addon's panel says and offers about its account. */
data class AccountView(
    /** A plain line: "Signed in as …", "Sign in to start." and the like. */
    val line: String? = null,
    /** In red, instead of the addon's raw error. */
    val error: String? = null,
    /** The sign-in button's label; none when signed in or no account is needed. */
    val signIn: String? = null,
    /** The sign-in button opens the code already waiting instead of asking for a new one. */
    val waiting: Boolean = false,
    val signOut: Boolean = false,
    /** Without an account there is nothing to sync. */
    val canSync: Boolean = true,
    /** "Sync anyway" instead of "Sync now". */
    val force: Boolean = false,
)

fun accountView(addon: Addon, now: Long): AccountView {
    val signIn = addon.signIn ?: return AccountView(error = addon.error?.ifBlank { null }, force = syncAnyway(addon))
    // "Nobody is signed in" says what the button already does.
    val error = signIn.error?.ifBlank { null }?.let { "That didn't work: $it" }
        ?: addon.error?.takeIf { it.isNotBlank() && addon.errorKind != "sign_in" }
    signIn.account?.let { account ->
        return AccountView(signedInAs(signIn.spotify, account), error, signOut = true, force = syncAnyway(addon))
    }
    val pending = signIn.pending
    val label = signIn.label?.ifBlank { null }
    if (signIn.spotify) return when {
        pending != null && pending.expiresAt > now ->
            AccountView("Waiting for you to pick it in Spotify…", error, label ?: "Connect Spotify", waiting = true, canSync = false)
        pending != null -> AccountView("Spotify didn't see a pick in time.", error, "Try again", canSync = false)
        else -> AccountView("Connect to start.", error, label ?: "Connect Spotify", canSync = false)
    }
    return when {
        pending != null && pending.expiresAt > now ->
            AccountView("Waiting for you to enter the code…", error, "Enter the code", waiting = true, canSync = false)
        pending != null -> AccountView("The code ran out.", error, "Get a new code", canSync = false)
        else -> AccountView("Sign in to start.", error, label ?: "Sign in", canSync = false)
    }
}

/** "Signed in as …", or "Connected as …" for a Spotify device. */
fun signedInAs(spotify: Boolean, account: String): String {
    val verb = if (spotify) "Connected" else "Signed in"
    return account.ifBlank { null }?.let { "$verb as $it" } ?: verb
}

/**
 * Whether the app holds Wi-Fi's multicast lock: while some addon waits for someone to pick this device
 * in the Spotify app, which finds it by multicast DNS that Wi-Fi drops otherwise.
 */
fun wantsMulticast(host: HostStatus?, now: Long): Boolean = host?.addons.orEmpty().any { addon ->
    addon.signIn?.let { it.spotify && it.account == null && (it.pending?.expiresAt ?: 0) > now } == true
}

/** The addon whose code should be on screen when the app starts: one waiting, with nobody signed in yet. */
fun waitingSignIn(host: HostStatus, now: Long): String? = host.addons.firstOrNull { addon ->
    addon.signIn?.let { it.account == null && (it.pending?.expiresAt ?: 0) > now } == true
}?.id

/** Seconds as "4:05". */
fun countdown(seconds: Long): String = seconds.coerceAtLeast(0).let { "%d:%02d".format(it / 60, it % 60) }

/** The addon whose sign-in screen is open, if any. */
val openSignIn = MutableStateFlow<String?>(null)

/** Puts a fresh copy of one addon into the status every screen shows. */
fun showFresh(addon: Addon) = Status.addons.update { state ->
    state.copy(host = state.host?.let { host -> host.copy(addons = host.addons.map { if (it.id == addon.id) addon else it }) })
}

/** Unix seconds, ticking every second while on screen. */
@Composable fun clock(): Long = produceState(epochNow()) { while (true) { delay(1_000); value = epochNow() } }.value

/**
 * Best effort: whether this device is on Wi-Fi or Ethernet, where a phone's Spotify app can find it.
 * Any such network counts, so a VPN on top doesn't hide it.
 */
fun onLocalNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
    @Suppress("DEPRECATION")
    return connectivity.allNetworks.any { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
    }
}

/**
 * The code and where to enter it, until the host sees someone did; for Spotify, the steps to pick this
 * device in the Spotify app instead.
 */
@Composable fun SignInScreen(id: String, work: Work, busy: Boolean, close: () -> Unit) {
    val context = LocalContext.current
    val television = LocalTelevision.current
    val addons by Status.addons.collectAsStateWithLifecycle()
    val addon = addons.host?.addons?.firstOrNull { it.id == id }
    val now = clock()
    // Faster than the 5 s status poll, and only while the code is on screen.
    LaunchedEffect(id) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { client.addon(id) } }.onSuccess(::showFresh)
            delay(2_000)
        }
    }
    val account = addon?.signIn?.account
    LaunchedEffect(account) { if (account != null) { delay(2_000); close() } }
    BackHandler { close() }
    val start = remember { FocusRequester() }
    val again = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    val spotify = addon?.signIn?.spotify == true
    val pending = addon?.signIn?.pending?.takeIf { account == null }
    val live = pending != null && pending.expiresAt > now
    val renew = addon != null && account == null && !live
    LaunchedEffect(renew) { if (renew) runCatching { again.requestFocus() } }
    // A TV has no one to paste into.
    val clipboard = if (television) null else remember { context.getSystemService(ClipboardManager::class.java) }
    var copied by remember(pending?.code) { mutableStateOf(false) }
    ScrollPage {
        Title(addon?.signIn?.label?.ifBlank { null } ?: "Sign in to ${addon?.name ?: id}")
        when {
            addon == null -> Panel { Text("Start rex to sign in.") }
            account != null -> Panel {
                Text(signedInAs(spotify, account), color = MaterialTheme.colorScheme.primary, fontSize = 28.sp, lineHeight = 34.sp)
            }
            pending != null && live && spotify -> Panel {
                // Read from across a room on a TV.
                val step = if (television) 28.sp else 20.sp
                listOf("1. Open Spotify on a phone that's on the same Wi-Fi.", "2. Play any song.", "3. Tap the devices button and pick")
                    .forEach { Text(it, fontSize = step, lineHeight = step * 1.3f) }
                val big = if (television) 56.sp else 34.sp
                Text(pending.device?.ifBlank { null } ?: context.runtime.serverName, color = MaterialTheme.colorScheme.primary,
                    fontSize = big, lineHeight = big * 1.15f, fontWeight = FontWeight.Bold)
                // Spotify finds the device on the local network only.
                if (onLocalNetwork(context)) Text("Waiting for you to pick it in Spotify…")
                else Text("Pairing needs this ${if (television) "TV" else "device"} on the same Wi-Fi as your phone. Connect it to Wi-Fi or Ethernet.",
                    color = MaterialTheme.colorScheme.error)
                Text("This works for ${countdown(pending.expiresAt - now)} more.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pending != null && live -> Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("On ${if (television) "your phone or computer" else "any device"}, go to", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(pending.url.removePrefix("https://"), color = MaterialTheme.colorScheme.secondary, fontSize = 26.sp,
                            lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                        Text("and enter", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Across a room on a TV; on a phone it still has to fit the width.
                        val big = if (television) 64.sp else 40.sp
                        Text(pending.code, color = MaterialTheme.colorScheme.primary, fontSize = big, lineHeight = big * 1.15f,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
                    }
                    // For a phone pointed at the TV; on a phone, Open the page does it.
                    if (television) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QrImage(pending.url)
                        Text("Or scan this", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Waiting for you to enter the code…")
                Text("The code works for ${countdown(pending.expiresAt - now)} more.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Panel {
                Text(when {
                    spotify && pending != null -> "Spotify didn't see a pick in time."
                    spotify -> "Not waiting for Spotify right now."
                    pending != null -> "That code ran out."
                    else -> "No code right now."
                })
                addon.signIn?.error?.ifBlank { null }?.let { Text("That didn't work: $it", color = MaterialTheme.colorScheme.error) }
            }
        }
        Buttons {
            if (pending != null && live && !television && !spotify) {
                Action("Open the page", primary = true) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, pending.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                if (clipboard != null) Action(if (copied) "Copied" else "Copy code") {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Code", pending.code)); copied = true
                }
            }
            if (renew) Action(if (spotify) "Try again" else "Get a new code", !busy && addon?.enabled == true, primary = true, modifier = Modifier.focusRequester(again)) {
                work { showFresh(client.signIn(id)); "" }
            }
            // One button throughout, so a remote's focus stays on it as the screen changes.
            Action(when { account != null -> "Done"; addon == null -> "Back"; else -> "Cancel" }, modifier = Modifier.focusRequester(start)) {
                if (addon != null && account == null) work.addonCall(if (spotify) "Pairing cancelled." else "Sign-in cancelled.") { client.signOut(id) }
                close()
            }
        }
    }
}

/** [text] as a QR code: dark on white with the standard's border, whatever the theme. */
@Composable fun QrImage(text: String, modifier: Modifier = Modifier) {
    val code = remember(text) { QrCode.encode(text) } ?: return
    Canvas(modifier.size(220.dp).background(Color.White)) {
        // Whole pixels per module, or neighbouring modules leave hairlines between them.
        val cell = (size.minDimension / (code.size + 8)).toInt().toFloat()
        val origin = ((size.minDimension - cell * code.size) / 2).toInt().toFloat()
        for (y in 0 until code.size) for (x in 0 until code.size) if (code[x, y])
            drawRect(Color.Black, Offset(origin + x * cell, origin + y * cell), Size(cell, cell))
    }
}
