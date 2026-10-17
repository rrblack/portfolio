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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import dev.kyleporter.harnessremote.net.ModelCatalog
import dev.kyleporter.harnessremote.net.providerLabel
import dev.kyleporter.harnessremote.net.baseName
import dev.kyleporter.harnessremote.net.childPath
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewSessionScreen(hub: Hub, client: BridgeClient) {
    val hello by client.hello.collectAsState()
    val scope = rememberCoroutineScope()
    val harnesses = hello?.harnesses.orEmpty()
    var harnessId by remember { mutableStateOf(harnesses.firstOrNull { it.available }?.id) }
    var catalog by remember { mutableStateOf<ModelCatalog?>(null) }
    var model by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var startingLms by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf<DirListing?>(null) }
    var title by remember { mutableStateOf("") }
    var askPermission by remember { mutableStateOf(true) }
    var review by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var typedPath by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val recent = remember { hub.recentFolders(client.server.id) }

    fun browse(path: String?, fallback: Boolean = false) {
        scope.launch {
            runCatching { client.listDir(path) }
                .onSuccess { listing = it; folderError = null; typedPath = "" }
                .onFailure { if (fallback) browse(null) else folderError = it.message }
        }
    }

    LaunchedEffect(hello) {
        if (harnessId == null) harnessId = hello?.harnesses?.firstOrNull { it.available }?.id
    }
    LaunchedEffect(Unit) {
        browse(recent.firstOrNull(), fallback = true)
        catalog = runCatching { client.models() }.getOrElse { error = it.message; ModelCatalog(emptyList(), emptyMap(), false) }
    }

    val harness = harnesses.find { it.id == harnessId }

    LaunchedEffect(harnessId, catalog) {
        val providers = harness?.models ?: return@LaunchedEffect
        if (provider !in providers) provider = providers.first()
        val known = catalog?.models.orEmpty().filter { it.provider in providers }
        if (known.none { it.name == model && it.provider == provider }) {
            known.minByOrNull { providers.indexOf(it.provider) }?.let { model = it.name; provider = it.provider }
        }
    }

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
                    if (!h.available) Text(h.reason ?: "`${h.command}` not found on the computer's PATH", color = Red.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }

            val providers = harness?.models
            if (providers != null) {
                Section("Model")
                when (val cat = catalog) {
                    null -> Text("Asking LM Studio and Ollama…", color = Gray, fontSize = 13.sp)
                    else -> providers.forEach { p ->
                        val list = cat.models.filter { it.provider == p }
                        Text(providerLabel(p), color = OffWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        cat.errors[p]?.let { msg ->
                            Text(msg, color = Gray, fontSize = 12.sp)
                            if (p == "lmstudio" && cat.lms) {
                                TextButton(enabled = !startingLms, onClick = {
                                    startingLms = true
                                    scope.launch {
                                        runCatching { client.startLmStudio() }
                                            .onSuccess { catalog = it }
                                            .onFailure { error = it.message }
                                        startingLms = false
                                    }
                                }) { Text(if (startingLms) "Starting LM Studio server…" else "Start LM Studio server", color = Red) }
                            }
                        }
                        if (list.isEmpty() && cat.errors[p] == null) Text("No models downloaded", color = Gray, fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            list.forEach { info ->
                                FilterChip(
                                    selected = model == info.name && provider == p,
                                    onClick = { model = info.name; provider = p },
                                    label = { Text(info.name + if (info.params.isNotBlank()) " · ${info.params}" else "", fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red.copy(alpha = 0.25f)),
                                )
                            }
                        }
                    }
                }
                if (providers.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Served by", color = Gray, fontSize = 12.sp)
                        providers.forEach { p ->
                            FilterChip(
                                selected = provider == p,
                                onClick = { provider = p },
                                label = { Text(providerLabel(p), fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red.copy(alpha = 0.25f)),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = model, onValueChange = { model = it.trim() },
                    label = { Text("Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            if (harness?.mode == "rpc") {
                Section("Permissions")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ask before commands and edits", color = OffWhite, fontSize = 14.sp)
                        Text(
                            if (askPermission) "You approve every command and file change from the phone. Reads and searches run without asking."
                            else "Commands and edits run without asking. Deletes still always ask, and deletes of your user folders are always blocked.",
                            color = Gray, fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = askPermission, onCheckedChange = { askPermission = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = OffWhite),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reviewer", color = OffWhite, fontSize = 14.sp)
                        Text(
                            "A second pass by the model checks each command and file change against your request before it runs. A concern always asks you first. Adds a few seconds per command.",
                            color = Gray, fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = review, onCheckedChange = { review = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = OffWhite),
                    )
                }
            }

            Section("Project folder")
            if (recent.isNotEmpty()) {
                Text("Recent", color = Gray, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { r ->
                        FilterChip(
                            selected = listing?.path == r,
                            onClick = { browse(r) },
                            label = { Text(baseName(r), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red.copy(alpha = 0.25f)),
                        )
                    }
                }
            }
            listing?.let { l ->
                val roots = hello?.roots.orEmpty()
                FlowRow(verticalArrangement = Arrangement.Center) {
                    pathCrumbs(l.path).forEachIndexed { i, (label, full) ->
                        val allowed = roots.any { root -> full.length >= root.trimEnd('/', '\\').length && full.startsWith(root.trimEnd('/', '\\'), ignoreCase = true) }
                        if (i > 0) Text(" › ", color = Gray, fontSize = 12.sp)
                        Text(
                            label, fontFamily = Mono, fontSize = 12.sp,
                            color = if (full == l.path) OffWhite else if (allowed) Red else Gray,
                            modifier = Modifier.clickable(enabled = allowed && full != l.path) { browse(full) },
                        )
                    }
                }
                if (l.drives.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        l.drives.forEach { d ->
                            FilterChip(
                                selected = l.path.startsWith(d, ignoreCase = true),
                                onClick = { browse(d) },
                                label = { Text(d.trimEnd('\\', '/'), fontSize = 12.sp, fontFamily = Mono) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red.copy(alpha = 0.25f)),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = typedPath,
                    onValueChange = { typedPath = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = Mono, fontSize = 13.sp),
                    placeholder = { Text("Or type a path, e.g. C:\\Program Files", fontSize = 13.sp, color = Gray) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (typedPath.isNotBlank()) browse(typedPath) }),
                    trailingIcon = {
                        if (typedPath.isNotBlank()) IconButton(onClick = { browse(typedPath) }) { Icon(Icons.Filled.Check, "Open this path", tint = Red) }
                    },
                )
                folderError?.let { Text(it, color = Red, fontSize = 12.sp) }
                Text(
                    "Selected: ${l.path}${if (l.git) "  (git repo)" else ""} · tap a folder below to open it",
                    color = Gray, fontSize = 11.sp,
                )
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).background(Surface, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState()),
                ) {
                    l.parent?.let { parent ->
                        DirRow("↑  up to ${baseName(parent)}") { browse(parent) }
                    }
                    l.dirs.forEach { d -> DirRow("$d/") { browse(childPath(l.path, d)) } }
                    if (l.dirs.isEmpty()) Text("No subfolders", Modifier.padding(12.dp), color = Gray, fontSize = 13.sp)
                }
            } ?: Text(folderError ?: "Loading…", color = if (folderError != null) Red else Gray)

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
                            val s = client.create(h.id, dir, if (h.models != null) model else "", if (h.models != null) provider else "", title, askPermission, review)
                            hub.rememberFolder(client.server.id, dir)
                            hub.route.value = Route.Session(s.id)
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Start in ${listing?.path?.let(::baseName) ?: "…"}") }
            TextButton(onClick = { hub.route.value = Route.Sessions }, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Gray) }
        }
    }
}

private fun pathCrumbs(path: String): List<Pair<String, String>> {
    val windows = '\\' in path && '/' !in path
    val sep = if (windows) '\\' else '/'
    var acc = if (windows) "" else "/"
    return path.split('/', '\\').filter { it.isNotEmpty() }.mapIndexed { i, part ->
        acc = when {
            windows && i == 0 -> "$part\\"
            acc.endsWith(sep) -> acc + part
            else -> "$acc$sep$part"
        }
        part to acc
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
