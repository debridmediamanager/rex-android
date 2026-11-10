package com.debridmediamanager.rex

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Each program the APK ships has to start from the installed app's nativeLibraryDir: Android
 * refuses to execute anything in app storage, and an installer that keeps libraries inside the
 * APK leaves nothing there to execute.
 */
@RunWith(AndroidJUnit4::class)
class NativeProgramsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime get() = context.runtime

    private fun run(program: File, vararg arguments: String): String {
        assertTrue("${program.name} is not an executable file in ${program.parent}", program.canExecute())
        val process = ProcessBuilder(listOf(program.path) + arguments).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { readProcessOutput(process.inputStream) { synchronized(output) { output.appendLine(it) } } }.apply { start() }
        assertTrue("${program.name} did not finish", process.waitFor(60, TimeUnit.SECONDS))
        reader.join(5_000)
        assertEquals("${program.name} failed: $output", 0, process.exitValue())
        return output.toString()
    }

    @Test fun rexRuns() = assertTrue(run(runtime.rexBinary, "--help").contains("--friendly-name"))
    @Test fun ffmpegRuns() = assertTrue(run(runtime.ffmpeg, "-hide_banner", "-encoders").contains("h264_mediacodec"))
    @Test fun ffprobeRuns() = assertTrue(run(runtime.ffprobe, "-version").startsWith("ffprobe version"))
    @Test fun addonHostRuns() = assertTrue(run(runtime.addonsBinary, "--version").isNotBlank())

    // rex with the app's own environment (no /etc/ssl on Android: it reads the exported trust bundle)
    // answers on its port. A scratch folder keeps this away from the app's own library.
    @Test fun rexServesItsIdentity() {
        val port = ServerSocket(0).use { it.localPort }
        val scratch = File(context.cacheDir, "rex-test-${System.nanoTime()}").apply { mkdirs() }
        val process = ProcessBuilder(runtime.rexBinary.path, "--port", "$port", "--friendly-name", "rex test").directory(scratch)
            .apply { environment().putAll(runtime.nativeEnvironment()); redirectErrorStream(true) }.start()
        val log = StringBuffer()
        Thread { readProcessOutput(process.inputStream) { log.appendLine(it) } }.start()
        try {
            val until = System.currentTimeMillis() + 60_000
            var body = ""
            while (body.isEmpty() && System.currentTimeMillis() < until && process.isAlive) {
                body = runCatching {
                    (URL("http://127.0.0.1:$port/identity").openConnection() as HttpURLConnection).run {
                        connectTimeout = 1_000; readTimeout = 5_000
                        inputStream.bufferedReader().use { it.readText() }
                    }
                }.getOrDefault("")
                if (body.isEmpty()) Thread.sleep(250)
            }
            assertTrue("rex did not answer /identity:\n$log", body.contains("machineIdentifier"))
        } finally {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            scratch.deleteRecursively()
        }
    }

    // rex's conversions encode on MediaCodec (this FFmpeg has no software H.264 encoder), and an
    // app's own process is what has to reach it. MPEG-TS: Amlogic's encoder hangs on muxers that
    // want global headers (MP4, DASH, Matroska), which this does not measure.
    @Test fun ffmpegEncodesOnMediaCodec() {
        val output = File(context.cacheDir, "mediacodec-${System.nanoTime()}.ts")
        try {
            run(runtime.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=640x360:r=24",
                "-t", "4", "-vf", "format=yuv420p", "-c:v", "h264_mediacodec", "-ndk_codec", "1", "-b:v", "1000k", "-g", "48",
                "-f", "mpegts", output.path)
            assertTrue("no video was written", output.length() > 10_000)
        } finally { output.delete() }
    }
}
