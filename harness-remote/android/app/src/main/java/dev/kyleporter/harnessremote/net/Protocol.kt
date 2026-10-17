package dev.kyleporter.harnessremote.net

import org.json.JSONArray
import org.json.JSONObject

open class BridgeException(message: String) : Exception(message)

/** The request may not have reached the bridge: no connection, the connection dropped, or no reply in time. */
class ConnectionLost(message: String) : BridgeException(message)

/** A message typed while the phone couldn't reach the bridge. It goes out, in order, once the connection is back. */
data class Outgoing(val cid: String, val sessionId: String, val text: String, val photos: List<Photo>, val interrupt: Boolean)

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
    val review: Boolean,
    val reviewModel: String,
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
    val queued: Boolean = false,
    val preparing: Boolean = false,
    val verdict: String = "",
    val interrupt: Boolean = false,
    val thumbs: List<String> = emptyList(),
    val parent: String = "",
)

data class ModelInfo(val provider: String, val name: String, val params: String, val vision: Boolean = false)

data class Photo(val data: String, val mimeType: String, val thumb: String)

val EFFORT_LEVELS = listOf("off", "minimal", "low", "medium", "high")

data class Activity(val phase: String, val label: String, val chars: Long, val since: Long)

fun parseActivity(o: JSONObject?): Activity? =
    o?.let { Activity(it.str("phase"), it.str("label"), it.optLong("chars"), it.optLong("since")) }

data class ModelCatalog(val models: List<ModelInfo>, val errors: Map<String, String>, val lms: Boolean)

fun providerLabel(provider: String): String = when (provider) {
    "lmstudio" -> "LM Studio"
    "ollama" -> "Ollama"
    else -> provider
}

data class DirListing(val path: String, val parent: String?, val dirs: List<String>, val git: Boolean, val drives: List<String> = emptyList())

fun baseName(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

fun childPath(dir: String, name: String): String {
    val sep = if ('\\' in dir && '/' !in dir) "\\" else "/"
    return dir.trimEnd('/', '\\') + sep + name
}

/**
 * What to list for folder suggestions while a path is typed: the folder to look in and the start of the name.
 * "C:\\Prog" → ("C:\\", "Prog"); a bare name like "Docu" looks in the folder that is open.
 */
fun completionTarget(typed: String, current: String?): Pair<String, String>? {
    val t = typed.trim().trim('"')
    if (t.isEmpty()) return null
    if (Regex("^[A-Za-z]:$").matches(t)) return "$t\\" to ""
    val cut = t.lastIndexOfAny(charArrayOf('/', '\\'))
    if (cut < 0) return (current ?: return null) to t
    val dir = t.substring(0, cut + 1)
    val absolute = Regex("^([A-Za-z]:|[/\\\\~])").containsMatchIn(dir)
    return (if (absolute || current == null) dir else childPath(current, dir.trimEnd('/', '\\'))) to t.substring(cut + 1)
}

/** Folder names matching what was typed: exact first, then names that start with it, then names that contain it. */
fun rankFolders(dirs: List<String>, prefix: String): List<String> {
    val p = prefix.lowercase()
    if (p.isEmpty()) return dirs
    val exact = dirs.filter { it.lowercase() == p }
    val starts = dirs.filter { it.lowercase().startsWith(p) && it.lowercase() != p }
    val contains = dirs.filter { p in it.lowercase() && !it.lowercase().startsWith(p) }
    return exact + starts + contains
}

fun isFilesystemRoot(path: String): Boolean = path == "/" || Regex("^[A-Za-z]:[\\\\/]?$").matches(path)

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
    review = o.optBoolean("review"),
    reviewModel = o.str("reviewModel"),
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
    queued = o.optBoolean("queued"),
    preparing = o.optBoolean("preparing"),
    verdict = o.str("verdict"),
    interrupt = o.optBoolean("interrupt"),
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
    drives = o.optJSONArray("drives").strings(),
)
