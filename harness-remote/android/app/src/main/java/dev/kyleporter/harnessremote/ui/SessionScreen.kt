package dev.kyleporter.harnessremote.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch

private sealed interface Item {
    val key: String
    data class User(override val key: String, val text: String) : Item
    data class Output(override val key: String, val text: String, val err: Boolean) : Item
    data class Note(override val key: String, val text: String, val bad: Boolean) : Item
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

private fun buildItems(events: List<BridgeEvent>): List<Item> {
    val out = ArrayList<Item>()
    val buf = StringBuilder()
    var bufKey = ""
    var bufErr = false
    var part = 0
    fun flush() {
        if (buf.isEmpty()) return
        val text = cleanTerminal(buf.toString()).trimEnd('\n')
        if (text.isNotBlank()) out += Item.Output(bufKey, text, bufErr)
        buf.setLength(0)
    }
    for (e in events) {
        when (e.kind) {
            "out", "err" -> {
                val err = e.kind == "err"
                if (buf.isNotEmpty() && err != bufErr) flush()
                if (buf.isEmpty()) { bufKey = "${if (err) "e" else "o"}${e.seq}"; bufErr = err }
                buf.append(e.text)
                if (buf.length > MAX_BLOCK) {
                    val cut = buf.lastIndexOf("\n").takeIf { it > MAX_BLOCK / 2 } ?: buf.length
                    val rest = buf.substring(cut)
                    buf.setLength(cut)
                    flush()
                    buf.append(rest.trimStart('\n'))
                    bufKey = "${if (bufErr) "e" else "o"}${e.seq}.${++part}"
                }
            }
            "user" -> { flush(); out += Item.User("u${e.seq}", e.text) }
            "exit" -> { flush(); out += Item.Note("n${e.seq}", e.text, e.code != null && e.code != 0) }
            else -> { flush(); out += Item.Note("n${e.seq}", e.text, false) }
        }
    }
    flush()
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(hub: Hub, client: BridgeClient, id: String) {
    val sessions by client.sessions.collectAsState()
    val hello by client.hello.collectAsState()
    val conn by client.conn.collectAsState()
    val events by client.events(id).collectAsState()
    val session = sessions.find { it.id == id }
    val harness = hello?.harnesses?.find { it.id == session?.harness }
    val interactive = harness?.mode == "interactive"
    val items = remember(events) { buildItems(events) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id, conn) {
        if (conn == ConnState.Connected) runCatching { client.subscribe(id) }.onFailure { error = it.message }
    }

    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    val lastSize = (items.lastOrNull() as? Item.Output)?.text?.length ?: 0
    LaunchedEffect(items.size, lastSize) {
        if (items.isNotEmpty() && (atBottom || listState.firstVisibleItemIndex == 0)) listState.scrollToItem(items.size - 1, Int.MAX_VALUE / 2)
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            error = null
            try { block() } catch (e: Exception) { error = e.message }
        }
    }

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
                                    listOfNotNull(harness?.name, session?.model?.ifBlank { null }).joinToString(" · "),
                                    color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                items(items, key = { it.key }) { item -> ItemView(item) }
            }

            if (session?.state == "waiting" || (interactive && session?.busy == true)) {
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

            Row(
                Modifier.fillMaxWidth().background(Surface).navigationBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                session?.state == "waiting" -> "Answer the prompt…"
                                session?.busy == true && !interactive -> "Working… (stop to send a new message)"
                                else -> "Message ${harness?.name ?: "the harness"}"
                            },
                            color = Gray,
                        )
                    },
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Red, unfocusedBorderColor = SurfaceHigh),
                )
                IconButton(
                    enabled = input.isNotBlank() && conn == ConnState.Connected,
                    onClick = {
                        val text = input
                        input = ""
                        act {
                            try { client.send(id, text) } catch (e: Exception) { input = text; throw e }
                        }
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (input.isNotBlank()) Red else Gray) }
            }
        }
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

@Composable
private fun ItemView(item: Item) {
    when (item) {
        is Item.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            SelectionContainer {
                Text(
                    item.text,
                    Modifier.widthIn(max = 320.dp).background(Red.copy(alpha = 0.18f), RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = OffWhite, fontSize = 15.sp,
                )
            }
        }
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
