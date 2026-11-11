package com.debridmediamanager.rex

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.system.Os
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

const val DEFAULT_PORT = 32400

class RexApp : Application() {
    val runtime by lazy { Runtime(this) }
    override fun onCreate() {
        super.onCreate()
        Logs.persistTo(File(filesDir, "logs"))
    }
}
val Context.runtime get() = (applicationContext as RexApp).runtime

/** Where the app keeps its settings and how it starts the programs it ships. */
class Runtime(val context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    // Android executes nothing from an app's writable storage: the programs run from the
    // folder the installer extracted the APK's native libraries into.
    private val programs = File(context.applicationInfo.nativeLibraryDir)
    val rexBinary = File(programs, "librex.so")
    val addonsBinary = File(programs, "librexaddons.so")
    val ffmpeg = File(programs, "libffmpeg.so")
    val ffprobe = File(programs, "libffprobe.so")
    val rexDirectory = File(context.filesDir, "rex")

    var port: Int
        get() = preferences.getInt("port", DEFAULT_PORT)
        set(value) = preferences.edit().putInt("port", value).apply()
    var serverName: String
        get() = preferences.getString("name", null)?.takeIf { it.isNotBlank() } ?: deviceName()
        set(value) = preferences.edit().putString("name", value.trim()).apply()
    var startAtBoot: Boolean
        get() = preferences.getBoolean("boot", false)
        set(value) = preferences.edit().putBoolean("boot", value).apply()
    /** Whether the person wants rex running: it comes back after an app update. */
    var wanted: Boolean
        get() = preferences.getBoolean("running", false)
        set(value) = preferences.edit().putBoolean("running", value).apply()
    var setupDone: Boolean
        get() = preferences.getBoolean("setup", false)
        set(value) = preferences.edit().putBoolean("setup", value).apply()

    val rexUrl get() = "http://127.0.0.1:$port"
    /** rex keeps the name it was first started with; after that it is renamed in Plex. */
    val named get() = File(rexDirectory, "rex.db").exists()

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && it.isSiteLocalAddress }.mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    fun rexProcess(): Process {
        rexDirectory.mkdirs()
        // Android names every device "localhost"; the name only seeds a new library.
        return ProcessBuilder(rexBinary.path, "--port", "$port", "--friendly-name", serverName).directory(rexDirectory).apply {
            environment().putAll(nativeEnvironment())
            environment()["REX_TRANSCODER"] = if (ffmpeg.canExecute()) ffmpeg.path else "off"
            environment()["REX_TRANSCODE_DIR"] = File(context.cacheDir, "rex-transcode").path
            redirectErrorStream(true)
        }.start()
    }

    fun addonsProcess(): Process {
        val data = File(context.filesDir, "addons").apply { mkdirs() }
        val arguments = mutableListOf(addonsBinary.path, "--listen", "127.0.0.1:$ADDONS_PORT", "--rex", rexUrl,
            "--data", data.path, "--ffprobe", ffprobe.path, "--device-name", serverName)
        bundledAddons()?.let { arguments += listOf("--bundled", it.path) }
        // Read on the host's first start only; afterwards the person's own list stands.
        BuildConfig.DEFAULT_REPOS.forEach { arguments += listOf("--default-repo", it) }
        return ProcessBuilder(arguments).directory(data).apply {
            environment().putAll(nativeEnvironment())
            environment()[AddonsToken.ENV] = AddonsToken.fresh()
            redirectErrorStream(true)
        }.start()
    }

    /**
     * The addons the APK carries (assets/addons/<id>/manifest.json + main.js), copied out once per
     * app version: the host reads them from a folder.
     */
    @Synchronized fun bundledAddons(): File? {
        val assets = context.assets
        val ids = assets.list("addons").orEmpty()
        if (ids.isEmpty()) return null
        val target = File(context.filesDir, "bundled-addons")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).let {
            "${it.longVersionCodeCompat}:${it.lastUpdateTime}"
        }
        if (target.isDirectory && preferences.getString("bundled", null) == version) return target
        val fresh = File(context.filesDir, "bundled-addons.new").apply { deleteRecursively(); mkdirs() }
        fun copy(path: String, into: File) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) assets.open(path).use { input -> into.outputStream().use { input.copyTo(it) } }
            else { into.mkdirs(); children.forEach { copy("$path/$it", File(into, it)) } }
        }
        ids.forEach { copy("addons/$it", File(fresh, it)) }
        target.deleteRecursively()
        check(fresh.renameTo(target)) { "Cannot unpack the bundled addons" }
        preferences.edit().putString("bundled", version).apply()
        return target
    }

    @Synchronized fun nativeEnvironment(): Map<String, String> = mapOf(
        "HOME" to context.filesDir.path,
        "TMPDIR" to File(context.cacheDir, "tmp").apply { mkdirs() }.path,
        "SSL_CERT_FILE" to platformTrustBundle().path,
    )

    private fun platformTrustBundle(): File {
        // Android has no /etc/ssl and OpenSSL or rustls cannot read Conscrypt's store. Export the
        // app's trust anchors as PEM on every start, so the programs trust what Android trusts.
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val certificates = factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
        check(certificates.isNotEmpty()) { "Android provided no trusted certificate authorities" }
        val pem = certificates.joinToString("") { certificate ->
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getEncoder().encodeToString(certificate.encoded).chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"
        }
        // A program can read it long after this returns and Android may purge cacheDir meanwhile.
        val file = File(context.filesDir, "ca-certificates.pem")
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try {
            Os.fchmod(output.fd, 0b110000000)
            output.write(pem.toByteArray(Charsets.US_ASCII))
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
        return file
    }
}

@Suppress("DEPRECATION")
private val android.content.pm.PackageInfo.longVersionCodeCompat: Long
    get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
