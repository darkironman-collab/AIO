package com.codex.aio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.system.Os
import androidx.core.app.NotificationCompat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

class LocalStackService : Service() {
    companion object {
        @Volatile var running = false
            private set
        @Volatile var ready = false
            private set
        @Volatile var statusMessage = "Stopped"
            private set
        @Volatile var lastError: String? = null
            private set

        const val CONFIG_URL = "http://127.0.0.1:3001/stremio/configure"
    }

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var runtimeProcess: Process? = null
    private lateinit var logFile: File

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1001, notification("Preparing original AIOStreams…"))
        worker.execute { startOriginalAioStreams() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ready = false
        running = false
        statusMessage = "Stopped"
        runCatching { runtimeProcess?.destroy() }
        runCatching { runtimeProcess?.destroyForcibly() }
        runtimeProcess = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startOriginalAioStreams() {
        try {
            lastError = null
            running = true
            ready = false
            statusMessage = "Installing AIOStreams runtime…"
            updateNotification(statusMessage)

            val rootfs = File(filesDir, "aiostreams-rootfs")
            installRootfsIfNeeded(rootfs)

            statusMessage = "Starting original AIOStreams…"
            updateNotification(statusMessage)

            val secret = getOrCreateSecret()
            val startScript = File(rootfs, "app/aio-android-start.sh")
            startScript.parentFile?.mkdirs()
            startScript.writeText(
                """#!/bin/sh
export PORT=3001
export BASE_URL=http://127.0.0.1:3001
export INTERNAL_URL=http://127.0.0.1:3001
export SECRET_KEY=$secret
export DATABASE_URI=sqlite://./data/db.sqlite
export NODE_ENV=production
export NODE_OPTIONS='--max-semi-space-size=8 --expose-gc'
export LD_PRELOAD=/usr/local/lib/libmimalloc.so.2
mkdir -p /app/data
exec /nodejs/bin/node /app/packages/server/dist/server.js
"""
            )
            runCatching { Os.chmod(startScript.absolutePath, 0b111101101) } // 0755

            val proot = File(applicationInfo.nativeLibraryDir, "libproot.so")
            if (!proot.exists()) error("Bundled PRoot runtime is missing")

            logFile = File(filesDir, "aiostreams.log")
            val command = listOf(
                proot.absolutePath,
                "--link2symlink",
                "-0",
                "-r", rootfs.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-w", "/app",
                "/bin/sh", "/app/aio-android-start.sh"
            )

            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            pb.environment()["PROOT_NO_SECCOMP"] = "1"
            pb.environment()["PROOT_TMP_DIR"] = cacheDir.absolutePath
            runtimeProcess = pb.start()

            Thread {
                runCatching {
                    runtimeProcess?.inputStream?.bufferedReader()?.use { reader ->
                        FileOutputStream(logFile, true).bufferedWriter().use { out ->
                            reader.forEachLine { line ->
                                out.appendLine(line)
                                out.flush()
                            }
                        }
                    }
                }
            }.start()

            var attempts = 0
            while (attempts < 120 && runtimeProcess?.isAlive == true) {
                if (isServerReady()) {
                    ready = true
                    statusMessage = "AIOStreams ready · 127.0.0.1:3001"
                    updateNotification(statusMessage)
                    return
                }
                attempts++
                Thread.sleep(1000)
            }

            val exit = if (runtimeProcess?.isAlive == true) null else runCatching { runtimeProcess?.exitValue() }.getOrNull()
            error("AIOStreams did not start${exit?.let { " (exit $it)" } ?: ""}${lastLogHint()}")
        } catch (t: Throwable) {
            ready = false
            running = false
            lastError = t.message ?: t.javaClass.simpleName
            statusMessage = "Error: ${lastError}"
            updateNotification(statusMessage)
        }
    }

    private fun installRootfsIfNeeded(rootfs: File) {
        val marker = File(rootfs, ".aio-android-ready")
        if (marker.exists() && File(rootfs, "nodejs/bin/node").exists() && File(rootfs, "app/packages/server/dist/server.js").exists()) {
            return
        }

        if (rootfs.exists()) rootfs.deleteRecursively()
        rootfs.mkdirs()

        // Android's asset packager transparently expands .tar.gz and stores it
        // in the APK as assets/aiostreams-rootfs.tar. AssetManager therefore
        // returns the raw POSIX tar stream here — no GZIPInputStream is needed.
        assets.open("aiostreams-rootfs.tar").use { raw ->
            TarArchiveInputStream(BufferedInputStream(raw, 1024 * 1024)).use { tar ->
                val deferredHardLinks = mutableListOf<Pair<File, String>>()
                var entry: TarArchiveEntry? = tar.nextTarEntry
                while (entry != null) {
                    extractEntry(rootfs, entry, tar, deferredHardLinks)
                    entry = tar.nextTarEntry
                }
                for ((link, targetName) in deferredHardLinks) {
                    val target = safeFile(rootfs, targetName.trimStart('/'))
                    link.parentFile?.mkdirs()
                    runCatching { if (link.exists()) link.delete() }
                    if (target.exists()) {
                        runCatching { Os.link(target.absolutePath, link.absolutePath) }
                            .getOrElse { target.copyTo(link, overwrite = true) }
                    }
                }
            }
        }
        marker.writeText("official AIOStreams runtime\n")
    }

    private fun extractEntry(
        rootfs: File,
        entry: TarArchiveEntry,
        tar: TarArchiveInputStream,
        deferredHardLinks: MutableList<Pair<File, String>>
    ) {
        val name = entry.name.removePrefix("./").trimStart('/')
        if (name.isBlank()) return
        val out = safeFile(rootfs, name)

        when {
            entry.isDirectory -> {
                out.mkdirs()
                chmodBestEffort(out, entry.mode)
            }
            entry.isSymbolicLink -> {
                out.parentFile?.mkdirs()
                runCatching { if (out.exists() || out.isSymbolicLink()) out.delete() }
                runCatching { Os.symlink(entry.linkName, out.absolutePath) }
            }
            entry.isLink -> {
                deferredHardLinks += out to entry.linkName
            }
            entry.isFile -> {
                out.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(out), 1024 * 1024).use { output ->
                    tar.copyTo(output, 1024 * 1024)
                }
                chmodBestEffort(out, entry.mode)
            }
            else -> Unit // device nodes/FIFOs are supplied by PRoot bindings
        }
    }

    private fun File.isSymbolicLink(): Boolean = runCatching {
        canonicalFile != absoluteFile
    }.getOrDefault(false)

    private fun safeFile(root: File, relative: String): File {
        val file = File(root, relative)
        val rootPath = root.canonicalPath + File.separator
        val filePath = file.canonicalFile.path
        if (filePath != root.canonicalPath && !filePath.startsWith(rootPath)) {
            error("Unsafe path in runtime archive: $relative")
        }
        return file
    }

    private fun chmodBestEffort(file: File, mode: Int) {
        if (mode > 0) runCatching { Os.chmod(file.absolutePath, mode) }
    }

    private fun getOrCreateSecret(): String {
        val prefs = getSharedPreferences("aio_runtime", MODE_PRIVATE)
        prefs.getString("secret_key", null)?.let { if (it.length == 64) return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val secret = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("secret_key", secret).apply()
        return secret
    }

    private fun isServerReady(): Boolean {
        return runCatching {
            val connection = URL(CONFIG_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 800
                connection.readTimeout = 800
                connection.instanceFollowRedirects = true
                connection.requestMethod = "GET"
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun lastLogHint(): String {
        if (!::logFile.isInitialized || !logFile.exists()) return ""
        return runCatching {
            val lines = logFile.readLines().takeLast(4).joinToString(" | ")
            if (lines.isBlank()) "" else ": $lines"
        }.getOrDefault("")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("aio_original", "AIOStreams Server", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1001, notification(text))
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, "aio_original")
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("AIOStreams")
        .setContentText(text)
        .setOngoing(true)
        .build()
}
