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

    private val servers = mutableListOf<MiniHttpServer>()
    private lateinit var core: AioCore

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1001, notification("Starting local AIO…"))
        try {
            core = AioCore(this)
            servers += MiniHttpServer(3001) { req -> core.handleAio(req) }
            servers += MiniHttpServer(8080) { req -> core.handleBridge(req) }
            servers += MiniHttpServer(8091) { req -> core.handleManager(req) }
            servers.forEach { it.start() }
            running = true
            lastError = null
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(1001, notification("AIO local stack running · 3001 / 8080 / 8091"))
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            stopSelf()
        }
    }

    override fun onDestroy() {
        servers.forEach { runCatching { it.stop() } }
        servers.clear()
        running = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("aio_local", "AIO Local Server", NotificationManager.IMPORTANCE_LOW)
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
    val rawTarget: String,
    val path: String,
    val query: String,
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
            val query = target.substringAfter('?', "")
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
            val req = HttpRequest(method, target, path, query, headers, if (off == len) body else body.copyOf(off))
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
            200 -> "OK"; 204 -> "No Content"; 302 -> "Found"; 400 -> "Bad Request"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 500 -> "Internal Server Error"
            502 -> "Bad Gateway"; else -> "OK"
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

    private val phisherRepo = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
    private val cncRepo = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"
    private val phisherPlugins = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/plugins.json"
    private val cncPlugins = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/plugins.json"

    fun handleAio(req: HttpRequest): HttpResponse = when {
        req.method == "GET" && (req.path == "/" || req.path == "/stremio/configure") -> html(configPage())
        req.method == "GET" && req.path == "/health" -> json(JSONObject()
            .put("status", "ready").put("name", "AIO Local").put("port", 3001)
            .put("addons", addonUrls().size).toString())
        req.method == "GET" && req.path == "/manifest.json" -> json(manifestJson("com.codex.aio.local", "AIO Local"))
        req.method == "POST" && req.path == "/save" -> saveConfig(req)
        req.method == "GET" && req.path.matches(Regex("^/stream/(movie|series)/.+\\.json$")) -> streamResponse(req.path)
        else -> notFound()
    }

    fun handleBridge(req: HttpRequest): HttpResponse = when {
        req.method == "GET" && req.path == "/" -> html(bridgePage())
        req.method == "GET" && req.path == "/health" -> json(JSONObject()
            .put("status", "ready").put("version", "android-local-1").put("port", 8080)
            .put("configuredAddons", addonUrls().size).toString())
        req.method == "GET" && req.path == "/manifest.json" -> json(manifestJson("com.codex.aio.bridge", "AIO Bridge"))
        req.method == "GET" && req.path == "/providers.json" -> json(JSONObject()
            .put("status", "ready")
            .put("loaded", JSONArray(addonUrls()))
            .put("failed", JSONObject())
            .put("repository", "http://127.0.0.1:8091/repo.json").toString())
        req.method == "GET" && req.path.matches(Regex("^/stream/(movie|series)/.+\\.json$")) -> streamResponse(req.path)
        else -> notFound()
    }

    fun handleManager(req: HttpRequest): HttpResponse = when {
        req.method == "GET" && req.path == "/" -> html(managerPage())
        req.method == "GET" && req.path == "/repo.json" -> json(JSONObject()
            .put("name", "AIO Multi Repository")
            .put("description", "Phisher + CNCVerse repositories from the working ExtremeOS setup")
            .put("manifestVersion", 1)
            .put("pluginLists", JSONArray().put(phisherPlugins).put(cncPlugins)).toString())
        req.method == "GET" && req.path == "/repos.json" -> json(JSONArray()
            .put(JSONObject().put("name", "Phisher").put("url", phisherRepo))
            .put(JSONObject().put("name", "CNCVerse").put("url", cncRepo)).toString())
        else -> notFound()
    }

    private fun saveConfig(req: HttpRequest): HttpResponse {
        val form = parseForm(req.body.toString(StandardCharsets.UTF_8))
        val raw = form["addons"].orEmpty()
        val cleaned = raw.lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct().joinToString("\n")
        prefs.edit().putString("addons", cleaned).apply()
        return HttpResponse(302, "text/plain", ByteArray(0), mapOf("Location" to "/stremio/configure"))
    }

    private fun addonUrls(): List<String> = prefs.getString("addons", "").orEmpty().lines()
        .map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()

    private fun streamResponse(localPath: String): HttpResponse {
        val urls = addonUrls()
        if (urls.isEmpty()) return json(JSONObject().put("streams", JSONArray()).toString())
        val remotePaths = urls.mapNotNull { manifest ->
            val base = when {
                manifest.endsWith("/manifest.json") -> manifest.removeSuffix("manifest.json")
                manifest.endsWith("manifest.json") -> manifest.removeSuffix("manifest.json")
                else -> manifest.trimEnd('/') + "/"
            }
            base + localPath.trimStart('/')
        }
        val futures = remotePaths.map { u -> workers.submit<JSONArray?> { fetchStreams(u) } }
        val merged = JSONArray()
        futures.forEach { f ->
            runCatching { f.get(18, TimeUnit.SECONDS) }.getOrNull()?.let { arr ->
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
            val code = conn.responseCode
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text).optJSONArray("streams")
        } finally {
            conn.disconnect()
        }
    }

    private fun manifestJson(id: String, name: String): String = JSONObject()
        .put("id", id)
        .put("version", "1.0.0")
        .put("name", name)
        .put("description", "Local Android AIO stream aggregator")
        .put("resources", JSONArray().put("stream"))
        .put("types", JSONArray().put("movie").put("series"))
        .put("catalogs", JSONArray())
        .toString()

    private fun configPage(): String {
        val saved = escapeHtml(prefs.getString("addons", "").orEmpty())
        return """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>AIO Local</title><style>${css()}</style></head><body><main>
            <div class="brand">AIO <span>LOCAL</span></div>
            <h1>Local AIO is running</h1>
            <p class="muted">Phone ke andar server chal raha hai. Oracle VPS required nahi hai.</p>
            <section><h2>Stremio Addons</h2>
            <p class="muted">Torrentio, Comet ya kisi compatible Stremio addon ka <b>manifest.json URL</b> ek line mein ek paste karo. Streams locally combine honge.</p>
            <form method="post" action="/save"><textarea name="addons" placeholder="https://.../manifest.json">$saved</textarea>
            <button type="submit">Save configuration</button></form></section>
            <section><h2>Local endpoints</h2>
            <a href="/manifest.json">AIO manifest</a><a href="/health">Health</a>
            <a href="http://127.0.0.1:8080/">Bridge · 8080</a><a href="http://127.0.0.1:8091/">Plugin Manager · 8091</a>
            </section><section><h2>Install</h2><code>http://127.0.0.1:3001/manifest.json</code></section>
            </main></body></html>
        """.trimIndent()
    }

    private fun bridgePage(): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>AIO Bridge</title><style>${css()}</style></head>
        <body><main><div class="brand">AIO <span>BRIDGE</span></div><h1>Bridge is running</h1>
        <section><a href="/manifest.json">manifest.json</a><a href="/health">health</a><a href="/providers.json">providers.json</a></section>
        <section><p class="muted">Repository source:</p><code>http://127.0.0.1:8091/repo.json</code></section>
        <a href="http://127.0.0.1:3001/stremio/configure">← AIO configuration</a></main></body></html>
    """.trimIndent()

    private fun managerPage(): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>AIO Plugin Manager</title><style>${css()}</style></head>
        <body><main><div class="brand">AIO <span>PLUGINS</span></div><h1>Plugin Manager</h1>
        <section><h2>Repositories</h2><div class="card"><b>Phisher</b><small>$phisherRepo</small></div>
        <div class="card"><b>CNCVerse</b><small>$cncRepo</small></div></section>
        <section><a href="/repo.json">Combined repo.json</a><a href="/repos.json">Repository list</a></section>
        <p class="warn">Repository metadata is active. CloudStream JAR execution is the next native bridge layer being ported from Stream X.</p>
        <a href="http://127.0.0.1:3001/stremio/configure">← AIO configuration</a></main></body></html>
    """.trimIndent()

    private fun css(): String = """
        :root{color-scheme:dark;--bg:#090d16;--panel:#121a29;--text:#eef4ff;--muted:#91a0b8;--accent:#8b5cf6}
        *{box-sizing:border-box}body{margin:0;background:linear-gradient(180deg,#090d16,#0d1320);color:var(--text);font:15px system-ui,sans-serif}
        main{max-width:760px;margin:auto;padding:28px 18px}.brand{font-weight:900;font-size:28px;letter-spacing:.5px}.brand span{color:var(--accent)}
        h1{margin:8px 0 4px}h2{font-size:17px}section{background:var(--panel);padding:16px;border-radius:16px;margin:14px 0;border:1px solid #202c42}
        textarea{width:100%;min-height:150px;background:#090e18;color:white;border:1px solid #33425e;border-radius:12px;padding:12px;font:13px monospace}
        button{margin-top:10px;border:0;border-radius:11px;padding:12px 16px;background:var(--accent);color:white;font-weight:800}
        a{display:block;color:#bca7ff;text-decoration:none;padding:7px 0}.muted,small{color:var(--muted)}code{display:block;overflow:auto;padding:10px;background:#080c14;border-radius:10px;color:#b7f7cf}
        .card{padding:12px;background:#0b111d;border-radius:12px;margin:8px 0}.card small{display:block;word-break:break-all;margin-top:5px}.warn{color:#ffd58a}
    """.trimIndent()

    private fun parseForm(value: String): Map<String, String> = value.split('&').mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx < 0) null else URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
    }.toMap()

    private fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun html(value: String) = HttpResponse.text(200, "text/html; charset=utf-8", value)
    private fun json(value: String) = HttpResponse.text(200, "application/json; charset=utf-8", value)
    private fun notFound() = HttpResponse.text(404, "application/json; charset=utf-8", "{\"error\":\"not_found\"}")
}
