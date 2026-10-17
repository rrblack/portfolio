package dev.kyleporter.harnessremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import dev.kyleporter.harnessremote.net.Photo
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyleporter.harnessremote.Hub
import dev.kyleporter.harnessremote.Route
import dev.kyleporter.harnessremote.net.BridgeClient
import dev.kyleporter.harnessremote.net.BridgeEvent
import dev.kyleporter.harnessremote.net.ConnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Item {
    val key: String
    data class User(override val key: String, val text: String, val steer: Boolean, val queued: Boolean, val thumbs: List<String>) : Item
    data class Output(override val key: String, val text: String, val err: Boolean) : Item
    data class Note(override val key: String, val text: String, val bad: Boolean) : Item
    data class Assistant(override val key: String, val text: String, val ts: Long = 0) : Item
    data class Thinking(override val key: String, val text: String, val startTs: Long, val endTs: Long?, val done: Boolean = endTs != null) : Item
    data class Tool(
        override val key: String,
        val toolId: String,
        val nested: Boolean,
        val name: String,
        val summary: String,
        val result: String?,
        val isError: Boolean,
        val durationMs: Long,
    ) : Item
    data class Ask(
        override val key: String,
        val askId: String,
        val method: String,
        val title: String,
        val message: String,
        val options: List<String>,
        val placeholder: String,
        val answer: String?,
    ) : Item
}

private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]|[\u0000-\u0007\u000B\u000C\u000E-\u001F]")

fun cleanTerminal(raw: String): String {
    val noAnsi = raw.replace(ansi, "")
    return noAnsi.split('\n').joinToString("\n") { line ->
        val seg = line.split('\r').lastOrNull { it.isNotEmpty() } ?: ""
        if ('\b' !in seg) seg else buildString { seg.forEach { c -> if (c == '\b') { if (isNotEmpty()) setLength(length - 1) } else append(c) } }
    }
}

private const val MAX_BLOCK = 6000

private fun buildItems(events: List<BridgeEvent>, interactive: Boolean): List<Item> {
    val out = ArrayList<Item>()
    val buf = StringBuilder()
    var bufKey = ""
    var bufKind = ""
    var bufTs = 0L
    var part = 0
    val tools = HashMap<String, Int>()
    val asks = HashMap<String, Int>()
    fun flush(nextTs: Long? = null) {
        if (buf.isEmpty()) return
        when (bufKind) {
            "text" -> buf.toString().trim().takeIf { it.isNotEmpty() }?.let { out += splitThinkTags(bufKey, it, bufTs) }
            "think" -> buf.toString().trim().takeIf { it.isNotEmpty() }?.let { out += Item.Thinking(bufKey, it, bufTs, nextTs) }
            else -> {
                val text = cleanTerminal(buf.toString()).trimEnd('\n')
                if (text.isNotBlank()) out += Item.Output(bufKey, text, bufKind == "err")
            }
        }
        buf.setLength(0)
    }
    for (e in events) {
        if (buf.isNotEmpty() && e.kind != bufKind && bufKind == "think") flush(e.ts)
        when (e.kind) {
            "out", "err", "text", "think" -> {
                if (buf.isNotEmpty() && e.kind != bufKind) flush()
                if (buf.isEmpty()) { bufKey = "${e.kind}${e.seq}"; bufKind = e.kind; bufTs = e.ts }
                buf.append(e.text)
                if ((bufKind == "out" || bufKind == "err") && buf.length > MAX_BLOCK) {
                    val cut = buf.lastIndexOf("\n").takeIf { it > MAX_BLOCK / 2 } ?: buf.length
                    val rest = buf.substring(cut)
                    buf.setLength(cut)
                    flush()
                    buf.append(rest.trimStart('\n'))
                    bufKey = "$bufKind${e.seq}.${++part}"
                }
            }
            "user" -> { flush(); out += Item.User("u${e.seq}", e.text, e.steer, e.queued, e.thumbs) }
            "tool" -> {
                flush()
                tools[e.toolId] = out.size
                out += Item.Tool("t${e.seq}", e.toolId, e.parent.isNotEmpty(), e.tool, e.text, null, false, 0)
            }
            "toolres" -> {
                flush()
                val i = tools[e.toolId]
                val t = i?.let { out[it] as? Item.Tool }
                if (i != null && t != null) out[i] = t.copy(result = e.text, isError = e.isError, durationMs = e.durationMs)
                else out += Item.Tool("t${e.seq}", e.toolId, e.parent.isNotEmpty(), e.tool, "", e.text, e.isError, e.durationMs)
            }
            "ask" -> {
                flush()
                asks[e.askId] = out.size
                out += Item.Ask("a${e.seq}", e.askId, e.method, e.text, e.message, e.options, e.placeholder, null)
            }
            "answer" -> {
                val i = asks[e.askId]
                val a = i?.let { out[it] as? Item.Ask }
                if (i != null && a != null) out[i] = a.copy(answer = e.text)
            }
            "exit" -> {
                flush()
                when {
                    e.text.startsWith("Stopped") -> out += Item.Note("n${e.seq}", "Interrupted", false)
                    e.code == -1 -> out += Item.Note("n${e.seq}", e.text, true)
                    e.code != null && e.code != 0 -> out += Item.Note("n${e.seq}", "Error · exited with code ${e.code}", true)
                    interactive -> out += Item.Note("n${e.seq}", "Session ended", false)
                }
            }
            else -> { flush(); out += Item.Note("n${e.seq}", e.text, false) }
        }
    }
    flush()
    return out
}

private val thinkOpen = Regex("<(think|thinking|reasoning)>", RegexOption.IGNORE_CASE)
private val thinkClose = Regex("</(think|thinking|reasoning)>", RegexOption.IGNORE_CASE)

// Models whose server does not separate reasoning send it inline as <think>…</think>. Often the chat
// template already opened the tag, so only </think> shows up and everything before it is reasoning.
private fun splitThinkTags(key: String, text: String, ts: Long): List<Item> {
    val firstOpen = thinkOpen.find(text)
    val firstClose = thinkClose.find(text)
    if (firstOpen == null && firstClose == null) return listOf(Item.Assistant(key, text, ts))
    val out = ArrayList<Item>()
    var rest = text
    var n = 0
    if (firstClose != null && (firstOpen == null || firstClose.range.first < firstOpen.range.first)) {
        rest.substring(0, firstClose.range.first).trim().takeIf { it.isNotEmpty() }?.let { out += Item.Thinking("$key.k${n++}", it, ts, null, done = true) }
        rest = rest.substring(firstClose.range.last + 1)
    }
    while (rest.isNotEmpty()) {
        val open = thinkOpen.find(rest)
        if (open == null) {
            rest.trim().takeIf { it.isNotEmpty() }?.let { out += Item.Assistant("$key.a${n++}", it, ts) }
            break
        }
        rest.substring(0, open.range.first).trim().takeIf { it.isNotEmpty() }?.let { out += Item.Assistant("$key.a${n++}", it, ts) }
        rest = rest.substring(open.range.last + 1)
        val close = thinkClose.find(rest)
        if (close == null) {
            out += Item.Thinking("$key.k${n++}", rest.trim(), ts, null, done = false)
            break
        }
        rest.substring(0, close.range.first).trim().takeIf { it.isNotEmpty() }?.let { out += Item.Thinking("$key.k${n++}", it, ts, null, done = true) }
        rest = rest.substring(close.range.last + 1)
    }
    return out
}

private fun toolLabel(name: String) = name.replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(hub: Hub, client: BridgeClient, id: String) {
    val sessions by client.sessions.collectAsState()
    val hello by client.hello.collectAsState()
    val conn by client.conn.collectAsState()
    val events by client.events(id).collectAsState()
    val progress by client.progress(id).collectAsState()
    val session = sessions.find { it.id == id }
    val harness = hello?.harnesses?.find { it.id == session?.harness }
    val interactive = harness?.mode == "interactive"
    val structured = harness?.mode == "rpc"
    val items = remember(events, interactive) { buildItems(events, interactive) }
    val showWorking = session != null && (session.state == "waiting" || (!interactive && session.state == "running"))
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val photos = remember { mutableStateListOf<Photo>() }
    val context = LocalContext.current
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        scope.launch {
            for (uri in uris) {
                runCatching { loadPhoto(context, uri) }.onSuccess { photos += it }.onFailure { error = "Could not read a photo: ${it.message}" }
            }
        }
    }

    LaunchedEffect(id, conn) {
        if (conn == ConnState.Connected) runCatching { client.subscribe(id) }.onFailure { error = it.message }
    }

    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(events.size, showWorking, progress) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0 && (atBottom || listState.firstVisibleItemIndex == 0)) listState.scrollToItem(count - 1, Int.MAX_VALUE / 2)
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            error = null
            try { block() } catch (e: Exception) { error = e.message }
        }
    }

    val activity = when (val last = items.lastOrNull()) {
        is Item.Tool -> if (last.result == null) "Running ${toolLabel(last.name)}" else null
        is Item.Thinking -> "Thinking"
        is Item.Assistant -> "Writing"
        else -> null
    }
    val busy = session?.busy == true

    Scaffold(
        containerColor = Black,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { hub.route.value = Route.Sessions }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Column {
                        Text(session?.title ?: "Session", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (conn != ConnState.Connected) ConnLine(conn)
                            else {
                                StateChip(session?.state ?: "idle")
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    listOfNotNull(
                                        session?.model?.ifBlank { null } ?: harness?.name,
                                        session?.thinking?.ifBlank { null }?.let { "effort $it" },
                                    ).joinToString(" · ") + if (harness?.models != null) "  ▾" else "",
                                    color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable(enabled = harness?.models != null || harness?.effort == true) { showSettings = true },
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (session?.busy == true) {
                        IconButton(onClick = { act { client.stop(id) } }) { Icon(Icons.Filled.Close, "Stop", tint = Red) }
                    }
                    if (interactive && session?.state == "exited") {
                        IconButton(onClick = { act { client.restart(id) } }) { Icon(Icons.Filled.Refresh, "Restart") }
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete", tint = Gray) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Black),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    ItemView(
                        item,
                        answerable = session?.state == "waiting",
                        progress = (item as? Item.Tool)?.let { progress[it.toolId] },
                        thinkingLive = busy && item === items.lastOrNull(),
                    ) { askId, value, confirmed, cancelled ->
                        act { client.answer(id, askId, value, confirmed, cancelled) }
                    }
                }
                if (showWorking && session != null) {
                    item(key = "working") {
                        WorkingLine(
                            waiting = session.state == "waiting",
                            waitingOnCard = items.any { it is Item.Ask && it.answer == null },
                            activity = activity,
                            startedAtLocal = session.runningSince + client.clockOffset,
                        )
                    }
                }
            }

            if (!structured && (session?.state == "waiting" || (interactive && session?.busy == true))) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val keys = buildList {
                        add("y" to "y\n")
                        add("n" to "n\n")
                        add("Enter" to "\n")
                        if (interactive) {
                            add("Esc" to "\u001B")
                            add("Tab" to "\t")
                            add("↑" to "\u001B[A")
                            add("↓" to "\u001B[B")
                            add("Ctrl-C" to "\u0003")
                            add("Ctrl-D" to "\u0004")
                        }
                    }
                    keys.forEach { (label, data) ->
                        AssistChip(
                            onClick = { act { client.stdin(id, if (interactive) data.replace("\n", "\r") else data) } },
                            label = { Text(label, fontFamily = Mono, fontSize = 12.sp) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = Surface, labelColor = OffWhite),
                        )
                    }
                }
            }

            error?.let {
                Text(it, color = Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            if (photos.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().background(Surface).horizontalScroll(rememberScrollState()).padding(start = 8.dp, top = 8.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    photos.forEachIndexed { i, photo ->
                        Box {
                            val bmp = remember(photo.thumb) { decodeThumb(photo.thumb) }
                            if (bmp != null) Image(bmp, null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            Text(
                                "✕", color = OffWhite, fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.TopEnd).background(Black.copy(alpha = 0.7f), CircleShape)
                                    .clickable { photos.removeAt(i) }.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Surface).navigationBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = photos.size < 4,
                ) { Icon(Icons.Filled.Add, "Attach photos", tint = Gray) }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                structured && session?.busy == true -> "Add a message: ${harness?.name} reads it after this step"
                                session?.state == "waiting" -> "Answer the prompt…"
                                session?.busy == true && !interactive -> "Queue a message for after this turn"
                                else -> "Message ${harness?.name ?: "the harness"}"
                            },
                            color = Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Red, unfocusedBorderColor = SurfaceHigh),
                )
                val canSend = (input.isNotBlank() || photos.isNotEmpty()) && conn == ConnState.Connected
                IconButton(
                    enabled = canSend,
                    onClick = {
                        val text = input
                        val sending = photos.toList()
                        input = ""
                        photos.clear()
                        act {
                            try { client.send(id, text, sending) } catch (e: Exception) { input = text; photos.addAll(sending); throw e }
                        }
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (canSend) Red else Gray) }
            }
        }
    }

    if (showSettings && session != null && harness != null) {
        SessionSettingsDialog(
            client = client,
            harnessModels = harness.models.orEmpty(),
            showEffort = harness.effort,
            currentModel = session.model,
            currentProvider = session.provider,
            currentEffort = session.thinking,
            busy = busy,
            onDismiss = { showSettings = false },
            onModel = { model, provider -> act { client.setModel(id, model, provider) } },
            onEffort = { level -> act { client.setEffort(id, level) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Surface,
            title = { Text("Delete session?") },
            text = { Text("Stops the process if it is running and removes the transcript from the bridge. Files the agent changed stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    act {
                        client.delete(id)
                        hub.route.value = Route.Sessions
                    }
                }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private typealias Answer = (askId: String, value: String?, confirmed: Boolean?, cancelled: Boolean) -> Unit

@Composable
private fun ItemView(item: Item, answerable: Boolean, progress: String?, thinkingLive: Boolean, onAnswer: Answer) {
    when (item) {
        is Item.User -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (item.thumbs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    item.thumbs.forEach { t ->
                        val bmp = remember(t) { decodeThumb(t) }
                        if (bmp != null) Image(bmp, null, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    }
                }
            }
            if (item.text.isNotBlank()) SelectionContainer {
                Text(
                    item.text,
                    Modifier.widthIn(max = 320.dp).background(Red.copy(alpha = 0.18f), RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = OffWhite, fontSize = 15.sp,
                )
            }
            if (item.steer) Text("sent mid-task · read after the current step", color = Gray, fontSize = 10.sp)
            if (item.queued) Text("queued · runs when the current turn finishes", color = Gray, fontSize = 10.sp)
        }
        is Item.Assistant -> Row(Modifier.fillMaxWidth()) {
            Dot(OffWhite, Modifier.padding(top = 8.dp))
            SelectionContainer { MarkdownText(item.text) }
        }
        is Item.Thinking -> ThinkingBlock(item, live = !item.done && thinkingLive)
        is Item.Tool -> ToolBlock(item, progress)
        is Item.Ask -> AskCard(item, answerable, onAnswer)
        is Item.Output -> if (item.err) WarningBlock(item) else SelectionContainer {
            Text(
                item.text,
                Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(8.dp)).padding(10.dp),
                fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, color = OffWhite,
            )
        }
        is Item.Note -> Text(
            item.text,
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            color = if (item.bad) Red else Gray, fontSize = 11.sp, textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ThinkingBlock(item: Item.Thinking, live: Boolean) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val seconds = item.endTs?.let { ((it - item.startTs) / 1000).coerceAtLeast(1) }
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
                if (live) Spinner(Gray, 12.dp) else Text("✻", color = Gray, fontSize = 12.sp)
            }
            Text(
                when {
                    live -> "Thinking…"
                    seconds != null -> "Thought for ${seconds}s"
                    else -> "Thought"
                },
                color = Gray, fontSize = 13.sp, fontStyle = FontStyle.Italic,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, if (expanded) "Hide thinking" else "Show thinking",
                tint = Gray, modifier = Modifier.padding(start = 2.dp).size(18.dp).rotate(turn),
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(item.text, Modifier.padding(start = 18.dp, top = 2.dp), color = Gray, fontSize = 13.sp, fontStyle = FontStyle.Italic, lineHeight = 18.sp)
            }
        } else if (live) {
            Text(
                item.text.trimEnd().lines().takeLast(2).joinToString("\n"),
                Modifier.padding(start = 18.dp),
                color = Gray.copy(alpha = 0.8f), fontSize = 13.sp, fontStyle = FontStyle.Italic, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val TOOL_PREVIEW_LINES = 3
private const val LIVE_LINES = 8

@Composable
private fun ToolBlock(item: Item.Tool, progress: String?) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val running = item.result == null
    val dot = when {
        running -> Amber
        item.isError -> Red
        else -> Green
    }
    val lines = item.result?.trimEnd()?.lines().orEmpty()
    val long = lines.size > TOOL_PREVIEW_LINES
    Column(Modifier.fillMaxWidth().padding(start = if (item.nested) 18.dp else 0.dp).clickable(enabled = long) { expanded = !expanded }) {
        Row {
            Dot(dot, Modifier.padding(top = 6.dp))
            Text(
                buildString {
                    append(toolLabel(item.name))
                    if (item.summary.isNotBlank()) append("(").append(item.summary).append(")")
                },
                color = OffWhite, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(start = 18.dp)) {
            Text("⎿  ", color = Gray, fontFamily = Mono, fontSize = 12.sp)
            val body = when {
                running && !progress.isNullOrBlank() -> {
                    val live = cleanTerminal(progress).trimEnd().lines()
                    (if (live.size > LIVE_LINES) listOf("… ${live.size - LIVE_LINES} earlier lines") else emptyList<String>())
                        .plus(live.takeLast(LIVE_LINES)).joinToString("\n")
                }
                running -> "Running… no output yet"
                lines.isEmpty() || lines.all { it.isBlank() } -> if (item.isError) "Failed" else "(no output)"
                expanded || !long -> lines.joinToString("\n")
                else -> lines.take(TOOL_PREVIEW_LINES).joinToString("\n") + "\n… +${lines.size - TOOL_PREVIEW_LINES} lines (tap to expand)"
            }
            SelectionContainer {
                Text(
                    body,
                    color = if (item.isError) Red.copy(alpha = 0.9f) else Gray,
                    fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun AskCard(item: Item.Ask, answerable: Boolean, onAnswer: Answer) {
    val heading = item.title.lineSequence().firstOrNull().orEmpty()
    val detail = (item.title.lines().drop(1) + listOfNotNull(item.message.ifBlank { null })).joinToString("\n").trim()
    val live = item.answer == null && answerable
    var text by rememberSaveable(item.key) { mutableStateOf(item.placeholder) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, if (live) Amber else SurfaceHigh), RoundedCornerShape(10.dp))
            .background(Surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(heading, color = if (live) Amber else OffWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (detail.isNotBlank()) {
            Text(
                detail,
                Modifier.fillMaxWidth().background(Black, RoundedCornerShape(6.dp)).padding(8.dp),
                color = OffWhite, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        when {
            item.answer != null -> Text("→ ${item.answer}", color = Gray, fontSize = 13.sp)
            !answerable -> Text("No longer waiting for an answer", color = Gray, fontSize = 12.sp)
            item.method == "select" -> item.options.forEachIndexed { i, option ->
                val negative = option.equals("No", true) || option.startsWith("No,") || option.equals("Deny", true) || option.equals("Block", true)
                OutlinedButton(
                    onClick = { onAnswer(item.askId, option, null, false) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, if (negative) SurfaceHigh else Amber.copy(alpha = 0.6f)),
                ) {
                    Text("${i + 1}. $option", color = if (negative) Gray else OffWhite, modifier = Modifier.fillMaxWidth(), fontSize = 13.sp)
                }
            }
            item.method == "confirm" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAnswer(item.askId, null, true, false) }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, Amber.copy(alpha = 0.6f))) {
                    Text("Yes", color = OffWhite)
                }
                OutlinedButton(onClick = { onAnswer(item.askId, null, false, false) }, modifier = Modifier.weight(1f)) { Text("No", color = Gray) }
            }
            else -> {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    maxLines = if (item.method == "editor") 10 else 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amber, unfocusedBorderColor = SurfaceHigh),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAnswer(item.askId, text, null, false) }, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, Amber.copy(alpha = 0.6f))) {
                        Text("Send", color = OffWhite)
                    }
                    OutlinedButton(onClick = { onAnswer(item.askId, null, null, true) }, modifier = Modifier.weight(1f)) { Text("Cancel", color = Gray) }
                }
            }
        }
    }
}

private const val WARNING_PREVIEW_LINES = 2

@Composable
private fun WarningBlock(item: Item.Output) {
    val lines = item.text.lines()
    val long = lines.size > WARNING_PREVIEW_LINES
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(Surface.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(enabled = long) { expanded = !expanded },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Amber.copy(alpha = 0.6f), RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                if (long) "stderr · ${lines.size} lines · ${if (expanded) "tap to collapse" else "tap to expand"}" else "stderr",
                color = Amber.copy(alpha = 0.8f), fontSize = 10.sp,
            )
            Text(
                if (expanded || !long) item.text else lines.take(WARNING_PREVIEW_LINES).joinToString("\n") + " …",
                fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, color = Gray,
            )
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
    }
}

private val VERBS = listOf("Working", "Pondering", "Cooking", "Brewing", "Crafting", "Tinkering", "Computing")

@Composable
fun Spinner(color: androidx.compose.ui.graphics.Color, size: Dp = 14.dp) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "angle")
    val pulse by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        rotate(angle) {
            for (i in 0 until 6) {
                val a = Math.PI * i / 3
                val len = r * (if (i % 2 == 0) pulse else 1.55f - pulse)
                drawLine(
                    color, center,
                    center + Offset((cos(a) * len).toFloat(), (sin(a) * len).toFloat()),
                    strokeWidth = r * 0.32f, cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun WorkingLine(waiting: Boolean, waitingOnCard: Boolean, activity: String?, startedAtLocal: Long) {
    if (waiting) {
        Text(
            if (waitingOnCard) "● Waiting for your answer above" else "● Waiting for your input",
            color = Amber, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp),
        )
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val shimmer by rememberInfiniteTransition(label = "shimmer")
        .animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha")
    val verb = activity ?: VERBS[((startedAtLocal / 1000) % VERBS.size).toInt().let { if (it < 0) it + VERBS.size else it }]
    val secs = if (startedAtLocal > 0) ((now - startedAtLocal) / 1000).coerceAtLeast(0) else -1
    val elapsed = when {
        secs < 0 -> ""
        secs < 60 -> "${secs}s"
        else -> "${secs / 60}m ${(secs % 60).toString().padStart(2, '0')}s"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) { Spinner(Red, 13.dp) }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Red.copy(alpha = shimmer), fontWeight = FontWeight.Medium)) { append("$verb…") }
                if (elapsed.isNotEmpty()) withStyle(SpanStyle(color = Gray)) { append("  ($elapsed)") }
            },
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
