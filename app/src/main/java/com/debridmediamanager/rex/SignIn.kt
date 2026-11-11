package com.debridmediamanager.rex

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
        return AccountView(account.ifBlank { null }?.let { "Signed in as $it" } ?: "Signed in", error, signOut = true, force = syncAnyway(addon))
    }
    val pending = signIn.pending
    return when {
        pending != null && pending.expiresAt > now ->
            AccountView("Waiting for you to enter the code…", error, "Enter the code", waiting = true, canSync = false)
        pending != null -> AccountView("The code ran out.", error, "Get a new code", canSync = false)
        else -> AccountView("Sign in to start.", error, signIn.label?.ifBlank { null } ?: "Sign in", canSync = false)
    }
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

/** The code and where to enter it, until the host sees someone did. */
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
                Text(account.ifBlank { null }?.let { "Signed in as $it" } ?: "Signed in", color = MaterialTheme.colorScheme.primary, fontSize = 28.sp, lineHeight = 34.sp)
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
                Text(if (pending != null) "That code ran out." else "No code right now.")
                addon.signIn?.error?.ifBlank { null }?.let { Text("That didn't work: $it", color = MaterialTheme.colorScheme.error) }
            }
        }
        Buttons {
            if (pending != null && live && !television) {
                Action("Open the page", primary = true) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, pending.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                if (clipboard != null) Action(if (copied) "Copied" else "Copy code") {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Code", pending.code)); copied = true
                }
            }
            if (renew) Action("Get a new code", !busy && addon?.enabled == true, primary = true, modifier = Modifier.focusRequester(again)) {
                work { showFresh(client.signIn(id)); "" }
            }
            // One button throughout, so a remote's focus stays on it as the screen changes.
            Action(when { account != null -> "Done"; addon == null -> "Back"; else -> "Cancel" }, modifier = Modifier.focusRequester(start)) {
                if (addon != null && account == null) work.addonCall("Sign-in cancelled.") { client.signOut(id) }
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
