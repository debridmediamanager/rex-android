package com.debridmediamanager.rex

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

val LocalTelevision = staticCompositionLocalOf { false }
private val RexColors = darkColorScheme(primary = Color(0xFFFF8A65), onPrimary = Color(0xFF3B0D00),
    background = Color(0xFF131418), surface = Color(0xFF1C1E24), surfaceVariant = Color(0xFF2A2D35),
    onBackground = Color(0xFFE8E6E3), onSurface = Color(0xFFE8E6E3), secondary = Color(0xFFFFB59C),
    error = Color(0xFFFF8A80))

/** Runs a blocking job off the main thread and shows its answer, or its error, in the notice bar. */
typealias Work = (suspend () -> String) -> Unit

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val television = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        setContent {
            CompositionLocalProvider(LocalTelevision provides television) {
                MaterialTheme(colorScheme = RexColors) {
                    val window = calculateWindowSizeClass(this)
                    RexScreen(wide = television || window.widthSizeClass != WindowWidthSizeClass.Compact)
                }
            }
        }
    }
}

@Composable fun RexScreen(wide: Boolean) {
    val runtime = LocalContext.current.runtime
    var page by rememberSaveable { mutableStateOf(if (runtime.setupDone) "Status" else "Setup") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val navigate: (String) -> Unit = { page = it; notice = "" }
    val work: Work = { job ->
        scope.launch {
            busy = true
            try { notice = withContext(Dispatchers.IO) { job() } }
            catch (e: Exception) { if (e is CancellationException) throw e; notice = e.message ?: "Something went wrong" }
            finally { busy = false }
        }
    }
    val pages = listOf("Status", "Addons", "Settings", "Logs")
    BackHandler(page != "Status" && page != "Setup") { navigate("Status") }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.weight(1f)) {
                if (wide && page != "Setup") Column(Modifier.width(if (LocalTelevision.current) 210.dp else 170.dp)
                    .fillMaxHeight().background(Color(0xFF17181D)).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("rex", color = MaterialTheme.colorScheme.primary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    pages.forEach { title -> Action(if (page == title) "› $title" else title, modifier = Modifier.fillMaxWidth()) { navigate(title) } }
                }
                Column(Modifier.weight(1f)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (notice.isNotEmpty()) Text(notice, Modifier.fillMaxWidth().background(Color(0xFF33261F)).padding(16.dp))
                    Box(Modifier.weight(1f)) { when (page) {
                        "Setup" -> SetupScreen(work, busy) { runtime.setupDone = true; navigate("Status") }
                        "Status" -> StatusScreen(work, busy, navigate)
                        "Addons" -> AddonsScreen(work, busy)
                        "Settings" -> SettingsScreen(work, busy)
                        "Logs" -> LogsScreen()
                    } }
                }
            }
            if (!wide && page != "Setup") Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                pages.forEach { title ->
                    TextButton(onClick = { navigate(title) }, contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (page == title) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            contentColor = if (page == title) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                        Text(title, fontSize = 13.sp, maxLines = 1, fontWeight = if (page == title) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable fun StatusScreen(work: Work, busy: Boolean, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val runtime = context.runtime
    val rex by Status.rex.collectAsStateWithLifecycle()
    val addons by Status.addons.collectAsStateWithLifecycle()
    ScrollPage {
        Title("Your media server", "For the Plex apps on this ${if (LocalTelevision.current) "TV" else "device"} and at home.")
        Panel {
            Text(rex.phase.uppercase(), color = MaterialTheme.colorScheme.primary, letterSpacing = 3.sp)
            if (rex.detail.isNotBlank()) Text(rex.detail, fontSize = 20.sp)
            if (rex.reachable) {
                Text("Name: ${runtime.serverName}")
                runtime.lanAddresses().forEach { Text("At home: http://$it:${runtime.port}", color = MaterialTheme.colorScheme.secondary) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action("Start", !busy && !rex.running, primary = true) { RexService.send(context) }
                Action("Stop", rex.running) { RexService.send(context, RexService.STOP) }
                Action("Restart", rex.running) { RexService.send(context, RexService.RESTART) }
            }
        }
        PlexPanel(work, busy)
        Panel {
            Text("Addons", style = MaterialTheme.typography.titleLarge)
            val host = addons.host
            if (host == null) Text(addons.detail.ifBlank { "Addons start with rex." })
            else if (host.addons.isEmpty()) Text("No addons yet.")
            else host.addons.forEach { AddonLine(it) }
            Action("Manage addons") { navigate("Addons") }
        }
    }
}

@Composable fun AddonLine(addon: Addon) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(addon.name, fontWeight = FontWeight.SemiBold)
        Text(addonSummary(addon), color = MaterialTheme.colorScheme.onSurfaceVariant)
        addon.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

fun addonSummary(addon: Addon): String = listOfNotNull(
    if (!addon.enabled) "Off" else when (addon.state) { "syncing" -> "Syncing"; "error" -> "Problem"; else -> "Ready" },
    "${addon.items} ${if (addon.items == 1L) "title" else "titles"}",
    // Sent without a probe: rex holds them unshown until a later sync checks them.
    if (addon.held > 0) "${addon.held} not shown yet" else null,
    lastSync(addon)?.let { "synced $it" },
).joinToString(" · ")

/** `last_sync` is Unix seconds, or null before the first sync that worked. */
private fun lastSync(addon: Addon): String? = addon.lastSync?.takeIf { it > 0 }?.let(::ago)

/** Unix seconds as "just now" or "5 minutes ago". */
fun ago(seconds: Long): String =
    if (System.currentTimeMillis() - seconds * 1000 < 60_000) "just now"
    else android.text.format.DateUtils.getRelativeTimeSpanString(seconds * 1000).toString()

// The one-time Plex sign-in that claims rex, and the way into a Plex app once it is claimed.
@Composable fun PlexPanel(work: Work, busy: Boolean) {
    val context = LocalContext.current
    val runtime = context.runtime
    val rex by Status.rex.collectAsStateWithLifecycle()
    val code by PlexClaim.code.collectAsStateWithLifecycle()
    val outcome by PlexClaim.outcome.collectAsStateWithLifecycle()
    val television = LocalTelevision.current
    if (!rex.reachable) return
    Panel {
        Text("Plex", style = MaterialTheme.typography.titleLarge)
        // A finished sign-in speaks for itself below; only a failed one needs saying.
        if (outcome.isNotBlank() && !rex.claimed) Text(outcome, color = MaterialTheme.colorScheme.error)
        if (code.isNotBlank()) Text("On any phone or computer, go to plex.tv/link and enter $code", fontSize = 22.sp)
        if (!rex.claimed) {
            Text("Sign in once with your Plex account. rex then shows up in every Plex app signed in to it.")
            Action("Sign in to Plex", !busy && code.isBlank(), primary = true) { work {
                val page = PlexClaim.begin(runtime)
                // A TV has no browser worth using: the code on screen is the way in there.
                if (!television) withContext(Dispatchers.Main) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                ""
            } }
        } else {
            Text("Signed in. rex shows up in the Plex apps as ${runtime.serverName}.")
            val plex = remember { context.packageManager.getLaunchIntentForPackage("com.plexapp.android") }
            if (plex != null) Action("Open Plex", primary = true) { context.startActivity(plex) }
        }
    }
}

@Composable fun Action(label: String, enabled: Boolean = true, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (LocalTelevision.current) {
        // The TV theme's own defaults draw the focused button dark on a dark page: on a remote,
        // where focus is the only pointer, it has to stand out.
        val colors = MaterialTheme.colorScheme
        androidx.tv.material3.Button(onClick = onClick, enabled = enabled, modifier = modifier.padding(vertical = 3.dp),
            colors = androidx.tv.material3.ButtonDefaults.colors(
                containerColor = colors.surfaceVariant, contentColor = colors.onSurface,
                focusedContainerColor = colors.primary, focusedContentColor = colors.onPrimary,
                pressedContainerColor = colors.primary, pressedContentColor = colors.onPrimary,
                disabledContainerColor = colors.surface, disabledContentColor = colors.onSurface.copy(alpha = 0.4f))) {
            androidx.tv.material3.Text(label, fontSize = 18.sp)
        }
    } else if (primary) Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
}

@Composable fun Title(text: String, subtitle: String = "") {
    val size = if (LocalTelevision.current) 34.sp else 30.sp
    // Without its own line height a wrapped title inherits the body's and its lines overlap.
    Text(text, fontSize = size, lineHeight = size * 1.2f, fontWeight = FontWeight.SemiBold)
    if (subtitle.isNotEmpty()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
}

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}
