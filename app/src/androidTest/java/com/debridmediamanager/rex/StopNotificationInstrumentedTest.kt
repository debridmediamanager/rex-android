package com.debridmediamanager.rex

import android.Manifest
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class StopNotificationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!check()) { assertTrue(what, System.currentTimeMillis() < until); Thread.sleep(200) }
    }
    private fun ours() = context.getSystemService(NotificationManager::class.java).activeNotifications.map { it.notification.extras.getString("android.text") }

    // Stop used to leave a "Stopped" notification behind that could not be swiped away: the state
    // change re-posted it after the service had ended (emulator, 0.3.0, 2026-11-14).
    @Test fun stoppingRexTakesItsNotificationAway() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val runtime = context.runtime
        val (port, wanted, setup) = Triple(runtime.port, runtime.wanted, runtime.setupDone)
        try {
            runtime.port = ServerSocket(0).use { it.localPort }
            runtime.setupDone = true
            RexService.send(context)
            waitFor("rex did not start") { Status.rex.value.running }
            waitFor("rex showed no notification while running") { ours().isNotEmpty() }
            RexService.send(context, RexService.STOP)
            waitFor("rex did not stop") { !Status.rex.value.running }
            waitFor("the notification stayed after Stop: ${ours()}") { ours().isEmpty() }
            Thread.sleep(3_000) // a late state update must not bring it back
            assertTrue("a notification came back after Stop: ${ours()}", ours().isEmpty())
        } finally {
            runtime.port = port; runtime.setupDone = setup; runtime.wanted = wanted
        }
    }
}
