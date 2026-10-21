package com.example.offlinekaraoke

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

internal data class RemoteCatalogSong(
    val number: String,
    val title: String,
    val artist: String
) {
    val searchKey: String = "$number $title $artist".lowercase(Locale.ROOT)
}

internal data class RemoteQueueStatus(val queueSize: Int, val queueLimit: Int)

internal data class RemoteReservationResult(val accepted: Boolean, val message: String)

internal data class RemoteControlResult(val accepted: Boolean, val message: String)

/** A dependency-free HTTP server used only on the device's local network. */
internal class BrowserRemoteServer(
    private val catalog: List<RemoteCatalogSong>,
    private val port: Int,
    private val statusProvider: () -> RemoteQueueStatus,
    private val reservationHandler: (String) -> RemoteReservationResult,
    private val controlHandler: (String) -> RemoteControlResult
) {
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var running = false
    @Volatile private var stopRequested = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        check(!stopRequested) { "This remote server has already been stopped." }
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        if (stopRequested) {
            socket.close()
            return
        }
        serverSocket = socket
        running = true
        acceptThread = Thread {
            while (running) {
                try {
                    val client = socket.accept()
                    workers.execute { handleClient(client) }
                } catch (_: SocketException) {
                    // Closing the socket is the normal way to stop the accept loop.
                } catch (_: Exception) {
                    // A malformed or abandoned connection must not stop the remote.
                }
            }
        }.apply {
            name = "KaraokeBrowserRemote"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopRequested = true
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        workers.shutdownNow()
    }

    fun browserAddresses(): List<String> = localIpv4Addresses().map { "http://$it:$port" }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val request = runCatching { readRequest(BufferedInputStream(socket.getInputStream())) }.getOrNull()
            if (request == null) {
                writeResponse(socket, 400, "application/json; charset=utf-8", jsonMessage(false, "Invalid request."))
                return
            }
            val targetParts = request.target.split('?', limit = 2)
            val path = targetParts[0]
            val query = if (targetParts.size > 1) parseForm(targetParts[1]) else emptyMap()
            when {
                request.method == "GET" && path == "/" -> {
                    writeResponse(socket, 200, "text/html; charset=utf-8", REMOTE_PAGE)
                }
                request.method == "GET" && path == "/api/status" -> {
                    writeResponse(socket, 200, "application/json; charset=utf-8", statusJson())
                }
                request.method == "GET" && path == "/api/search" -> {
                    val term = query["q"].orEmpty().trim().lowercase(Locale.ROOT).take(MAX_QUERY_LENGTH)
                    val matches = if (term.isBlank()) {
                        emptyList()
                    } else {
                        catalog.asSequence()
                            .filter { it.searchKey.contains(term) }
                            .take(MAX_SEARCH_RESULTS)
                            .toList()
                    }
                    val result = JSONObject()
                    result.put("songs", JSONArray().apply {
                        matches.forEach { song ->
                            put(JSONObject().apply {
                                put("number", song.number)
                                put("title", song.title)
                                put("artist", song.artist)
                            })
                        }
                    })
                    val status = statusProvider()
                    result.put("queueSize", status.queueSize)
                    result.put("queueLimit", status.queueLimit)
                    writeResponse(socket, 200, "application/json; charset=utf-8", result.toString())
                }
                request.method == "POST" && path == "/api/reserve" -> {
                    val number = parseForm(request.body)["number"].orEmpty().trim()
                    val result = reservationHandler(number)
                    val status = statusProvider()
                    val json = JSONObject()
                        .put("ok", result.accepted)
                        .put("message", result.message)
                        .put("queueSize", status.queueSize)
                        .put("queueLimit", status.queueLimit)
                        .toString()
                    writeResponse(socket, if (result.accepted) 200 else 409, "application/json; charset=utf-8", json)
                }
                request.method == "POST" && path == "/api/control" -> {
                    val action = parseForm(request.body)["action"].orEmpty().trim().lowercase(Locale.ROOT)
                    val result = controlHandler(action)
                    val status = statusProvider()
                    val json = JSONObject()
                        .put("ok", result.accepted)
                        .put("message", result.message)
                        .put("queueSize", status.queueSize)
                        .put("queueLimit", status.queueLimit)
                        .toString()
                    writeResponse(socket, if (result.accepted) 200 else 409, "application/json; charset=utf-8", json)
                }
                else -> writeResponse(socket, 404, "application/json; charset=utf-8", jsonMessage(false, "Not found."))
            }
        }
    }

    private fun statusJson(): String {
        val status = statusProvider()
        return JSONObject()
            .put("enabled", running)
            .put("queueSize", status.queueSize)
            .put("queueLimit", status.queueLimit)
            .toString()
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            headerBytes.write(value)
            matched = when {
                (matched == 0 || matched == 2) && value == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && value == '\n'.code -> matched + 1
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        if (matched != 4) return null
        val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
        val headers = lines.drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).trim().lowercase(Locale.ROOT) to
                    line.substring(separator + 1).trim()
            }
            .toMap()
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = input.read(bodyBytes, offset, contentLength - offset)
            if (count <= 0) break
            offset += count
        }
        if (offset != contentLength) return null
        return HttpRequest(
            method = requestLine[0].uppercase(Locale.ROOT),
            target = requestLine[1],
            body = String(bodyBytes, StandardCharsets.UTF_8)
        )
    }

    private fun writeResponse(socket: Socket, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        runCatching {
            socket.getOutputStream().apply {
                write(headers)
                write(bytes)
                flush()
            }
        }
    }

    private fun parseForm(value: String): Map<String, String> = value
        .split('&')
        .mapNotNull { item ->
            if (item.isBlank()) return@mapNotNull null
            val parts = item.split('=', limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }
        .toMap()

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun jsonMessage(ok: Boolean, message: String): String = JSONObject()
        .put("ok", ok)
        .put("message", message)
        .toString()

    private data class HttpRequest(val method: String, val target: String, val body: String)

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024
        private const val MAX_QUERY_LENGTH = 80
        private const val MAX_SEARCH_RESULTS = 30

        private fun localIpv4Addresses(): List<String> = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { network -> runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) }
                .flatMap { network -> Collections.list(network.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filter { address -> !address.isLoopbackAddress && address.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
                .sortedWith(compareBy<String> { address ->
                    when {
                        address.startsWith("192.168.") -> 0
                        address.startsWith("10.") -> 1
                        address.startsWith("172.") -> 2
                        else -> 3
                    }
                }.thenBy { it })
        }.getOrDefault(emptyList())

        private val REMOTE_PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <title>RC Karaoke Remote</title>
              <style>
                :root{color-scheme:dark;--cyan:#32d9ff;--blue:#278bff;--violet:#973cff;--pink:#ff3fa4;--panel:#111b35;--muted:#9aa8c6}
                *{box-sizing:border-box}body{margin:0;min-height:100vh;font-family:system-ui,-apple-system,sans-serif;color:#fff;background:radial-gradient(circle at 10% 0,#17367a 0,transparent 34%),radial-gradient(circle at 100% 15%,#521873 0,transparent 30%),#070d1e}
                main{max-width:760px;margin:auto;padding:22px 16px 48px}.brand{font-size:12px;letter-spacing:.22em;color:var(--cyan);font-weight:800}.hero{font-size:30px;line-height:1.05;margin:8px 0 6px}.sub{color:var(--muted);margin:0 0 18px}
                .status{display:flex;justify-content:space-between;gap:10px;align-items:center;background:linear-gradient(135deg,#122343,#17172f);border:1px solid #29436c;border-radius:16px;padding:12px 14px;margin-bottom:14px;box-shadow:0 14px 35px #0006}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#43f0a6;box-shadow:0 0 12px #43f0a6;margin-right:7px}.count{color:var(--cyan);font-weight:800}
                .search{width:100%;height:54px;border-radius:16px;border:1px solid #36507c;background:#101a31;color:#fff;padding:0 17px;font-size:17px;outline:none;box-shadow:inset 0 0 0 1px #0004}.search:focus{border-color:var(--cyan);box-shadow:0 0 0 3px #32d9ff22}.hint{color:var(--muted);font-size:13px;margin:10px 3px 14px}
                .songs{display:grid;gap:10px}.song{display:grid;grid-template-columns:auto 1fr auto;gap:12px;align-items:center;padding:13px;background:linear-gradient(120deg,#111c35,#15172e);border:1px solid #293d62;border-radius:16px}.num{font-size:12px;color:var(--cyan);font-weight:900;background:#0a2940;padding:7px;border-radius:9px}.title{font-weight:800;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.artist{font-size:13px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:3px}
                button{border:0;border-radius:12px;color:#fff;font-weight:900;font-size:20px;width:46px;height:46px;background:linear-gradient(135deg,var(--violet),var(--blue));box-shadow:0 8px 20px #3d47e955}button:disabled{opacity:.4}.controls{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:0 0 14px}.control{width:100%;height:46px;padding:0 8px;font-size:13px;white-space:nowrap}.control.finish{background:linear-gradient(135deg,#e03068,#ff4b86)}.message{text-align:center;color:var(--muted);padding:28px 8px}.toast{position:fixed;left:50%;bottom:25px;transform:translateX(-50%) translateY(90px);opacity:0;transition:.25s;background:#14213d;border:1px solid #3c5c8e;border-radius:14px;padding:12px 18px;max-width:90%;text-align:center;box-shadow:0 12px 30px #0009}.toast.show{transform:translateX(-50%) translateY(0);opacity:1}.toast.good{border-color:#38dc9c}.toast.bad{border-color:#ff4b86}
                @media(max-width:440px){.hero{font-size:26px}.controls{grid-template-columns:repeat(2,minmax(0,1fr))}.song{grid-template-columns:auto minmax(0,1fr) auto;padding:11px}.num{font-size:11px;padding:6px 5px}}
              </style>
            </head>
            <body><main>
              <div class="brand">RC KARAOKE</div><h1 class="hero">Reserve your next song</h1><p class="sub">Search the offline songbook without interrupting the singer.</p>
              <div class="status"><span><i class="dot"></i>Remote connected</span><span class="count" id="count">Queue -- / --</span></div>
              <div class="controls" aria-label="Playback controls">
                <button class="control" data-action="restart">&#8634; Restart</button>
                <button class="control" data-action="pause">&#9208; Pause</button>
                <button class="control finish" data-action="finish">&#9632; Finish</button>
                <button class="control" data-action="next">Next &#9654;</button>
              </div>
              <input class="search" id="search" autocomplete="off" placeholder="Song number, title, or artist">
              <div class="hint">Type to search, then tap + to reserve.</div><section class="songs" id="songs"><div class="message">Start typing to find a song.</div></section>
            </main><div class="toast" id="toast"></div>
            <script>
              const search=document.getElementById('search'),songs=document.getElementById('songs'),count=document.getElementById('count'),toast=document.getElementById('toast');let timer;
              function status(d){count.textContent='Queue '+d.queueSize+' / '+d.queueLimit}
              function say(message,good){toast.textContent=message;toast.className='toast show '+(good?'good':'bad');clearTimeout(toast.t);toast.t=setTimeout(()=>toast.className='toast',2800)}
              function empty(message){songs.replaceChildren();const e=document.createElement('div');e.className='message';e.textContent=message;songs.append(e)}
              async function control(action,button){button.disabled=true;try{const response=await fetch('/api/control',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'action='+encodeURIComponent(action)});const data=await response.json();status(data);say(data.message,data.ok)}catch(e){say('The karaoke remote is unavailable.',false)}finally{button.disabled=false}}
              function row(song){const card=document.createElement('article');card.className='song';const number=document.createElement('span');number.className='num';number.textContent=song.number;const info=document.createElement('div');const title=document.createElement('div');title.className='title';title.textContent=song.title;const artist=document.createElement('div');artist.className='artist';artist.textContent=song.artist;info.append(title,artist);const add=document.createElement('button');add.textContent='+';add.setAttribute('aria-label','Reserve '+song.title);add.onclick=async()=>{add.disabled=true;try{const response=await fetch('/api/reserve',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'number='+encodeURIComponent(song.number)});const data=await response.json();status(data);say(data.message,data.ok)}catch(e){say('The karaoke remote is unavailable.',false)}finally{add.disabled=false}};card.append(number,info,add);return card}
              async function find(){const q=search.value.trim();if(!q){empty('Start typing to find a song.');return}try{const response=await fetch('/api/search?q='+encodeURIComponent(q));const data=await response.json();status(data);songs.replaceChildren();if(!data.songs.length){empty('No matching songs found.');return}data.songs.forEach(song=>songs.append(row(song)))}catch(e){empty('Connection lost. Check the main karaoke device.')}}
              document.querySelectorAll('.control').forEach(button=>button.addEventListener('click',()=>control(button.dataset.action,button)));search.addEventListener('input',()=>{clearTimeout(timer);timer=setTimeout(find,220)});fetch('/api/status').then(r=>r.json()).then(status).catch(()=>say('Cannot reach the karaoke device.',false));
            </script></body></html>
        """.trimIndent()
    }
}
