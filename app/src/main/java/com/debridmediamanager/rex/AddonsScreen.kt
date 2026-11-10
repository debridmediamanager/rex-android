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

/** What an addon can reach, from its manifest's `hosts`. */
fun reach(hosts: List<String>): String = when {
    "*" in hosts -> "any website"
    hosts.isEmpty() -> "no websites"
    else -> hosts.joinToString(", ")
}

/**
 * What to tell the person before an update that can reach other websites than the installed copy,
 * or comes from another repository (also a repository taking over the copy built into the app).
 * Null when it changes neither: then it stays one tap.
 */
fun updateWarning(installedHosts: List<String>?, offeredHosts: List<String>?, installedRepo: String?, repo: String, repoName: String): String? {
    val sameSites = installedHosts != null && offeredHosts != null && installedHosts.toSet() == offeredHosts.toSet()
    if (sameSites && installedRepo == repo) return null
    val from = if (installedRepo == repo) "It comes from $repoName."
        else "It now comes from $repoName instead of ${installedRepo ?: "the copy built into the app"}."
    val added = offeredHosts.orEmpty() - installedHosts.orEmpty().toSet()
    val sites = when {
        offeredHosts == null -> "It doesn't say which websites it can reach."
        installedHosts == null || added.isEmpty() -> "It can reach ${reach(offeredHosts)}."
        else -> "It can reach ${reach(offeredHosts)}, new: ${reach(added)}."
    }
    return "$from $sites Update only from people you trust."
}

/**
 * Whether the sync button overrides rex: only after rex refused a sync that would remove more than
 * half of the addon's titles. Any other failure just syncs again.
 */
fun syncAnyway(addon: Addon) = addon.errorKind == "would_remove"

/** An update waiting for a yes, and why it asks. */
private class PendingUpdate(val name: String, val warning: String, val update: () -> Unit)

@Composable private fun ConfirmUpdate(pending: PendingUpdate, dismiss: () -> Unit) =
    AlertDialog(onDismissRequest = dismiss, title = { Text("Update ${pending.name}?") }, text = { Text(pending.warning) },
        confirmButton = { Action("Update", primary = true) { dismiss(); pending.update() } },
        dismissButton = { Action("Cancel") { dismiss() } })

@Composable fun AddonsScreen(work: Work, busy: Boolean) {
    val addons by Status.addons.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf<Repo?>(null) }
    var removing by remember { mutableStateOf<Addon?>(null) }
    var dropping by remember { mutableStateOf<Repo?>(null) }
    var asking by remember { mutableStateOf<PendingUpdate?>(null) }
    var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
    var link by remember { mutableStateOf("") }
    val host = addons.host
    LaunchedEffect(host != null, browsing) {
        if (host != null) withContext(Dispatchers.IO) { runCatching { client.repos() } }.onSuccess { repos = it }
    }
    // Runs a repository call, then lists the repositories again.
    val repoCall = { done: String, call: () -> Unit -> work.addonCall(done) { call(); repos = client.repos() } }
    editing?.let { id -> AddonSettingsForm(id, work, busy) { editing = null }; return }
    browsing?.let { repo -> RepoScreen(repo, work, busy) { browsing = null }; return }
    ScrollPage {
        Title("Addons", "Addons bring things to watch into rex.")
        if (host == null) Panel { Text(addons.detail.ifBlank { "Start rex to manage addons." }) }
        else {
            if (host.addons.isEmpty()) Panel { Text("No addons yet. Install one from a repository below.") }
            host.addons.forEach { addon ->
                Panel {
                    Text(listOf(addon.name, addon.version).filter(String::isNotBlank).joinToString(" "),
                        style = MaterialTheme.typography.titleLarge)
                    if (addon.description.isNotBlank()) Text(addon.description)
                    Text(addonSummary(addon), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    addon.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Toggle("Enabled", addon.enabled) { on ->
                        work.addonCall(if (on) "${addon.name} is on." else "${addon.name} is off.") { client.enable(addon.id, on) }
                    }
                    Buttons {
                        val repo = addon.repo
                        if (addon.updateAvailable && repo != null) Action("Update", !busy, primary = true) {
                            val update = { work.addonCall("${addon.name} updated.") { client.install(repo, addon.id) } }
                            val repoName = repos.firstOrNull { it.id == repo }?.name?.ifBlank { null } ?: repo
                            val warning = updateWarning(addon.hosts, addon.updateHosts, repo, repo, repoName)
                            if (warning == null) update() else asking = PendingUpdate(addon.name, warning, update)
                        }
                        val force = syncAnyway(addon)
                        Action(if (force) "Sync anyway" else "Sync now", !busy && addon.enabled && addon.state != "syncing") {
                            work.addonCall("Syncing ${addon.name}.") { client.sync(addon.id, force = force) }
                        }
                        Action("Settings", !busy) { editing = addon.id }
                        Action("Remove", !busy) { removing = addon }
                    }
                }
            }
            Title("Repositories", "Where addons come from. Install only from people you trust.")
            if (repos.isEmpty()) Panel { Text("No repositories yet. Add one to find addons.") }
            repos.forEach { repo ->
                Panel {
                    Text(repo.name.ifBlank { repo.id }, style = MaterialTheme.typography.titleLarge)
                    Text(repo.url, color = MaterialTheme.colorScheme.secondary)
                    if (repo.description.isNotBlank()) Text(repo.description)
                    Text("${repo.addonCount} ${if (repo.addonCount == 1) "addon" else "addons"}" +
                        (repo.lastFetch?.let { " · checked ${ago(it)}" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    repo.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Buttons {
                        Action("Addons", !busy, primary = true) { browsing = repo }
                        Action("Refresh", !busy) { repoCall("Checked ${repo.name.ifBlank { repo.id }} for updates.") { client.refreshRepo(repo.id) } }
                        Action("Remove", !busy) { dropping = repo }
                    }
                }
            }
            Panel {
                Text("Add a repository", style = MaterialTheme.typography.titleLarge)
                Input("Repository link", link, hint = "Like github.com/user/repo", link = true) { link = it }
                Buttons {
                    Action("Add", !busy && link.isNotBlank(), primary = true) {
                        val typed = link
                        repoCall("Repository added.") {
                            client.addRepo(repoLink(typed) ?: error("That isn't a repository link. Try github.com/user/repo."))
                            link = ""
                        }
                    }
                    if (repos.isNotEmpty()) Action("Refresh all", !busy) {
                        repoCall("Checked every repository for updates.") { repos.forEach { client.refreshRepo(it.id) } }
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
    asking?.let { ConfirmUpdate(it) { asking = null } }
    dropping?.let { repo ->
        val name = repo.name.ifBlank { repo.id }
        AlertDialog(onDismissRequest = { dropping = null }, title = { Text("Remove $name?") },
            text = { Text("Addons you installed from it stay, but they won't get updates.") },
            confirmButton = { Action("Remove", primary = true) { dropping = null; repoCall("$name removed.") { client.removeRepo(repo.id) } } },
            dismissButton = { Action("Cancel") { dropping = null } })
    }
}

/** A repository's addons, each with what it can reach, to install or update. */
@Composable fun RepoScreen(repo: Repo, work: Work, busy: Boolean, close: () -> Unit) {
    val name = repo.name.ifBlank { repo.id }
    var offered by remember { mutableStateOf<List<RepoAddon>?>(null) }
    var problem by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<RepoAddon?>(null) }
    var asking by remember { mutableStateOf<PendingUpdate?>(null) }
    val load = { runCatching { client.repoAddons(repo.id) }.onSuccess { offered = it }.onFailure { problem = it.message ?: "Can't list its addons." } }
    LaunchedEffect(repo.id) { withContext(Dispatchers.IO) { load() } }
    BackHandler { close() }
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    ScrollPage {
        Title(name, repo.url)
        Action("Back", modifier = Modifier.focusRequester(start)) { close() }
        val list = offered
        when {
            list == null -> Panel { Text(problem.ifBlank { "Loading…" }) }
            list.isEmpty() -> Panel { Text("This repository has no addons.") }
            else -> list.forEach { addon ->
                Panel {
                    Text(listOf(addon.name, addon.version).filter(String::isNotBlank).joinToString(" "), style = MaterialTheme.typography.titleLarge)
                    if (addon.description.isNotBlank()) Text(addon.description)
                    Text("Can reach ${reach(addon.hosts)}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        addon.installedVersion == null -> Action("Install", !busy, primary = true) { confirming = addon }
                        addon.updateAvailable -> Action("Update to ${addon.version}", !busy, primary = true) {
                            val update = { work.addonCall("${addon.name} updated.") { client.install(repo.id, addon.id); load() } }
                            val warning = updateWarning(addon.installedHosts, addon.hosts, addon.installedRepo, repo.id, name)
                            if (warning == null) update() else asking = PendingUpdate(addon.name, warning, update)
                        }
                        else -> Text("Installed", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    asking?.let { ConfirmUpdate(it) { asking = null } }
    confirming?.let { addon ->
        AlertDialog(onDismissRequest = { confirming = null }, title = { Text("Install ${addon.name}?") },
            text = { Text("It comes from $name and can reach ${reach(addon.hosts)}. Install addons only from people you trust.") },
            confirmButton = { Action("Install", primary = true) {
                confirming = null
                work.addonCall("${addon.name} installed. It starts syncing now.") { client.install(repo.id, addon.id); load() }
            } },
            dismissButton = { Action("Cancel") { confirming = null } })
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
        if (current != null && current.deleted > 0) Panel {
            val titles = if (current.deleted == 1L) "title" else "titles"
            Text("You deleted ${current.deleted} $titles in a Plex app. They stay gone until you bring them back.")
            Buttons {
                Action("Bring back ${current.deleted} deleted $titles", !busy) {
                    work.addonCall("Bringing them back. ${current.name} is syncing.") { client.restore(id); settings = current.copy(deleted = 0) }
                }
            }
        }
        Buttons {
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
