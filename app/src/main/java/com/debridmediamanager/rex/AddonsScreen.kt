package com.debridmediamanager.rex

import androidx.activity.compose.BackHandler
import android.content.Intent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val client by lazy { AddonsClient() }

/** Runs an addon call, then reads the host's status again so the screen shows its effect at once. */
internal fun Work.addonCall(done: String, call: () -> Unit) = this {
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
 * What an installed addon can reach: its manifest's `hosts`, and the addresses the person typed into
 * its settings; [asks] when it has address settings still empty.
 */
fun reachLine(hosts: List<String>, granted: List<String>, asks: Boolean = false): String = when {
    granted.isEmpty() && asks -> if (hosts.isEmpty()) "Can reach only the address you type below." else "Can reach ${reach(hosts)}, and the address you type below."
    granted.isEmpty() -> "Can reach ${reach(hosts)}."
    hosts.isEmpty() -> "Can reach only what you allowed: ${granted.joinToString(", ")}."
    else -> "Can reach ${reach(hosts)}, and what you allowed: ${granted.joinToString(", ")}."
}

/**
 * What to tell the person before an update that can reach other websites than the installed copy,
 * comes from another repository (also a repository taking over the copy built into the app), or adds
 * [hostSettings]: addresses to type in, which it may then reach. Null when it changes none: then it stays one tap.
 */
fun updateWarning(installedHosts: List<String>?, offeredHosts: List<String>?, installedRepo: String?, repo: String, repoName: String,
    hostSettings: List<String>? = null): String? {
    val sameSites = installedHosts != null && offeredHosts != null && installedHosts.toSet() == offeredHosts.toSet()
    if (sameSites && installedRepo == repo && hostSettings.isNullOrEmpty()) return null
    val from = if (installedRepo == repo) "It comes from $repoName."
        else "It now comes from $repoName instead of ${installedRepo ?: "the copy built into the app"}."
    val added = offeredHosts.orEmpty() - installedHosts.orEmpty().toSet()
    val sites = when {
        offeredHosts == null -> "It doesn't say which websites it can reach."
        installedHosts == null || added.isEmpty() -> "It can reach ${reach(offeredHosts)}."
        else -> "It can reach ${reach(offeredHosts)}, new: ${reach(added)}."
    }
    val asks = when (hostSettings.orEmpty().size) {
        0 -> ""
        1 -> " It asks for an address (${hostSettings!![0]}) and can reach whatever you type there."
        else -> " It asks for addresses (${hostSettings!!.joinToString(", ")}) and can reach whatever you type there."
    }
    return "$from $sites$asks Update only from people you trust."
}

/**
 * Whether the sync button overrides rex: only after rex refused a sync that would remove more than
 * half of the addon's titles, and never without the account the addon needs. Any other failure just
 * syncs again.
 */
fun syncAnyway(addon: Addon) = addon.errorKind == "would_remove" && addon.signIn.let { it == null || it.account != null }

/**
 * What another addon has to do before this one works, for the first requirement not met; null when
 * all are. Named as installed, else by its id.
 */
fun unmetNeed(addon: Addon, installed: List<Addon>): String? {
    // The update comes first: the new version may need other addons, or none.
    if (addon.errorKind == "update") return null
    val need = addon.requires.firstOrNull { !it.ok } ?: return null
    val name = installed.firstOrNull { it.id == need.id }?.name?.ifBlank { null } ?: need.id.replaceFirstChar(Char::uppercaseChar)
    return when (need.reason) {
        "not installed" -> "Needs the $name addon: install it and sign in."
        "disabled" -> "Needs the $name addon turned on."
        "sign in needed" -> "Sign in to the $name addon first."
        else -> "Needs the $name addon."
    }
}

/** An update waiting for a yes, and why it asks. */
private class PendingUpdate(val name: String, val warning: String, val update: () -> Unit)

@Composable private fun ConfirmUpdate(pending: PendingUpdate, dismiss: () -> Unit) =
    AlertDialog(onDismissRequest = dismiss, title = { Text("Update ${pending.name}?") }, text = { Text(pending.warning) },
        confirmButton = { Action("Update", primary = true) { dismiss(); pending.update() } },
        dismissButton = { Action("Cancel") { dismiss() } })

@Composable fun AddonsScreen(work: Work, busy: Boolean) {
    val addons by Status.addons.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf<Repo?>(null) }
    var removing by remember { mutableStateOf<Addon?>(null) }
    var signingOut by remember { mutableStateOf<Addon?>(null) }
    val signingIn by openSignIn.collectAsStateWithLifecycle()
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
    signingIn?.let { id -> SignInScreen(id, work, busy) { openSignIn.value = null }; return }
    editing?.let { id -> AddonSettingsForm(id, work, busy) { editing = null }; return }
    reading?.let { id -> AddonLogScreen(id) { reading = null }; return }
    browsing?.let { repo -> RepoScreen(repo, work, busy) { browsing = null }; return }
    val now = clock()
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
                    val account = accountView(addon, now)
                    val need = unmetNeed(addon, host.addons)
                    account.line?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                    (need ?: account.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Toggle("Enabled", addon.enabled) { on ->
                        work.addonCall(if (on) "${addon.name} is on." else "${addon.name} is off.") { client.enable(addon.id, on) }
                    }
                    Buttons {
                        account.signIn?.let { label ->
                            Action(label, !busy && addon.enabled, primary = true) {
                                if (account.waiting) openSignIn.value = addon.id
                                else work { showFresh(client.signIn(addon.id)); openSignIn.value = addon.id; "" }
                            }
                        }
                        val repo = addon.repo
                        if (addon.updateAvailable && repo != null) Action("Update", !busy, primary = true) {
                            val update = { work.addonCall("${addon.name} updated.") { client.install(repo, addon.id) } }
                            val repoName = repos.firstOrNull { it.id == repo }?.name?.ifBlank { null } ?: repo
                            val warning = updateWarning(addon.hosts, addon.updateHosts, repo, repo, repoName, addon.updateHostSettings)
                            if (warning == null) update() else asking = PendingUpdate(addon.name, warning, update)
                        }
                        Action(if (account.force) "Sync anyway" else "Sync now", !busy && addon.enabled && addon.state != "syncing" && account.canSync && need == null) {
                            work.addonCall("Syncing ${addon.name}.") { client.sync(addon.id, force = account.force) }
                        }
                        Action("Settings", !busy) { editing = addon.id }
                        Action("Log") { reading = addon.id }
                        if (account.signOut) Action("Sign out", !busy) { signingOut = addon }
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
    signingOut?.let { addon ->
        AlertDialog(onDismissRequest = { signingOut = null }, title = { Text("Sign out of ${addon.name}?") },
            text = { Text("Its titles stay, but it stops updating until you sign in again.") },
            confirmButton = { Action("Sign out", primary = true) {
                signingOut = null
                work.addonCall("Signed out of ${addon.name}.") { client.signOut(addon.id) }
            } },
            dismissButton = { Action("Cancel") { signingOut = null } })
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
                        addon.installedVersion != null && !addon.updateAvailable ->
                            Text("Installed", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                        !addon.compatible -> Text("Needs a newer version of this app", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        addon.installedVersion == null -> Action("Install", !busy, primary = true) { confirming = addon }
                        else -> Action("Update to ${addon.version}", !busy, primary = true) {
                            val update = { work.addonCall("${addon.name} updated.") { client.install(repo.id, addon.id); load() } }
                            // The repository's list doesn't say which host settings it adds; the installed copy's status does.
                            val asks = Status.addons.value.host?.addons?.firstOrNull { it.id == addon.id && it.repo == repo.id }?.updateHostSettings
                            val warning = updateWarning(addon.installedHosts, addon.hosts, addon.installedRepo, repo.id, name, asks)
                            if (warning == null) update() else asking = PendingUpdate(addon.name, warning, update)
                        }
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
        Title(current?.name ?: "Settings", current?.let { reachLine(it.hosts, it.grantedHosts, it.fields.any { field -> field.type == "host" }) } ?: "Addon settings")
        when {
            current == null -> Panel { Text(problem.ifBlank { "Loading…" }) }
            current.fields.isEmpty() -> Panel { Text("This addon has no settings.") }
            else -> Panel {
                current.fields.forEach { field ->
                    val value = input[field.key].orEmpty()
                    val change = { text: String -> input = input + (field.key to text) }
                    val label = field.label + if (field.required) " *" else ""
                    val error = errors[field.key]
                    val hint = error ?: listOfNotNull(field.help?.ifBlank { null }, when {
                        field.type == "secret" && secretSaved(current, field.key) -> "Saved. Leave it blank to keep it."
                        field.type == "list" -> "One per line"
                        field.type == "host" && field.help.isNullOrBlank() -> "Like 192.168.1.20:8096"
                        else -> null
                    }).joinToString(". ")
                    when (field.type) {
                        "bool" -> Toggle(label, value == "true") { change(it.toString()) }
                        // One button per choice: a remote moves across them, a finger taps one.
                        "select" -> {
                            Text(label)
                            val shown = value.ifEmpty { (field.default as? JsonPrimitive)?.contentOrNull.orEmpty() }
                            Buttons { field.options.forEach { option ->
                                Action(if (option.value == shown) "✓ ${option.label}" else option.label, primary = option.value == shown) { change(option.value) }
                            } }
                        }
                        else -> Input(label, value, secret = field.type == "secret", hint = hint,
                            multiline = field.type == "list", number = field.type == "number", link = field.type == "host", error = error != null, onChange = change)
                    }
                    if ((field.type == "bool" || field.type == "select") && hint.isNotBlank())
                        Text(hint, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (checked.errors.isEmpty()) work.addonCall("Settings saved.") {
                    try { client.configure(id, checked.body) }
                    catch (e: ApiFailure) {
                        // A refused value goes next to its field: "Server: that is this device, …".
                        if (e.status == 400) fieldError(current, e.message.orEmpty())?.let { errors = mapOf(it) }
                        throw e
                    }
                    // What it can reach follows the addresses just saved.
                    runCatching { client.settings(id) }.onSuccess { settings = it }
                }
            }
        }
        if (errors.isNotEmpty()) Text("Fix the fields marked above.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    }
}

/** "3 left out" and why: entries the last sync skipped, which show nowhere else. */
fun leftOut(warnings: Warnings): String? = warnings.count.takeIf { it > 0 }?.let { "$it left out" }

/** A log line as the log page shows and shares it: the time of day, then the line, secrets hidden. */
fun logText(line: AddonLogLine, format: SimpleDateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())): String =
    "${format.format(Date(line.at * 1000))} ${redact(line.line)}"

/**
 * One addon's own log, newest first, one focusable line each so a remote scrolls it, and what its
 * last sync left out. The only way to see why an addon fails on a TV.
 */
@Composable fun AddonLogScreen(id: String, close: () -> Unit) {
    val context = LocalContext.current
    val addons by Status.addons.collectAsStateWithLifecycle()
    val addon = addons.host?.addons?.firstOrNull { it.id == id }
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var problem by remember { mutableStateOf("") }
    LaunchedEffect(id) {
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        while (true) {
            withContext(Dispatchers.IO) { runCatching { client.log(id) } }
                .onSuccess { log -> lines = log.map { logText(it, format) }.asReversed(); problem = "" }
                .onFailure { problem = it.message ?: "Can't read its log." }
            delay(3_000)
        }
    }
    BackHandler { close() }
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    val name = addon?.name ?: id
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Title("$name log", "What it said lately, newest first. Keys and passwords are hidden.") }
        item {
            Buttons {
                Action("Back", modifier = Modifier.focusRequester(start)) { close() }
                Action("Share", !lines.isNullOrEmpty()) {
                    // Fire TV has no share sheet.
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, lines.orEmpty().asReversed().joinToString("\n")), "Share $name's log")) }
                }
            }
        }
        val warnings = addon?.warnings ?: Warnings()
        if (warnings.count > 0) item {
            Panel(Modifier.focusable()) {
                val titles = if (warnings.count == 1L) "entry" else "entries"
                Text("The last sync left out ${warnings.count} $titles", fontWeight = FontWeight.SemiBold)
                warnings.first.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (warnings.count > warnings.first.size) Text("The rest are in the log below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (problem.isNotBlank()) item { Text(problem, color = MaterialTheme.colorScheme.error) }
        when {
            lines == null -> if (problem.isBlank()) item { Text("Loading…") }
            lines!!.isEmpty() -> item { Text("Nothing yet.", modifier = Modifier.focusable()) }
            else -> items(lines!!) { line -> Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.focusable()) }
        }
    }
}
