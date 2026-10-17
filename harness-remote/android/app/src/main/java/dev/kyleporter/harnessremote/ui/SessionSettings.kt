package dev.kyleporter.harnessremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyleporter.harnessremote.net.BridgeClient
import dev.kyleporter.harnessremote.net.EFFORT_LEVELS
import dev.kyleporter.harnessremote.net.ModelCatalog
import dev.kyleporter.harnessremote.net.providerLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionSettingsDialog(
    client: BridgeClient,
    harnessModels: List<String>,
    showEffort: Boolean,
    showReviewer: Boolean,
    reviewOn: Boolean,
    reviewModel: String,
    currentModel: String,
    currentProvider: String,
    currentEffort: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onModel: (model: String, provider: String) -> Unit,
    onEffort: (level: String) -> Unit,
    onReview: (enabled: Boolean, model: String?, provider: String?) -> Unit,
) {
    var catalog by remember { mutableStateOf<ModelCatalog?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { client.models() }.onSuccess { catalog = it }.onFailure { failed = it.message }
    }
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = Red.copy(alpha = 0.25f))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Session settings") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showEffort) {
                    Text("EFFORT", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EFFORT_LEVELS.forEach { level ->
                            FilterChip(
                                selected = (currentEffort.ifBlank { "medium" }) == level,
                                onClick = { onEffort(level) },
                                label = { Text(level, fontSize = 12.sp) },
                                colors = chipColors,
                            )
                        }
                    }
                    Text("How much the model thinks before answering. Applies right away.", color = Gray, fontSize = 11.sp)
                }
                if (showReviewer) {
                    Text("REVIEWER", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Check each command and file change before it runs",
                            color = OffWhite, fontSize = 13.sp, modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = reviewOn, onCheckedChange = { onReview(it, null, null) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = OffWhite),
                        )
                    }
                    if (reviewOn) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = reviewModel.isBlank(),
                                onClick = { onReview(true, "", null) },
                                label = { Text("Same as session", fontSize = 12.sp) },
                                colors = chipColors,
                            )
                            catalog?.models?.filter { it.provider in harnessModels }?.forEach { m ->
                                FilterChip(
                                    selected = m.name == reviewModel,
                                    onClick = { onReview(true, m.name, m.provider) },
                                    label = { Text(m.name, fontSize = 12.sp) },
                                    colors = chipColors,
                                )
                            }
                        }
                        Text("A smaller, faster model works well as the reviewer.", color = Gray, fontSize = 11.sp)
                    }
                }
                if (harnessModels.isNotEmpty()) {
                    Text("MODEL", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    when (val cat = catalog) {
                        null -> Text(failed ?: "Loading models…", color = Gray, fontSize = 13.sp)
                        else -> harnessModels.forEach { p ->
                            val list = cat.models.filter { it.provider == p }
                            Text(providerLabel(p), color = OffWhite, fontSize = 13.sp)
                            cat.errors[p]?.let { Text(it, color = Gray, fontSize = 11.sp) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                list.forEach { m ->
                                    FilterChip(
                                        selected = m.name == currentModel && p == currentProvider,
                                        onClick = { onModel(m.name, p) },
                                        label = { Text(m.name + if (m.vision) " · vision" else "", fontSize = 12.sp) },
                                        colors = chipColors,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        if (busy) "A new model takes over after the current step finishes. The conversation carries over."
                        else "Switching keeps the conversation. Pick a vision model to send photos.",
                        color = Gray, fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
