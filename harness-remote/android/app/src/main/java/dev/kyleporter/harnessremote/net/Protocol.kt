package dev.kyleporter.harnessremote.net

import org.json.JSONArray
import org.json.JSONObject

class BridgeException(message: String) : Exception(message)

data class Server(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String,
) {
    fun wsUrl(): String = baseUrl.replaceFirst(Regex("^http"), "ws").trimEnd('/') + "/ws"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("baseUrl", baseUrl).put("token", token)

    companion object {
        fun fromJson(o: JSONObject) = Server(o.str("id"), o.str("name"), o.str("baseUrl"), o.str("token"))

        fun normalizeAddress(input: String): String {
            var s = input.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            val hostPart = s.substringAfter("://")
            val hasPort = if (hostPart.startsWith("[")) hostPart.contains("]:") else hostPart.contains(":")
            if (!hasPort) s = "$s:8787"
            return s
        }
    }
}

data class HarnessInfo(
    val id: String,
    val name: String,
    val description: String,
    val mode: String,
    val models: String?,
    val requiresModel: Boolean,
    val available: Boolean,
    val reason: String?,
    val command: String,
)

data class Hello(
    val host: String,
    val version: String,
    val platform: String,
    val roots: List<String>,
    val harnesses: List<HarnessInfo>,
)

data class SessionSummary(
    val id: String,
    val title: String,
    val harness: String,
    val cwd: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    val turns: Int,
    val state: String,
    val seq: Long,
    val preview: String,
) {
    val busy get() = state == "running" || state == "waiting"
}

data class BridgeEvent(
    val seq: Long,
    val ts: Long,
    val kind: String,
    val text: String,
    val code: Int?,
)

data class ModelInfo(val provider: String, val name: String, val params: String)

data class DirListing(val path: String, val parent: String?, val dirs: List<String>, val git: Boolean)

fun baseName(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

fun childPath(dir: String, name: String): String {
    val sep = if ('\\' in dir && '/' !in dir) "\\" else "/"
    return dir.trimEnd('/', '\\') + sep + name
}

fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else optString(key)

private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).map { f(getJSONObject(it)) }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { getString(it) }
}

fun parseHarness(o: JSONObject) = HarnessInfo(
    id = o.str("id"),
    name = o.str("name"),
    description = o.str("description"),
    mode = o.str("mode"),
    models = o.strOrNull("models"),
    requiresModel = o.optBoolean("requiresModel"),
    available = o.optBoolean("available"),
    reason = o.strOrNull("reason"),
    command = o.str("command"),
)

fun parseHello(o: JSONObject) = Hello(
    host = o.str("host"),
    version = o.str("version"),
    platform = o.str("platform"),
    roots = o.optJSONArray("roots").strings(),
    harnesses = o.optJSONArray("harnesses").mapObjects(::parseHarness),
)

fun parseSession(o: JSONObject) = SessionSummary(
    id = o.str("id"),
    title = o.str("title"),
    harness = o.str("harness"),
    cwd = o.str("cwd"),
    model = o.str("model"),
    createdAt = o.optLong("createdAt"),
    updatedAt = o.optLong("updatedAt"),
    turns = o.optInt("turns"),
    state = o.str("state"),
    seq = o.optLong("seq"),
    preview = o.str("preview"),
)

fun parseSessions(a: JSONArray?) = a.mapObjects(::parseSession)

fun parseEvent(o: JSONObject) = BridgeEvent(
    seq = o.optLong("seq"),
    ts = o.optLong("ts"),
    kind = o.str("kind"),
    text = o.str("text"),
    code = if (o.has("code") && !o.isNull("code")) o.optInt("code") else null,
)

fun parseEvents(a: JSONArray?) = a.mapObjects(::parseEvent)

fun parseModels(o: JSONObject): List<ModelInfo> =
    o.optJSONArray("ollama").mapObjects { ModelInfo("ollama", it.str("name"), it.str("params")) } +
        o.optJSONArray("lmstudio").mapObjects { ModelInfo("lmstudio", it.str("name"), it.str("params")) }

fun parseListing(o: JSONObject) = DirListing(
    path = o.str("path"),
    parent = o.strOrNull("parent"),
    dirs = o.optJSONArray("dirs").strings(),
    git = o.optBoolean("git"),
)
