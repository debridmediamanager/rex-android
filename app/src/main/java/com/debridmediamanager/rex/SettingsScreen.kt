package com.debridmediamanager.rex

import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable fun SettingsScreen(work: Work, busy: Boolean) {
    val context = LocalContext.current
    val runtime = context.runtime
    var port by remember { mutableStateOf(runtime.port.toString()) }
    var name by remember { mutableStateOf(runtime.serverName) }
    var boot by remember { mutableStateOf(runtime.startAtBoot) }
    var licenses by remember { mutableStateOf(false) }
    if (licenses) { Licenses { licenses = false }; return }
    val named = remember { runtime.named }
    ScrollPage {
        Title("Settings")
        Panel {
            if (named) Text("Name: ${runtime.serverName}. To rename it, open Settings, General in a Plex app and change Friendly name.")
            else Input("Name", name, hint = "What the Plex apps call this server") { name = it }
            Input("Port", port, number = true, hint = "32400 unless another Plex server runs on this device. The Plex apps follow a change.") { port = it }
            Action("Save", !busy, primary = true) { work {
                val chosen = parsePort(port) ?: error("Use a port from 1024 to 65535.")
                require(named || name.isNotBlank()) { "Give the server a name." }
                val moved = chosen != runtime.port
                if (moved && portInUse(chosen)) error("Another app on this device already uses port $chosen. Try another one.")
                runtime.port = chosen
                if (!named) runtime.serverName = name
                if (moved && Status.rex.value.running) { RexService.send(context, RexService.RESTART); "Saved. rex restarts on port $chosen." }
                else "Saved."
            } }
        }
        Panel {
            Toggle("Start when this device turns on", boot) { boot = it; runtime.startAtBoot = it }
            BackgroundPermissions()
            Text("Some devices stop background apps anyway. dontkillmyapp.com explains how to stop that on yours.")
        }
        Panel {
            Text("rex for Android ${BuildConfig.VERSION_NAME}")
            Action("Licenses") { licenses = true }
        }
    }
}

/** The bundled programs' license texts, readable with a remote: each paragraph takes focus. */
@Composable fun Licenses(close: () -> Unit) {
    val context = LocalContext.current
    val texts = remember {
        context.assets.list("native-licenses").orEmpty().sorted().filter { !it.endsWith(".json") }.map { name ->
            name to context.assets.open("native-licenses/$name").bufferedReader().use { it.readText() }
        }
    }
    BackHandler { close() }
    // The button that opened this page is gone, so a remote would otherwise land back in the menu.
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Title("Licenses", "The programs inside this app and their terms.") }
        item { Action("Close", modifier = Modifier.focusRequester(start)) { close() } }
        if (texts.isEmpty()) item { Text("This build carries no license texts.") }
        texts.forEach { (name, text) ->
            item { Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
            items(text.split(Regex("\n\\s*\n")).filter(String::isNotBlank)) { paragraph ->
                Text(paragraph.trim(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.focusable())
            }
        }
    }
}

@Composable fun LogsScreen(work: Work, busy: Boolean) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(Logs.all()) }
    var preview by remember { mutableStateOf<SupportBundle?>(null) }
    var receipt by remember { mutableStateOf<SupportReceipt?>(null) }
    LaunchedEffect(Unit) { while (true) { lines = Logs.all(); delay(2_000) } }
    receipt?.let { SentLogs(it) { receipt = null; preview = null }; return }
    preview?.let { SendLogsPreview(it, work, busy, sent = { done -> receipt = done }) { preview = null }; return }
    // Newest first, one focusable line each, so a remote can scroll it.
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Title("Logs", "What rex and the addons said lately, newest first. Tokens, keys and passwords are hidden.") }
        item {
            Buttons {
                Action("Send logs to support", !busy, primary = true) { work { preview = supportBundle(context); "" } }
                Action("Share", lines.isNotEmpty()) {
                    // Fire TV has no share sheet.
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n")), "Share logs")) }
                }
            }
        }
        if (lines.isEmpty()) item { Text("Nothing yet. Logs appear once rex starts.") }
        items(lines.asReversed()) { line -> Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.focusable()) }
    }
}

/** What support needs to know about this device and rex, and nothing about the person. Blocks on rex. */
private fun supportBundle(context: Context): SupportBundle {
    val runtime = context.runtime
    val rex = Status.rex.value
    val addons = Status.addons.value
    val yes = { on: Boolean -> if (on) "yes" else "no" }
    val television = context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val info = linkedMapOf(
        "App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})",
        "rex" to (runCatching { rexVersion(runtime.rexUrl) }.getOrNull() ?: "unknown"),
        "Addon host" to (addons.host?.version?.ifBlank { null } ?: "unknown"),
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}" + if (television) " (TV)" else "",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
        "Port" to "${runtime.port}",
        "rex state" to listOf(rex.phase, rex.detail).filter(String::isNotBlank).joinToString(": "),
        "rex running" to yes(rex.running),
        "rex reachable" to yes(rex.reachable),
        "Signed in to Plex" to yes(rex.claimed),
        "Addon host state" to (addons.host?.let { "running, rex ${if (it.rex.reachable) "reachable" else "not reachable"}" }
            ?: addons.detail.ifBlank { "not running" }),
        "Time zone" to java.time.ZonedDateTime.now().offset.id,
    )
    return supportBundle(BuildConfig.VERSION_NAME, info, addons.host, addons.detail, Logs.run(previous = false), Logs.run(previous = true))
}

/** Exactly what will be sent, with Send and Cancel kept on screen above it. */
@Composable fun SendLogsPreview(bundle: SupportBundle, work: Work, busy: Boolean, sent: (SupportReceipt) -> Unit, close: () -> Unit) {
    BackHandler { close() }
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    val files = remember(bundle) { bundle.files.map { it.name to it.text.lines() } }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Send logs to support", "This is everything that gets sent. Only the support team can read it, and it's deleted after 7 days.")
        Buttons {
            Action("Send", !busy, primary = true, modifier = Modifier.focusRequester(start)) {
                work { val receipt = SupportClient().send(bundle); sent(receipt); "" }
            }
            Action("Cancel") { close() }
        }
        // One focusable row each, so a remote scrolls through it below the buttons.
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text("About this device", style = MaterialTheme.typography.titleLarge) }
            items(bundle.info.entries.toList()) { (label, value) ->
                Row(Modifier.focusable(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, Modifier.width(150.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value)
                }
            }
            files.forEach { (name, lines) ->
                item { Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
                if (lines.all(String::isBlank)) item { Text("Empty.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.focusable()) }
                else items(lines) { line -> Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.focusable()) }
            }
        }
    }
}

/** The code to hand to support, big enough to read off a TV across the room. */
@Composable fun SentLogs(receipt: SupportReceipt, done: () -> Unit) {
    val context = LocalContext.current
    BackHandler { done() }
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    // A TV has no one to paste into.
    val clipboard = if (LocalTelevision.current) null else remember { context.getSystemService(ClipboardManager::class.java) }
    var copied by remember { mutableStateOf(false) }
    ScrollPage {
        Title("Sent")
        Panel {
            Text("Your code", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(receipt.display, color = MaterialTheme.colorScheme.primary, fontSize = 56.sp, lineHeight = 64.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
            Text("Post this code in your support thread on Discord. Kept for ${receipt.ttlDays} days.")
            Buttons {
                if (clipboard != null) Action(if (copied) "Copied" else "Copy", primary = true) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Support code", receipt.display)); copied = true
                }
                Action("Done", modifier = Modifier.focusRequester(start)) { done() }
            }
        }
    }
}
