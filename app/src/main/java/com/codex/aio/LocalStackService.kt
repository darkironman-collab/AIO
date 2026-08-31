package com.codex.aio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalStackService : Service() {
    companion object {
        @Volatile var running: Boolean = false
            private set
        @Volatile var lastError: String? = null
            private set
    }

    private var server: MiniHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1001, notification("Starting AIO server…"))
        try {
            val core = AioCore(this)
            server = MiniHttpServer(3001) { req -> core.handle(req) }.also { it.start() }
            running = true
            lastError = null
            getSystemService(NotificationManager::class.java)
                .notify(1001, notification("AIO server running · 127.0.0.1:3001"))
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            running = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        running = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("aio_local", "AIO Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, "aio_local")
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("AIO")
        .setContentText(text)
        .setOngoing(true)
        .build()
}

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

data class HttpResponse(
    val status: Int = 200,
    val contentType: String = "application/json; charset=utf-8",
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        fun text(status: Int = 200, type: String = "text/plain; charset=utf-8", value: String) =
            HttpResponse(status, type, value.toByteArray(StandardCharsets.UTF_8))
    }
}

class MiniHttpServer(
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse
) {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        pool.execute {
            while (running.get()) {
                try {
                    val client = socket?.accept() ?: break
                    pool.execute { serve(client) }
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val path = target.substringBefore('?')
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1_048_576) ?: 0
            val body = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(body, off, len - off)
                if (n <= 0) break
                off += n
            }
            val req = HttpRequest(method, path, headers, if (off == len) body else body.copyOf(off))
            val res = try {
                if (method == "OPTIONS") HttpResponse(204, "text/plain", ByteArray(0)) else handler(req)
            } catch (t: Throwable) {
                HttpResponse.text(500, "application/json; charset=utf-8", JSONObject().put("error", t.message ?: "server_error").toString())
            }
            writeResponse(s, res)
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ArrayList<Byte>(128)
        var prev = -1
        while (true) {
            val cur = input.read()
            if (cur == -1) return if (out.isEmpty()) null else out.toByteArray().toString(StandardCharsets.UTF_8)
            if (prev == '\r'.code && cur == '\n'.code) {
                if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                return out.toByteArray().toString(StandardCharsets.UTF_8)
            }
            out += cur.toByte()
            prev = cur
            if (out.size > 16_384) return null
        }
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val reason = when (response.status) {
            200 -> "OK"; 204 -> "No Content"; 302 -> "Found"; 404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "OK"
        }
        val baseHeaders = linkedMapOf(
            "Content-Type" to response.contentType,
            "Content-Length" to response.body.size.toString(),
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store",
            "Connection" to "close"
        )
        baseHeaders.putAll(response.headers)
        val header = buildString {
            append("HTTP/1.1 ${response.status} $reason\r\n")
            baseHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(header)
        if (response.body.isNotEmpty()) out.write(response.body)
        out.flush()
    }
}

class AioCore(private val context: android.content.Context) {
    private val prefs = context.getSharedPreferences("aio_local", 0)
    private val workers = Executors.newFixedThreadPool(4)

    fun handle(req: HttpRequest): HttpResponse = when {
        req.method == "GET" && req.path == "/" -> HttpResponse(302, "text/plain", ByteArray(0), mapOf("Location" to "/stremio/configure"))
        req.method == "GET" && req.path == "/stremio/configure" -> html(configPage())
        req.method == "GET" && req.path == "/health" -> json(JSONObject()
            .put("status", "ready")
            .put("name", "AIO")
            .put("port", 3001)
            .put("configuredAddons", addonUrls().size)
            .toString())
        req.method == "GET" && req.path == "/manifest.json" -> json(manifestJson())
        req.method == "POST" && req.path == "/save" -> saveConfig(req)
        req.method == "GET" && req.path.matches(Regex("^/stream/(movie|series)/.+\\.json$")) -> streamResponse(req.path)
        else -> HttpResponse.text(404, "application/json; charset=utf-8", "{\"error\":\"not_found\"}")
    }

    private fun saveConfig(req: HttpRequest): HttpResponse {
        val form = parseForm(req.body.toString(StandardCharsets.UTF_8))
        val cleaned = form["addons"].orEmpty().lines()
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .joinToString("\n")
        prefs.edit().putString("addons", cleaned).apply()
        return HttpResponse(302, "text/plain", ByteArray(0), mapOf("Location" to "/stremio/configure"))
    }

    private fun addonUrls(): List<String> = prefs.getString("addons", "").orEmpty().lines()
        .map { it.trim() }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .distinct()

    private fun streamResponse(localPath: String): HttpResponse {
        val urls = addonUrls()
        if (urls.isEmpty()) return json(JSONObject().put("streams", JSONArray()).toString())

        val remotePaths = urls.map { manifest ->
            val base = if (manifest.endsWith("manifest.json")) manifest.removeSuffix("manifest.json") else manifest.trimEnd('/') + "/"
            base + localPath.trimStart('/')
        }

        val futures = remotePaths.map { url -> workers.submit<JSONArray?> { fetchStreams(url) } }
        val merged = JSONArray()
        futures.forEach { future ->
            runCatching { future.get(18, TimeUnit.SECONDS) }.getOrNull()?.let { arr ->
                for (i in 0 until arr.length()) merged.put(arr.get(i))
            }
        }
        return json(JSONObject().put("streams", merged).toString())
    }

    private fun fetchStreams(url: String): JSONArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AIO-Android/1.0")
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).optJSONArray("streams")
        } finally {
            conn.disconnect()
        }
    }

    private fun manifestJson(): String = JSONObject()
        .put("id", "com.codex.aio.local")
        .put("version", "1.0.0")
        .put("name", "AIO")
        .put("description", "Local Android AIO server")
        .put("resources", JSONArray().put("stream"))
        .put("types", JSONArray().put("movie").put("series"))
        .put("catalogs", JSONArray())
        .toString()

    private fun configPage(): String {
        val saved = escapeHtml(prefs.getString("addons", "").orEmpty())
        return """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>AIO Configuration</title>
              <style>
                :root{color-scheme:dark;--bg:#090d16;--panel:#121a29;--text:#eef4ff;--muted:#91a0b8;--accent:#8b5cf6}
                *{box-sizing:border-box}body{margin:0;background:linear-gradient(180deg,#090d16,#0d1320);color:var(--text);font:15px system-ui,sans-serif}
                main{max-width:760px;margin:auto;padding:28px 18px}.brand{font-weight:900;font-size:30px;letter-spacing:.5px}.brand span{color:var(--accent)}
                h1{margin:10px 0 4px}h2{font-size:17px}.muted{color:var(--muted)}
                section{background:var(--panel);padding:16px;border-radius:16px;margin:14px 0;border:1px solid #202c42}
                textarea{width:100%;min-height:170px;background:#090e18;color:white;border:1px solid #33425e;border-radius:12px;padding:12px;font:13px monospace}
                button{margin-top:10px;width:100%;border:0;border-radius:11px;padding:13px 16px;background:var(--accent);color:white;font-weight:800}
                code{display:block;overflow:auto;padding:10px;background:#080c14;border-radius:10px;color:#b7f7cf}
              </style>
            </head>
            <body><main>
              <div class="brand">AIO <span>CONFIGURATION</span></div>
              <h1>Configure AIO</h1>
              <p class="muted">This server is running locally on your phone.</p>
              <section>
                <h2>Stremio Addons</h2>
                <p class="muted">Add compatible addon manifest URLs, one per line.</p>
                <form method="post" action="/save">
                  <textarea name="addons" placeholder="https://.../manifest.json">$saved</textarea>
                  <button type="submit">Save Configuration</button>
                </form>
              </section>
              <section>
                <h2>Your AIO Manifest</h2>
                <code>http://127.0.0.1:3001/manifest.json</code>
              </section>
            </main></body></html>
        """.trimIndent()
    }

    private fun parseForm(value: String): Map<String, String> = value.split('&').mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx < 0) null
        else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
    }.toMap()

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun html(value: String) = HttpResponse.text(200, "text/html; charset=utf-8", value)
    private fun json(value: String) = HttpResponse.text(200, "application/json; charset=utf-8", value)
}
