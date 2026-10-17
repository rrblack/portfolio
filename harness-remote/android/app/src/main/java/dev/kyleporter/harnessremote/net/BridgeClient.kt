package dev.kyleporter.harnessremote.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface ConnState {
    data object Connecting : ConnState
    data object Connected : ConnState
    data class Retrying(val seconds: Int, val reason: String) : ConnState
    data class Failed(val reason: String) : ConnState
    data object Closed : ConnState
}

class BridgeClient(val server: Server, private val http: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val rid = AtomicInteger()
    private val eventFlows = HashMap<String, MutableStateFlow<List<BridgeEvent>>>()
    private val subscribed = LinkedHashSet<String>()
    private val synced = HashSet<String>()
    private var socket: WebSocket? = null
    private var wanted = false
    private var attempt = 0
    private var retryJob: Job? = null

    val conn = MutableStateFlow<ConnState>(ConnState.Closed)
    val hello = MutableStateFlow<Hello?>(null)
    val sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    var onSessions: ((old: List<SessionSummary>, new: List<SessionSummary>) -> Unit)? = null

    fun connect() {
        wanted = true
        retryJob?.cancel()
        open()
    }

    fun close() {
        wanted = false
        retryJob?.cancel()
        socket?.close(1000, null)
        socket = null
        conn.value = ConnState.Closed
        failPending("Disconnected")
        scope.cancel()
    }

    fun reconnectNow() {
        if (!wanted || conn.value == ConnState.Connected || conn.value == ConnState.Connecting) return
        retryJob?.cancel()
        open()
    }

    private fun open() {
        conn.value = ConnState.Connecting
        val req = Request.Builder()
            .url(server.wsUrl())
            .header("Authorization", "Bearer ${server.token}")
            .build()
        socket = http.newWebSocket(req, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch {
                if (webSocket !== socket) return@launch
                attempt = 0
                conn.value = ConnState.Connected
                for (id in subscribed.toList()) launch { runCatching { subscribe(id) } }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                if (webSocket !== socket) return@launch
                runCatching { handle(JSONObject(text)) }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { if (webSocket === socket) dropped("Connection closed") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch {
                if (webSocket !== socket) return@launch
                if (response?.code == 401) {
                    wanted = false
                    socket = null
                    failPending("Unauthorized")
                    conn.value = ConnState.Failed("The bridge rejected this phone's token. Pair again.")
                } else {
                    dropped(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun dropped(reason: String) {
        socket = null
        failPending(reason)
        if (!wanted) {
            conn.value = ConnState.Closed
            return
        }
        val wait = minOf(30, 1 shl minOf(attempt, 5))
        attempt++
        conn.value = ConnState.Retrying(wait, reason)
        retryJob = scope.launch {
            delay(wait * 1000L)
            if (wanted) open()
        }
    }

    private fun failPending(reason: String) {
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.completeExceptionally(BridgeException(reason)) }
    }

    private fun handle(o: JSONObject) {
        when (o.str("t")) {
            "hello" -> {
                hello.value = parseHello(o)
                setSessions(parseSessions(o.optJSONArray("sessions")))
            }
            "sessions" -> setSessions(parseSessions(o.optJSONArray("sessions")))
            "event" -> {
                val id = o.str("id")
                val ev = parseEvent(o.getJSONObject("ev"))
                val flow = eventFlows[id] ?: return
                val cur = flow.value
                if (cur.isEmpty() || ev.seq > cur.last().seq) flow.value = cur + ev
            }
        }
        if (o.has("rid") && !o.isNull("rid")) pending.remove(o.optInt("rid"))?.complete(o)
    }

    private fun setSessions(list: List<SessionSummary>) {
        val old = sessions.value
        sessions.value = list
        onSessions?.invoke(old, list)
    }

    suspend fun request(type: String, build: JSONObject.() -> Unit = {}): JSONObject {
        val id = rid.incrementAndGet()
        val msg = JSONObject().put("t", type).put("rid", id).apply(build)
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        val ws = socket
        if (ws == null || conn.value != ConnState.Connected || !ws.send(msg.toString())) {
            pending.remove(id)
            throw BridgeException("Not connected to the bridge")
        }
        val r = try {
            withTimeout(20_000) { d.await() }
        } finally {
            pending.remove(id)
        }
        if (r.str("t") == "error") throw BridgeException(r.str("error"))
        return r
    }

    fun events(id: String): StateFlow<List<BridgeEvent>> = eventFlows.getOrPut(id) { MutableStateFlow(emptyList()) }

    suspend fun subscribe(id: String) {
        subscribed.add(id)
        val flow = eventFlows.getOrPut(id) { MutableStateFlow(emptyList()) }
        val after = if (id in synced) flow.value.lastOrNull()?.seq ?: 0L else 0L
        val r = request("subscribe") { put("id", id); put("after", after) }
        val incoming = parseEvents(r.optJSONArray("events"))
        flow.value = (flow.value + incoming).distinctBy { it.seq }.sortedBy { it.seq }
        synced.add(id)
    }

    suspend fun models(): List<ModelInfo> = parseModels(request("models"))

    suspend fun listDir(path: String?): DirListing = parseListing(request("ls") { if (path != null) put("path", path) })

    suspend fun create(harness: String, cwd: String, model: String, title: String): SessionSummary {
        val r = request("create") {
            put("harness", harness); put("cwd", cwd); put("model", model); put("title", title)
        }
        val s = parseSession(r.getJSONObject("session"))
        subscribed.add(s.id)
        eventFlows.getOrPut(s.id) { MutableStateFlow(emptyList()) }
        return s
    }

    suspend fun send(id: String, text: String) { request("send") { put("id", id); put("text", text) } }

    suspend fun stdin(id: String, data: String) { request("stdin") { put("id", id); put("data", data) } }

    suspend fun stop(id: String) { request("stop") { put("id", id) } }

    suspend fun restart(id: String) { request("restart") { put("id", id) } }

    suspend fun rename(id: String, title: String) { request("rename") { put("id", id); put("title", title) } }

    suspend fun delete(id: String) {
        request("delete") { put("id", id) }
        subscribed.remove(id)
        eventFlows.remove(id)
        synced.remove(id)
    }

    companion object {
        suspend fun pair(http: OkHttpClient, baseUrl: String, code: String, device: String): Pair<String, String> =
            withContext(Dispatchers.IO) {
                val body = JSONObject().put("code", code).put("device", device).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(baseUrl.trimEnd('/') + "/pair").post(body).build()
                http.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    val o = runCatching { JSONObject(text) }.getOrNull()
                    if (!res.isSuccessful || o == null) {
                        throw BridgeException(o?.str("error")?.ifBlank { null } ?: "Pairing failed (HTTP ${res.code})")
                    }
                    o.str("token") to o.str("host")
                }
            }

        suspend fun health(http: OkHttpClient, baseUrl: String): String = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(baseUrl.trimEnd('/') + "/health").build()
            http.newCall(req).execute().use { res ->
                val o = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull()
                if (!res.isSuccessful || o?.str("name") != "harness-bridge") {
                    throw BridgeException("No Harness Bridge answered at $baseUrl")
                }
                o.str("host")
            }
        }
    }
}
