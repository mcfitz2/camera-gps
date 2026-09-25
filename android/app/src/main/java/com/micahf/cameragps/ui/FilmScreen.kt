package com.micahf.cameragps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CameraRoll
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micahf.cameragps.Prefs
import com.micahf.cameragps.db.FilmDao
import com.micahf.cameragps.db.Roll
import com.micahf.cameragps.db.RollSummary
import kotlinx.coroutines.launch

/**
 * The Film tab: the roll in the camera, past rolls (each opens its frames),
 * and the shutter logger behind a chip in the app bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilmScreen(
    dao: FilmDao,
    loggerPaired: Boolean,
    onPairLogger: () -> Unit,
    onForgetLogger: () -> Unit,
    onCollect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openRoll by rememberSaveable { mutableStateOf<Long?>(null) }
    openRoll?.let { id ->
        BackHandler { openRoll = null }
        RollScreen(dao, id, onClose = { openRoll = null }, modifier)
        return
    }

    val rolls by dao.rolls().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var loggerSheet by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    val active = rolls.firstOrNull { it.finishedAt == null }
    val past = rolls.filter { it.id != active?.id }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Film") },
                actions = {
                    LoggerChip(loggerPaired, onClick = { if (loggerPaired) loggerSheet = true else onPairLogger() })
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
        floatingActionButton = {
            if (active != null) {
                ExtendedFloatingActionButton(
                    onClick = { loading = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Load film") },
                )
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
        ) {
            item {
                if (active == null) {
                    EmptyState(
                        Icons.Outlined.CameraRoll,
                        "No film loaded",
                        "Load a roll to keep its frames together. Until then, shots start a new untitled roll.",
                    ) {
                        Button(onClick = { loading = true }) { Text("Load film") }
                    }
                } else {
                    LoadedRoll(dao, active, onOpen = { openRoll = active.id }, onFinish = { finishing = true })
                }
            }
            if (past.isNotEmpty()) {
                item { SectionHeader("Previous rolls", Modifier.padding(top = 16.dp)) }
            }
            items(past, key = { it.id }) { roll ->
                val context = LocalContext.current
                ListItem(
                    headlineContent = { Text(roll.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull(stockLine(roll.stock, null), frames(roll.frames)).joinToString(" · "))
                    },
                    trailingContent = { Text(shortDate(context, roll.loadedAt)) },
                    modifier = Modifier.clickable { openRoll = roll.id },
                )
            }
        }
    }

    if (loading) {
        RollSheet(
            dao = dao,
            initial = null,
            replacing = active?.name,
            onDismiss = { loading = false },
            onSave = { roll ->
                loading = false
                scope.launch { dao.loadRoll(roll) }
            },
        )
    }
    if (loggerSheet) {
        LoggerSheet(
            onDismiss = { loggerSheet = false },
            onCollect = {
                loggerSheet = false
                onCollect()
            },
            onForget = {
                loggerSheet = false
                onForgetLogger()
            },
        )
    }
    if (finishing && active != null) {
        AlertDialog(
            onDismissRequest = { finishing = false },
            title = { Text("Finish ${active.name}?") },
            text = { Text("It moves to previous rolls. New shots start an untitled roll until you load film.") },
            confirmButton = {
                TextButton(onClick = {
                    finishing = false
                    scope.launch { dao.finish(active.id, System.currentTimeMillis()) }
                }) { Text("Finish") }
            },
            dismissButton = { TextButton(onClick = { finishing = false }) { Text("Cancel") } },
        )
    }
}

private fun frames(n: Int) = if (n == 1) "1 frame" else "$n frames"

@Composable
private fun LoggerChip(paired: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(if (paired) "Logger" else "Pair logger") },
        leadingIcon = {
            StatusDot(if (paired) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        },
    )
}

/** The roll in the camera: how far through it you are and the last frame. */
@Composable
private fun LoadedRoll(dao: FilmDao, roll: RollSummary, onOpen: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    val frames by remember(roll.id) { dao.frames(roll.id) }.collectAsStateWithLifecycle(emptyList())
    val last = frames.lastOrNull { it.takenAt != null }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("In the camera", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(roll.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            stockLine(roll.stock, roll.iso)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${roll.frames}", style = MaterialTheme.typography.displayMedium)
                Text(
                    " / ${roll.capacity}",
                    style = MaterialTheme.typography.titleLarge.tabular(),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (roll.frames.toFloat() / roll.capacity.coerceAtLeast(1)).coerceAtMost(1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                last?.let { f -> listOfNotNull("Last frame ${time(context, f.takenAt!!)}", f.place).joinToString(" · ") }
                    ?: "No frames yet",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpen) { Text("Frames") }
                TextButton(onClick = onFinish) { Text("Finish roll") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoggerSheet(onDismiss: () -> Unit, onCollect: () -> Unit, onForget: () -> Unit) {
    val context = LocalContext.current
    val lastSeen = remember { Prefs(context).shutterLastSeen }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    IconBadge(
                        Icons.Outlined.Sensors,
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                        size = 48.dp,
                    )
                },
                headlineContent = { Text("Shutter logger", style = MaterialTheme.typography.titleLarge) },
                supportingContent = {
                    Text(
                        if (lastSeen == 0L) "Shots are collected automatically"
                        else "Last collected ${ago(lastSeen, System.currentTimeMillis()).lowercase()}",
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.Sync, null) },
                headlineContent = { Text("Collect now") },
                supportingContent = { Text("Read any shots waiting on the logger") },
                modifier = Modifier.clickable(onClick = onCollect),
            )
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(Icons.Outlined.LinkOff, null) },
                headlineContent = { Text("Forget logger") },
                supportingContent = { Text("Stop collecting from it; pair again any time") },
                modifier = Modifier.clickable(onClick = onForget),
            )
        }
    }
}

private val isoPresets = listOf(100, 200, 400, 800, 1600, 3200)
private val exposurePresets = listOf(12, 24, 36)

/**
 * Loads a new roll ([initial] null) or edits one. [replacing] names the roll
 * that loading will finish.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RollSheet(
    dao: FilmDao,
    initial: Roll?,
    replacing: String?,
    onDismiss: () -> Unit,
    onSave: (Roll) -> Unit,
) {
    val context = LocalContext.current
    val recent by dao.recentStocks().collectAsStateWithLifecycle(emptyList())
    var stock by remember { mutableStateOf(initial?.stock ?: "") }
    var iso by remember { mutableStateOf(initial?.iso) }
    var capacity by remember { mutableStateOf(initial?.capacity ?: 36) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    val loadedAt = remember { initial?.loadedAt ?: System.currentTimeMillis() }
    val defaultName = "${stock.trim().ifEmpty { "Roll" }} · ${shortDate(context, loadedAt)}"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (initial == null) "Load film" else "Edit roll", style = MaterialTheme.typography.headlineSmall)

            OutlinedTextField(
                stock,
                { stock = it },
                label = { Text("Film stock") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val suggestions = recent.filter { it != stock }
            if (suggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { s -> SuggestionChip(onClick = { stock = s }, label = { Text(s) }) }
                }
            }

            Text("ISO", style = MaterialTheme.typography.titleSmall)
            PresetRow(isoPresets, iso, onChange = { iso = it })

            Text("Exposures", style = MaterialTheme.typography.titleSmall)
            PresetRow(exposurePresets, capacity, onChange = { capacity = it ?: 36 })

            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Name") },
                placeholder = { Text(defaultName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (replacing != null) {
                Text(
                    "Finishes $replacing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    val roll = (initial ?: Roll(name = "", loadedAt = loadedAt)).copy(
                        name = name.trim().ifEmpty { defaultName },
                        stock = stock.trim().ifEmpty { null },
                        iso = iso,
                        capacity = capacity,
                    )
                    onSave(roll)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (initial == null) "Load film" else "Save") }
        }
    }
}

/** Chips for common values, then a field for anything else. */
@Composable
private fun PresetRow(presets: List<Int>, value: Int?, onChange: (Int?) -> Unit) {
    var other by remember { mutableStateOf(value?.takeIf { it !in presets }?.toString() ?: "") }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        items(presets) { p ->
            FilterChip(
                selected = value == p,
                onClick = {
                    other = ""
                    onChange(if (value == p) null else p)
                },
                label = { Text("$p") },
            )
        }
        item {
            // Chip-sized, so it lines up with the presets.
            val colors = MaterialTheme.colorScheme
            val style = MaterialTheme.typography.labelLarge
            BasicTextField(
                other,
                {
                    other = it.filter(Char::isDigit).take(5)
                    onChange(other.toIntOrNull())
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = style.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .width(80.dp)
                    .height(32.dp)
                    .border(1.dp, if (other.isEmpty()) colors.outline else colors.primary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (other.isEmpty()) Text("Other", style = style, color = colors.onSurfaceVariant)
                        field()
                    }
                },
            )
        }
    }
}
