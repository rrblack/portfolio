package dev.kyleporter.harnessremote.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyleporter.harnessremote.Hub
import dev.kyleporter.harnessremote.net.BridgeClient
import dev.kyleporter.harnessremote.net.Server
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(hub: Hub) {
    val servers by hub.servers.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Server?>(null) }

    Scaffold(
        containerColor = Black,
        topBar = {
            TopAppBar(
                title = { Text("Harness Remote", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Black),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add computer") },
                containerColor = Red,
                contentColor = OffWhite,
            )
        },
    ) { pad ->
        if (servers.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Control your agent harnesses from here", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "1. On your computer run:\n    node harness-bridge.mjs\n   (Windows: double-click start-bridge.cmd)\n\n" +
                        "2. Tap Add computer and enter the address and 6-digit pairing code it prints.\n\n" +
                        "Away from home? Put both devices on Tailscale and use the computer's 100.x address.",
                    color = Gray,
                    fontFamily = Mono,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                items(servers, key = { it.id }) { s ->
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { hub.connect(s) },
                        colors = CardDefaults.cardColors(containerColor = Surface),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                Text(s.baseUrl, color = Gray, fontFamily = Mono, fontSize = 12.sp)
                            }
                            IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Filled.Delete, "Remove", tint = Gray) }
                        }
                    }
                }
            }
        }
    }

    if (adding) AddServerDialog(hub, onDone = { adding = false })

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${s.name}?") },
            text = { Text("This phone forgets the address and token. Sessions on the computer are not touched.") },
            confirmButton = { TextButton(onClick = { hub.removeServer(s); confirmDelete = null }) { Text("Remove", color = Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            containerColor = Surface,
        )
    }
}

@Composable
private fun AddServerDialog(hub: Hub, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var useToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        containerColor = Surface,
        title = { Text("Add computer") },
        text = {
            Column(Modifier.imePadding()) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address") }, placeholder = { Text("100.101.102.103:8787") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                if (useToken) {
                    OutlinedTextField(
                        value = token, onValueChange = { token = it.trim() },
                        label = { Text("Access token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                } else {
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("Pairing code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
                TextButton(onClick = { useToken = !useToken }) {
                    Text(if (useToken) "Use a pairing code instead" else "I have an access token", fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && address.isNotBlank() && (if (useToken) token.isNotBlank() else code.length == 6),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val base = Server.normalizeAddress(address)
                            val host = BridgeClient.health(hub.http, base)
                            val tok = if (useToken) token else BridgeClient.pair(hub.http, base, code, Build.MODEL).first
                            val server = Server(UUID.randomUUID().toString(), name.ifBlank { host.ifBlank { base } }, base, tok)
                            hub.saveServer(server)
                            onDone()
                            hub.connect(server)
                        } catch (e: Exception) {
                            error = e.message ?: "Could not reach the bridge"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp, color = OffWhite) else Text(if (useToken) "Save" else "Pair")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text("Cancel") } },
    )
}
