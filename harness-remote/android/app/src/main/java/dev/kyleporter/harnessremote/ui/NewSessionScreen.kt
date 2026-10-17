package dev.kyleporter.harnessremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyleporter.harnessremote.Hub
import dev.kyleporter.harnessremote.Route
import dev.kyleporter.harnessremote.net.BridgeClient
import dev.kyleporter.harnessremote.net.DirListing
import dev.kyleporter.harnessremote.net.ModelInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewSessionScreen(hub: Hub, client: BridgeClient) {
    val hello by client.hello.collectAsState()
    val scope = rememberCoroutineScope()
    val harnesses = hello?.harnesses.orEmpty()
    var harnessId by remember { mutableStateOf(harnesses.firstOrNull { it.available }?.id) }
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var model by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<DirListing?>(null) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun browse(path: String?) {
        scope.launch {
            runCatching { client.listDir(path) }
                .onSuccess { listing = it }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(hello) {
        if (harnessId == null) harnessId = hello?.harnesses?.firstOrNull { it.available }?.id
    }
    LaunchedEffect(Unit) {
        browse(null)
        models = runCatching { client.models() }.getOrDefault(emptyList())
        if (model.isBlank()) models?.firstOrNull()?.let { model = it.name }
    }

    val harness = harnesses.find { it.id == harnessId }

    Scaffold(
        containerColor = Black,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { hub.route.value = Route.Sessions }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("New session") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Black),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Section("Harness")
            harnesses.forEach { h ->
                val selected = h.id == harnessId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, if (selected) Red else Surface), RoundedCornerShape(10.dp))
                        .background(Surface, RoundedCornerShape(10.dp))
                        .clickable(enabled = h.available) { harnessId = h.id }
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            h.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                            color = if (h.available) OffWhite else Gray,
                        )
                        Text(if (h.mode == "interactive") "interactive" else "per message", color = Gray, fontSize = 11.sp)
                        if (selected) Icon(Icons.Filled.Check, null, tint = Red, modifier = Modifier.padding(start = 6.dp))
                    }
                    if (h.description.isNotBlank()) Text(h.description, color = Gray, fontSize = 12.sp)
                    if (!h.available) Text("`${h.command}` not found on the computer's PATH", color = Red.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }

            if (harness?.models != null) {
                Section("Model")
                when (val m = models) {
                    null -> Text("Asking Ollama and LM Studio…", color = Gray, fontSize = 13.sp)
                    else -> {
                        val relevant = m.filter { it.provider == harness.models }.ifEmpty { m }
                        if (relevant.isEmpty()) {
                            Text("No local models found. Is Ollama running on the computer? You can still type a name.", color = Gray, fontSize = 13.sp)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            relevant.forEach { info ->
                                FilterChip(
                                    selected = model == info.name,
                                    onClick = { model = info.name },
                                    label = { Text(info.name + if (info.params.isNotBlank()) " · ${info.params}" else "", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red.copy(alpha = 0.25f)),
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = model, onValueChange = { model = it.trim() },
                    label = { Text("Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("Project folder")
            listing?.let { l ->
                Text(l.path + if (l.git) "  (git)" else "", fontFamily = Mono, fontSize = 12.sp, color = OffWhite)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 280.dp).background(Surface, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState()),
                ) {
                    l.parent?.let { parent ->
                        DirRow("..") { browse(parent) }
                    }
                    l.dirs.forEach { d -> DirRow("$d/") { browse(l.path.trimEnd('/') + "/" + d) } }
                    if (l.dirs.isEmpty() && l.parent == null) Text("Empty", Modifier.padding(12.dp), color = Gray)
                }
            } ?: Text("Loading…", color = Gray)

            Section("Title (optional)")
            OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth())

            error?.let { Text(it, color = Red, fontSize = 13.sp) }

            Spacer(Modifier.height(4.dp))
            Button(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !busy && harness?.available == true && listing != null && (!harness.requiresModel || model.isNotBlank()),
                onClick = {
                    val h = harness ?: return@Button
                    val dir = listing?.path ?: return@Button
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val s = client.create(h.id, dir, if (h.models != null) model else "", title)
                            hub.route.value = Route.Session(s.id)
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Start in ${listing?.path?.substringAfterLast('/')?.ifBlank { "/" } ?: "…"}") }
            TextButton(onClick = { hub.route.value = Route.Sessions }, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Gray) }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text.uppercase(), color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun DirRow(name: String, onClick: () -> Unit) {
    Text(
        name,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        fontFamily = Mono, fontSize = 13.sp,
    )
}
