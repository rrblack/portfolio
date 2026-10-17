package dev.kyleporter.harnessremote

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.kyleporter.harnessremote.net.BridgeClient
import dev.kyleporter.harnessremote.net.Server
import dev.kyleporter.harnessremote.service.ConnectionService
import dev.kyleporter.harnessremote.service.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
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
            override fun onActivityStarted(activity: Activity) { started++; hub.foreground = true; hub.client.value?.reconnectNow() }
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

    fun connect(server: Server) {
        client.value?.close()
        val c = BridgeClient(server, http)
        c.onSessions = { old, new -> notifier.onSessions(server, old, new, foreground) }
        client.value = c
        c.connect()
        prefs.edit().putString("last", server.id).apply()
        ConnectionService.start(app)
        route.value = Route.Sessions
    }

    fun disconnect() {
        client.value?.close()
        client.value = null
        ConnectionService.stop(app)
        route.value = Route.Servers
    }

    fun lastServer(): Server? = prefs.getString("last", null)?.let { id -> servers.value.find { it.id == id } }
}
