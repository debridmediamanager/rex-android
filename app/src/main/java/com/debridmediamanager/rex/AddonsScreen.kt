package com.debridmediamanager.rex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val client by lazy { AddonsClient() }

/** Runs an addon call, then reads the host's status again so the screen shows its effect at once. */
private fun Work.addonCall(done: String, call: () -> Unit) = this {
    call()
    runCatching { Status.addons.value = AddonsState(host = client.status()) }
    done
}

@Composable fun AddonsScreen(work: Work, busy: Boolean) {
    val addons by Status.addons.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Addon?>(null) }
    var url by remember { mutableStateOf("") }
    val open = editing
    if (open != null) { AddonSettingsForm(open, work, busy) { editing = null }; return }
    val host = addons.host
    ScrollPage {
        Title("Addons", "Addons bring things to watch into rex.")
        if (host == null) Panel { Text(addons.detail.ifBlank { "Start rex to manage addons." }) }
        else {
            if (host.addons.isEmpty()) Panel { Text("No addons yet. Add one below.") }
            host.addons.forEach { addon ->
                Panel {
                    Text(listOf(addon.name, addon.version).filter(String::isNotBlank).joinToString(" "),
                        style = MaterialTheme.typography.titleLarge)
                    if (addon.description.isNotBlank()) Text(addon.description)
                    Text(addonSummary(addon), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    addon.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Toggle("On", addon.enabled) { on ->
                        work.addonCall(if (on) "${addon.name} is on." else "${addon.name} is off.") { client.enable(addon.id, on) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // After a failed sync the error says why; syncing anyway also lets it remove
                        // more than half of its titles, which rex refuses otherwise.
                        val failed = addon.state == "error"
                        Action(if (failed) "Sync anyway" else "Sync now", !busy && addon.enabled && addon.state != "syncing") {
                            work.addonCall("Syncing ${addon.name}.") { client.sync(addon.id, force = failed) }
                        }
                        Action("Settings", !busy) { editing = addon.id }
                        Action("Remove", !busy) { removing = addon }
                    }
                }
            }
            Panel {
                Text("Add an addon", style = MaterialTheme.typography.titleLarge)
                Input("Addon link", url, hint = "The address of its manifest.json", link = true) { url = it }
                Action("Add", !busy && url.isNotBlank(), primary = true) {
                    val link = url.trim()
                    work.addonCall("Addon added.") {
                        require(link.startsWith("https://") || link.startsWith("http://")) { "An addon link starts with https://" }
                        client.install(link)
                        url = ""
                    }
                }
            }
        }
    }
    removing?.let { addon ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${addon.name}?") },
            text = { Text("Its titles leave the Plex apps and its settings are deleted.") },
            confirmButton = { Action("Remove", primary = true) { removing = null; work.addonCall("${addon.name} removed.") { client.remove(addon.id) } } },
            dismissButton = { Action("Cancel") { removing = null } })
    }
}

/** An addon's settings, as a form its manifest's `config` describes. */
@Composable fun AddonSettingsForm(id: String, work: Work, busy: Boolean, close: () -> Unit) {
    var settings by remember { mutableStateOf<AddonSettings?>(null) }
    var problem by remember { mutableStateOf("") }
    var input by remember { mutableStateOf(emptyMap<String, String>()) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) { runCatching { client.settings(id) } }
            .onSuccess { settings = it; input = initialForm(it) }
            .onFailure { problem = it.message ?: "Can't read this addon's settings." }
    }
    BackHandler { close() }
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    ScrollPage {
        val current = settings
        Title(current?.name ?: "Settings", "Addon settings")
        when {
            current == null -> Panel { Text(problem.ifBlank { "Loading…" }) }
            current.fields.isEmpty() -> Panel { Text("This addon has no settings.") }
            else -> Panel {
                current.fields.forEach { field ->
                    val value = input[field.key].orEmpty()
                    val change = { text: String -> input = input + (field.key to text) }
                    val label = field.label + if (field.required) " *" else ""
                    val hint = errors[field.key] ?: when {
                        field.type == "secret" && secretSaved(current, field.key) -> "Saved. Leave it blank to keep it."
                        field.type == "list" -> "One per line"
                        else -> ""
                    }
                    when (field.type) {
                        "bool" -> Toggle(label, value == "true") { change(it.toString()) }
                        else -> Input(label, value, secret = field.type == "secret", hint = hint,
                            multiline = field.type == "list", number = field.type == "number", onChange = change)
                    }
                    if (field.type == "bool" && hint.isNotBlank()) Text(hint, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("Back", modifier = Modifier.focusRequester(start)) { close() }
            if (current != null && current.fields.isNotEmpty()) Action("Save", !busy, primary = true) {
                val checked = checkForm(current, input)
                errors = checked.errors
                if (checked.errors.isEmpty()) work.addonCall("Settings saved.") { client.configure(id, checked.body) }
            }
        }
        if (errors.isNotEmpty()) Text("Fix the fields marked above.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    }
}
