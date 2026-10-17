package dev.kyleporter.harnessremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyleporter.harnessremote.Hub
import dev.kyleporter.harnessremote.Route
import dev.kyleporter.harnessremote.net.BridgeClient
import dev.kyleporter.harnessremote.net.ConnState
import dev.kyleporter.harnessremote.net.baseName
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(hub: Hub, client: BridgeClient) {
    val conn by client.conn.collectAsState()
    val hello by client.hello.collectAsState()
    val sessions by client.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    val harnessNames = hello?.harnesses?.associate { it.id to it.name }.orEmpty()

    Scaffold(
        containerColor = Black,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { hub.disconnect() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Disconnect") }
                },
                title = {
                    Column {
                        Text(client.server.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ConnLine(conn)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (conn == ConnState.Connected) scope.launch { runCatching { client.request("list") } } else client.reconnectNow()
                    }) { Icon(Icons.Filled.Refresh, "Refresh") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Black),
            )
        },
        floatingActionButton = {
            if (conn == ConnState.Connected) {
                ExtendedFloatingActionButton(
                    onClick = { hub.route.value = Route.NewSession },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New session") },
                    containerColor = Red,
                    contentColor = OffWhite,
                )
            }
        },
    ) { pad ->
        val failed = conn as? ConnState.Failed
        when {
            failed != null -> Column(Modifier.fillMaxSize().padding(pad).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(failed.reason, color = Red)
                TextButton(onClick = { hub.disconnect() }) { Text("Back to computers") }
            }
            sessions.isEmpty() -> Column(Modifier.fillMaxSize().padding(pad).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("No sessions yet", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Start one with New session: pick a harness, a local model and a project folder.",
                    color = Gray,
                )
                hello?.let { h ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        h.harnesses.joinToString("\n") { "${if (it.available) "●" else "○"} ${it.name}${if (it.available) "" else "  (${it.reason ?: "not installed"})"}" },
                        fontFamily = Mono, fontSize = 13.sp, color = Gray,
                    )
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
                items(sessions, key = { it.id }) { s ->
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { hub.route.value = Route.Session(s.id) },
                        colors = CardDefaults.cardColors(containerColor = Surface),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    s.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(8.dp))
                                StateChip(s.state)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(harnessNames[s.harness] ?: s.harness, s.model.ifBlank { null }, baseName(s.cwd))
                                    .joinToString(" · "),
                                color = Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (s.preview.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    cleanTerminal(s.preview).trim().lines().takeLast(2).joinToString("\n"),
                                    Modifier.fillMaxWidth().background(Black).padding(8.dp),
                                    fontFamily = Mono, fontSize = 11.sp, color = OffWhite.copy(alpha = 0.75f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.updatedAt)),
                                color = Gray, fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnLine(conn: ConnState) {
    val (text, color) = when (conn) {
        ConnState.Connected -> "Connected" to Green
        ConnState.Connecting -> "Connecting…" to Amber
        is ConnState.Retrying -> "Reconnecting in ${conn.seconds}s · ${conn.reason}" to Amber
        is ConnState.Failed -> "Not authorized" to Red
        ConnState.Closed -> "Disconnected" to Gray
    }
    Text(text, color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
