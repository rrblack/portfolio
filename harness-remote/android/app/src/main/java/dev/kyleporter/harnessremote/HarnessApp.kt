package dev.kyleporter.harnessremote

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.kyleporter.harnessremote.net.BridgeClient
import dev.kyleporter.harnessremote.net.Server
import dev.kyleporter.harnessremote.service.ConnectionService
import dev.kyleporter.harnessremote.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.util.concurrent.TimeUnit

sealed interface Route {
    data object Servers : Route
    data object Sessions : Route
    data object NewSession : Route
    data class Session(val id: String) : Route
}

class HarnessApp : Application() {
    lateinit var hub: Hub
        private set

    override fun onCreate() {
        super.onCreate()
        hub = Hub(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { started++; hub.foreground = true; hub.client.value?.wake() }
            override fun onActivityStopped(activity: Activity) { started--; hub.foreground = started > 0 }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

val Context.hub: Hub get() = (applicationContext as HarnessApp).hub

class Hub(private val app: Application) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val prefs = app.getSharedPreferences("harness_remote", Context.MODE_PRIVATE)
    private val notifier = Notifier(app)

    val servers = MutableStateFlow(loadServers())
    val client = MutableStateFlow<BridgeClient?>(null)
    val route = MutableStateFlow<Route>(Route.Servers)

    @Volatile var foreground = false

    init {
        // Reopen the chat that was on screen if Android closes the app in the background.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            route.drop(1).collect { r ->
                when (r) {
                    is Route.Session -> prefs.edit().putString("lastSession", r.id).apply()
                    Route.Sessions, Route.Servers -> prefs.edit().remove("lastSession").apply()
                    Route.NewSession -> {}
                }
            }
        }
        // Wi-Fi, mobile data or Tailscale coming back: reconnect now instead of waiting out the retry timer.
        val main = Handler(Looper.getMainLooper())
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.postDelayed({ client.value?.wake() }, 500) }
            })
        }
    }

    private fun loadServers(): List<Server> = runCatching {
        val a = JSONArray(prefs.getString("servers", "[]"))
        (0 until a.length()).map { Server.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun persist(list: List<Server>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs.edit().putString("servers", a.toString()).apply()
        servers.value = list
    }

    fun saveServer(server: Server) {
        persist(servers.value.filterNot { it.id == server.id } + server)
    }

    fun removeServer(server: Server) {
        if (client.value?.server?.id == server.id) disconnect()
        persist(servers.value.filterNot { it.id == server.id })
    }

    fun connect(server: Server, navigate: Boolean = true) {
        client.value?.close()
        val c = BridgeClient(server, http)
        c.onSessions = { old, new -> notifier.onSessions(server, old, new, foreground) }
        client.value = c
        c.connect()
        prefs.edit().putString("last", server.id).apply()
        ConnectionService.start(app)
        if (navigate) route.value = Route.Sessions
    }

    /** After Android closed the app: connect to the last computer and reopen the chat that was open. */
    fun resume() {
        val session = prefs.getString("lastSession", null)
        if (client.value == null) connect(lastServer() ?: return)
        route.value = if (session != null) Route.Session(session) else Route.Sessions
    }

    fun draft(sessionId: String): String = prefs.getString("draft:${client.value?.server?.id}:$sessionId", "").orEmpty()

    fun saveDraft(sessionId: String, text: String) {
        val key = "draft:${client.value?.server?.id}:$sessionId"
        prefs.edit().apply { if (text.isBlank()) remove(key) else putString(key, text) }.apply()
    }

    fun disconnect() {
        client.value?.close()
        client.value = null
        ConnectionService.stop(app)
        route.value = Route.Servers
    }

    fun recentFolders(serverId: String): List<String> = runCatching {
        val a = JSONArray(prefs.getString("recent:$serverId", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun rememberFolder(serverId: String, path: String) {
        val list = (listOf(path) + recentFolders(serverId).filterNot { it == path }).take(6)
        prefs.edit().putString("recent:$serverId", JSONArray(list).toString()).apply()
    }

    fun lastServer(): Server? = prefs.getString("last", null)?.let { id -> servers.value.find { it.id == id } }
}
