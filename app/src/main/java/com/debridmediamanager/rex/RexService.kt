package com.debridmediamanager.rex

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File

data class RexState(val phase: String = "Stopped", val detail: String = "", val running: Boolean = false,
    val reachable: Boolean = false, val claimed: Boolean = false, val name: String = "")
data class AddonsState(val detail: String = "", val host: HostStatus? = null)

object Status {
    val rex = MutableStateFlow(RexState())
    val addons = MutableStateFlow(AddonsState())
}

/** Runs rex, then the addon host once rex answers, restarting either when it exits. */
class RexService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Pair<String?, Int>>(Channel.UNLIMITED)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Media server", NotificationManager.IMPORTANCE_LOW))
        val notification = notification("Starting")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
        scope.launch {
            Status.rex.collect { state ->
                getSystemService(NotificationManager::class.java).notify(1, notification(listOf(state.phase, state.detail).filter(String::isNotBlank).joinToString(" · ")))
            }
        }
        scope.launch {
            for ((action, startId) in commands) {
                when (action) {
                    STOP -> { runtime.wanted = false; halt(); stopSelf(startId) }
                    RESTART -> { halt(); begin() }
                    else -> begin()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Queue in arrival order: commands run one at a time on IO.
        commands.trySend(intent?.action to startId)
        return START_STICKY
    }

    private fun begin() {
        if (job?.isActive == true) return
        runtime.wanted = true
        // A rex left behind when Android killed the app holds its database and its port.
        stopLeftovers()
        val port = runtime.port
        val refusal = when {
            !runtime.rexBinary.canExecute() -> "This build of the app has no rex in it."
            portInUse(port) -> "Another app on this device already uses port $port, perhaps a Plex server. Pick another port in Settings."
            else -> null
        }
        if (refusal != null) {
            Status.rex.value = RexState("Can't start", refusal)
            Logs.add("app", refusal)
            runtime.wanted = false
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }
        Status.rex.value = RexState("Starting", "rex is starting on port $port.", running = true)
        job = scope.launch {
            launch {
                supervise(runtime::rexProcess, { Logs.add("rex", it) },
                    onExit = { reason, wait ->
                        Logs.add("app", "rex $reason")
                        Status.rex.value = RexState("Restarting", "rex $reason. Trying again in ${wait / 1000} s.", running = true)
                    },
                    whileRunning = { pollRex() }, kill = { stopLeftovers("librex.so") })
            }
            launch { runAddons() }
        }
    }

    private suspend fun pollRex() {
        while (true) {
            val claimed = runCatching { rexClaimed(runtime.rexUrl) }
            // Renamed in a Plex app (Friendly name): the app and its addons follow rex.
            if (claimed.getOrNull() == true) runCatching { rexName(runtime.rexUrl, rexToken(runtime.rexDirectory)) }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != runtime.serverName }?.let { runtime.serverName = it }
            Status.rex.value = claimed.fold(
                { RexState("Running", if (it) "Ready for the Plex apps." else "Sign in to Plex to see it in the Plex apps.",
                    running = true, reachable = true, claimed = it, name = runtime.serverName) },
                { RexState("Starting", "Waiting for rex to answer…", running = true) })
            delay(5_000)
        }
    }

    private suspend fun runAddons() {
        if (!runtime.addonsBinary.canExecute()) {
            Status.addons.value = AddonsState("Addons aren't included in this build.")
            return
        }
        Status.addons.value = AddonsState("Addons start once rex is running.")
        Status.rex.first { it.reachable }
        if (portInUse(ADDONS_PORT)) {
            Status.addons.value = AddonsState("Addons can't start: another app on this device uses port $ADDONS_PORT.")
            return
        }
        val client = AddonsClient()
        supervise(runtime::addonsProcess, { Logs.add("addons", it) },
            onExit = { reason, wait ->
                Logs.add("app", "addon host $reason")
                Status.addons.value = AddonsState("The addon host $reason. Trying again in ${wait / 1000} s.")
            },
            whileRunning = {
                while (true) {
                    Status.addons.value = runCatching { AddonsState(host = client.status()) }
                        .getOrElse { AddonsState("Addons are starting…") }
                    delay(5_000)
                }
            }, kill = { stopLeftovers("librexaddons.so") })
    }

    private suspend fun halt() {
        val running = job
        job = null
        running?.cancelAndJoin()
        stopLeftovers()
        Status.rex.value = RexState()
        Status.addons.value = AddonsState()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RexService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_status).setContentTitle("rex")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "Stop", stop).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        commands.close()
        scope.cancel()
        stopLeftovers()
        Status.rex.value = RexState()
        Status.addons.value = AddonsState()
        super.onDestroy()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopLeftovers(); stopSelf() }

    companion object {
        const val START = "rex.START"; const val STOP = "rex.STOP"; const val RESTART = "rex.RESTART"
        private const val CHANNEL = "rex_server"
        fun send(context: Context, action: String = START) {
            ContextCompat.startForegroundService(context, Intent(context, RexService::class.java).setAction(action))
        }
    }
}

/**
 * Stops this app's own copies of the programs: SIGTERM, then SIGKILL after 5 s. Only the app's
 * own processes are readable under /proc, so another app's rex is never touched.
 */
fun stopLeftovers(vararg programs: String = arrayOf("librex.so", "librexaddons.so")) {
    val leftovers = File("/proc").listFiles().orEmpty().mapNotNull { entry ->
        val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
        val exe = runCatching { Os.readlink("/proc/$pid/exe") }.getOrNull() ?: return@mapNotNull null
        pid.takeIf { programs.any { exe.removeSuffix(" (deleted)").endsWith("/$it") } }
    }
    if (leftovers.isEmpty()) return
    for (pid in leftovers) runCatching { Os.kill(pid, OsConstants.SIGTERM) }
    repeat(50) {
        if (leftovers.all { runCatching { Os.kill(it, 0) }.isFailure }) return
        Thread.sleep(100)
    }
    for (pid in leftovers) runCatching { Os.kill(pid, OsConstants.SIGKILL) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val runtime = context.runtime
        val start = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> runtime.startAtBoot
            Intent.ACTION_MY_PACKAGE_REPLACED -> runtime.wanted
            else -> false
        }
        if (start && runtime.setupDone) runCatching { RexService.send(context) }
            .onFailure { Logs.add("app", "Android refused to start rex in the background: ${it.javaClass.simpleName}") }
    }
}

class RexTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            Status.rex.map { it.running }.distinctUntilChanged().collect { running ->
                qsTile?.apply { state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
            }
        }
    }

    override fun onStopListening() { listening?.cancel(); listening = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onClick() {
        super.onClick()
        if (runtime.setupDone) RexService.send(this, if (Status.rex.value.running) RexService.STOP else RexService.START)
        else {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            else startActivity(intent)
        }
    }
}
