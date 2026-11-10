package com.debridmediamanager.rex

import android.content.Intent
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
import androidx.compose.ui.unit.dp
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

@Composable fun LogsScreen() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(Logs.all()) }
    LaunchedEffect(Unit) { while (true) { lines = Logs.all(); delay(2_000) } }
    // Newest first, one focusable line each, so a remote can scroll it.
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Title("Logs", "What rex and the addons said lately, newest first. Tokens, keys and passwords are hidden.") }
        item {
            Action("Share", lines.isNotEmpty()) {
                // Fire TV has no share sheet.
                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n")), "Share logs")) }
            }
        }
        if (lines.isEmpty()) item { Text("Nothing yet. Logs appear once rex starts.") }
        items(lines.asReversed()) { line -> Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.focusable()) }
    }
}
