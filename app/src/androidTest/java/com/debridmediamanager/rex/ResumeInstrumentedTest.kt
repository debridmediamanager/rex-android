package com.debridmediamanager.rex

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class ResumeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 30_000
        while (!check()) { assertTrue(what, System.currentTimeMillis() < until); Thread.sleep(200) }
    }

    // A force stop (the system's, or a battery saver's) ends rex with nothing to restart it: seen as
    // STOPPED on opening the app, although nobody pressed Stop.
    @Test fun openingTheAppBringsBackARexThatWasLeftRunning() {
        val runtime = context.runtime
        val (port, wanted, setup) = Triple(runtime.port, runtime.wanted, runtime.setupDone)
        try {
            runtime.port = ServerSocket(0).use { it.localPort }
            runtime.setupDone = true
            runtime.wanted = true
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("rex did not start when the app opened") { Status.rex.value.running }
            }
        } finally {
            RexService.send(context, RexService.STOP)
            waitFor("rex did not stop") { !Status.rex.value.running }
            runtime.port = port; runtime.setupDone = setup; runtime.wanted = wanted
        }
    }
}
