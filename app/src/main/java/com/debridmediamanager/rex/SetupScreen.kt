package com.debridmediamanager.rex

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A text field the remote can pass through: on TV, Select starts editing and Back stops it, so the
 * D-pad moves between fields instead of the cursor moving inside one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun Input(label: String, value: String, secret: Boolean = false, hint: String = "", multiline: Boolean = false,
    number: Boolean = false, link: Boolean = false, error: Boolean = false, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val television = LocalTelevision.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var sawKeyboard by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    val finishEditing = { editing = false; sawKeyboard = false; keyboard?.hide(); Unit }
    LaunchedEffect(television, editing) {
        if (television && editing) { requester.requestFocus(); keyboard?.show() }
    }
    LaunchedEffect(television, editing, imeVisible) {
        if (television && editing) {
            if (imeVisible) sawKeyboard = true
            else if (sawKeyboard) finishEditing()
        }
    }
    BackHandler(television && editing) { finishEditing() }
    val help = if (television) listOf(hint.trimEnd('.'), "Press Select to type").filter(String::isNotBlank).joinToString(". ") + "." else hint
    val inputModifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
        fieldFocused = it.isFocused
        if (!it.hasFocus) { editing = false; sawKeyboard = false }
    }.onPreviewKeyEvent { event ->
        if (!television || !fieldFocused || editing) false
        else {
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up; Key.DirectionDown -> FocusDirection.Down
                Key.DirectionLeft -> FocusDirection.Left; Key.DirectionRight -> FocusDirection.Right
                else -> null
            }
            when {
                direction != null -> {
                    if (event.type == KeyEventType.KeyDown) focus.moveFocus(direction)
                    true
                }
                event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter) -> {
                    if (event.type == KeyEventType.KeyUp) editing = true
                    true
                }
                else -> false
            }
        }
    }
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = inputModifier,
        readOnly = television && !editing,
        singleLine = !multiline, minLines = if (multiline) 3 else 1, maxLines = if (multiline) 10 else 1,
        keyboardOptions = KeyboardOptions(
            keyboardType = when { secret -> KeyboardType.Password; number -> KeyboardType.Number; link -> KeyboardType.Uri; else -> KeyboardType.Text },
            // A keyboard that corrects a link or a key breaks it ("addons" became "add-ons").
            autoCorrectEnabled = if (secret || link) false else null,
            imeAction = if (television && !multiline) ImeAction.Done else ImeAction.Default),
        keyboardActions = if (television) KeyboardActions(onDone = { finishEditing() }) else KeyboardActions.Default,
        supportingText = if (help.isNotBlank()) ({ Text(help) }) else null, isError = error,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret && !television) ({ TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } }) else null)
    // Outside the field's key handler: an inline icon would send Select through the field's edit action.
    if (secret && television) Action(if (visible) "Hide" else "Show") { visible = !visible }
}

@Composable fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    // A switch shows no focus a remote can see; on TV it is a button that says its state.
    if (LocalTelevision.current) return Action("$label: ${if (value) "On" else "Off"}") { onChange(!value) }
    // The whole row toggles, so tapping the label works too.
    Row(Modifier.fillMaxWidth().toggleable(value, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp, end = 12.dp))
        Switch(value, null)
    }
}

/** Notifications and the battery exemption: both answers come back from system screens. */
@Composable fun BackgroundPermissions() {
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(true) }
    var backgroundAllowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        backgroundAllowed = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAllowed = it }
    if (Build.VERSION.SDK_INT >= 33) Action(if (notificationsAllowed) "✓ Notifications allowed" else "Allow notifications", !notificationsAllowed) {
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // The battery list rather than a direct request: Play allows REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    // only for a few kinds of app, and a media server is not one of them.
    Action(if (backgroundAllowed) "✓ Can keep running in the background" else "Let rex keep running in the background", !backgroundAllowed) {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } }
    }
    if (!backgroundAllowed) Text("In the list, show all apps, pick rex and choose Don't optimize.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun SetupScreen(work: Work, busy: Boolean, finished: () -> Unit) {
    val context = LocalContext.current
    val runtime = context.runtime
    val rex by Status.rex.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(if (rex.running) 1 else 0) }
    var port by remember { mutableStateOf(runtime.port.toString()) }
    var name by remember { mutableStateOf(runtime.serverName) }
    var portTaken by remember { mutableStateOf(false) }
    LaunchedEffect(port, rex.running) {
        portTaken = withContext(Dispatchers.IO) { parsePort(port)?.let { !rex.running && portInUse(it) } ?: false }
    }
    // Start rex, which had focus, goes away with step 1: without this a remote finds nothing lit there.
    val next = remember { FocusRequester() }
    LaunchedEffect(step) { if (step == 1) runCatching { next.requestFocus() } }
    ScrollPage {
        Text("${step + 1} / 2", color = MaterialTheme.colorScheme.primary)
        if (step == 0) {
            Title("Welcome to rex.", "A media server for the Plex apps, right on this ${if (LocalTelevision.current) "TV" else "device"}.")
            Panel {
                Text("rex shows up in the Plex apps like any Plex server. Addons bring in what to watch.")
                Text("It runs in the background with a notification you can stop it from.")
                BackgroundPermissions()
            }
            Panel {
                Input("Name", name, hint = "What the Plex apps call this server") { name = it }
                Input("Port", port, number = true, hint = when {
                    parsePort(port) == null -> "Use a number from 1024 to 65535"
                    portTaken -> "Another app here already uses this port, maybe a Plex server. Try 32500."
                    else -> "32400 unless another Plex server runs on this device"
                }) { port = it }
            }
            Action("Start rex", !busy && parsePort(port) != null && !portTaken && name.isNotBlank(), primary = true) {
                runtime.port = parsePort(port)!!
                runtime.serverName = name
                RexService.send(context)
                step = 1
            }
        } else {
            Title("Sign in to Plex.", "Then open any Plex app signed in to the same account.")
            if (!rex.reachable) Panel { Text(listOf(rex.phase, rex.detail).filter(String::isNotBlank).joinToString(" · ")) }
            PlexPanel(work, busy)
            Buttons {
                Action("Back", !busy) { step = 0 }
                Action(if (rex.claimed) "Done" else "Skip for now", !busy, primary = rex.claimed, modifier = Modifier.focusRequester(next)) { finished() }
            }
        }
    }
}
