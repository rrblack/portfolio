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
    val models: List<String>?,
    val requiresModel: Boolean,
    val effort: Boolean,
    val images: String,
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
    val now: Long,
)

data class SessionSummary(
    val id: String,
    val title: String,
    val harness: String,
    val cwd: String,
    val model: String,
    val provider: String,
    val thinking: String,
    val permissions: String,
    val createdAt: Long,
    val updatedAt: Long,
    val turns: Int,
    val state: String,
    val runningSince: Long,
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
    val tool: String = "",
    val toolId: String = "",
    val isError: Boolean = false,
    val durationMs: Long = 0,
    val askId: String = "",
    val method: String = "",
    val message: String = "",
    val options: List<String> = emptyList(),
    val placeholder: String = "",
    val steer: Boolean = false,
    val thumbs: List<String> = emptyList(),
    val parent: String = "",
)

data class ModelInfo(val provider: String, val name: String, val params: String, val vision: Boolean = false)

data class Photo(val data: String, val mimeType: String, val thumb: String)

val EFFORT_LEVELS = listOf("off", "minimal", "low", "medium", "high")

data class ModelCatalog(val models: List<ModelInfo>, val errors: Map<String, String>, val lms: Boolean)

fun providerLabel(provider: String): String = when (provider) {
    "lmstudio" -> "LM Studio"
    "ollama" -> "Ollama"
    else -> provider
}

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
    models = when (val m = o.opt("models")) {
        is JSONArray -> m.strings().ifEmpty { null }
        is String -> listOf(m)
        else -> null
    },
    requiresModel = o.optBoolean("requiresModel"),
    effort = o.optBoolean("effort"),
    images = o.str("images"),
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
    now = o.optLong("now"),
)

fun parseSession(o: JSONObject) = SessionSummary(
    id = o.str("id"),
    title = o.str("title"),
    harness = o.str("harness"),
    cwd = o.str("cwd"),
    model = o.str("model"),
    provider = o.str("provider"),
    thinking = o.str("thinking"),
    permissions = o.str("permissions"),
    createdAt = o.optLong("createdAt"),
    updatedAt = o.optLong("updatedAt"),
    turns = o.optInt("turns"),
    state = o.str("state"),
    runningSince = o.optLong("runningSince"),
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
    tool = o.str("tool"),
    toolId = o.str("toolId"),
    isError = o.optBoolean("isError"),
    durationMs = o.optLong("durationMs"),
    askId = o.str("askId"),
    method = o.str("method"),
    message = o.str("message"),
    options = o.optJSONArray("options").strings(),
    placeholder = o.str("placeholder"),
    steer = o.optBoolean("steer"),
    thumbs = o.optJSONArray("thumbs").strings(),
    parent = o.str("parent"),
)

fun parseEvents(a: JSONArray?) = a.mapObjects(::parseEvent)

fun parseModels(o: JSONObject): ModelCatalog {
    val models = o.optJSONArray("lmstudio").mapObjects { ModelInfo("lmstudio", it.str("name"), it.str("params"), it.optBoolean("vision")) } +
        o.optJSONArray("ollama").mapObjects { ModelInfo("ollama", it.str("name"), it.str("params")) }
    val errs = o.optJSONObject("errors")
    val errors = errs?.keys()?.asSequence()?.associateWith { errs.str(it) }.orEmpty()
    return ModelCatalog(models, errors, o.optBoolean("lms"))
}

fun parseListing(o: JSONObject) = DirListing(
    path = o.str("path"),
    parent = o.strOrNull("parent"),
    dirs = o.optJSONArray("dirs").strings(),
    git = o.optBoolean("git"),
)
