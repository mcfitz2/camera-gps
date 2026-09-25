package com.micahf.cameragps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micahf.cameragps.db.FilmDao
import com.micahf.cameragps.db.Frame
import com.micahf.cameragps.db.Roll
import com.micahf.cameragps.db.RollSummary
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The Film tab: the shutter logger, the roll in the camera, and past rolls,
 * each of which opens its frame log.
 */
@Composable
fun FilmScreen(
    dao: FilmDao,
    shutterAddress: String?,
    onChooseShutter: () -> Unit,
    onForgetShutter: () -> Unit,
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
    val active = rolls.firstOrNull { it.finishedAt == null }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Film", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
        }
        item {
            if (shutterAddress == null) {
                Text("Press reset on the shutter logger (it advertises for a minute), then choose it here.")
                Button(onClick = onChooseShutter) { Text("Choose shutter logger") }
            } else {
                Text("Shutter logger $shutterAddress")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCollect) { Text("Collect now") }
                    OutlinedButton(onClick = onForgetShutter) { Text("Forget") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active == null) {
                        Text("No roll loaded", style = MaterialTheme.typography.titleMedium)
                        Text("Shots go to a new untitled roll until you load one.")
                        Button(onClick = { loading = true }) { Text("Load roll") }
                    } else {
                        Text(active.name, style = MaterialTheme.typography.titleLarge)
                        describe(active)?.let { Text(it) }
                        Text("Frame ${active.frames} of ${active.capacity}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { openRoll = active.id }) { Text("Frames") }
                            Button(onClick = { loading = true }) { Text("Finish & load new") }
                        }
                    }
                }
            }
        }
        val past = rolls.filter { it.id != active?.id }
        if (past.isNotEmpty()) {
            item { Text("Rolls", style = MaterialTheme.typography.titleMedium) }
        }
        items(past, key = { it.id }) { roll ->
            Column(Modifier.fillMaxWidth().clickable { openRoll = roll.id }.padding(vertical = 8.dp)) {
                Text(roll.name)
                Text(
                    listOfNotNull(describe(roll), "${roll.frames} frames", date(roll.loadedAt)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
        }
    }

    if (loading) {
        RollDialog(
            title = if (active == null) "Load roll" else "Finish ${active.name} and load",
            initial = null,
            onDismiss = { loading = false },
            onSave = { name, stock, iso, capacity ->
                loading = false
                scope.launch {
                    dao.loadRoll(Roll(name = name, stock = stock, iso = iso, capacity = capacity, loadedAt = System.currentTimeMillis()))
                }
            },
        )
    }
}

/** One roll's frame log, with editing and export. */
@Composable
private fun RollScreen(dao: FilmDao, rollId: Long, onClose: () -> Unit, modifier: Modifier) {
    val roll by dao.roll(rollId).collectAsStateWithLifecycle(null)
    val frames by dao.frames(rollId).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editingRoll by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Frame?>(null) }
    val r = roll ?: return

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) { Text("‹ Rolls") }
            Text(r.name, style = MaterialTheme.typography.headlineMedium)
            describe(r)?.let { Text(it) }
            Text(
                "Loaded ${date(r.loadedAt)}" + (r.finishedAt?.let { " · finished ${date(it)}" } ?: "") +
                    " · ${frames.size} of ${r.capacity} frames",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { editingRoll = true }) { Text("Edit") }
                Button(onClick = { Export.share(context, r, frames) }, enabled = frames.isNotEmpty()) { Text("Export CSV") }
                OutlinedButton(onClick = { deleting = true }) { Text("Delete") }
            }
            if (frames.isEmpty()) Text("No frames yet.")
        }
        items(frames, key = { it.id }) { frame ->
            Row(
                Modifier.fillMaxWidth().clickable { editing = frame }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("${frame.number}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp))
                Column {
                    Text(frame.takenAt?.let(::dateTime) ?: "Blank frame")
                    place(frame)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    frame.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            HorizontalDivider()
        }
        item {
            OutlinedButton(
                onClick = { scope.launch { dao.insertBlank(rollId, (frames.lastOrNull()?.number ?: 0) + 1) } },
                modifier = Modifier.padding(vertical = 16.dp),
            ) { Text("Add blank frame") }
        }
    }

    if (editingRoll) {
        RollDialog(
            title = "Edit roll",
            initial = r,
            onDismiss = { editingRoll = false },
            onSave = { name, stock, iso, capacity ->
                editingRoll = false
                scope.launch { dao.update(r.copy(name = name, stock = stock, iso = iso, capacity = capacity)) }
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${r.name}?") },
            text = { Text("Its ${frames.size} frames will be deleted too. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onClose()
                    scope.launch { dao.delete(r) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
    editing?.let { frame ->
        FrameDialog(
            frame = frame,
            onDismiss = { editing = null },
            onSave = { note ->
                editing = null
                scope.launch { dao.update(frame.copy(note = note)) }
            },
            onInsertBlank = {
                editing = null
                scope.launch { dao.insertBlank(rollId, frame.number) }
            },
            onDelete = {
                editing = null
                scope.launch { dao.delete(frame) }
            },
        )
    }
}

@Composable
private fun RollDialog(
    title: String,
    initial: Roll?,
    onDismiss: () -> Unit,
    onSave: (name: String, stock: String?, iso: Int?, capacity: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var stock by remember { mutableStateOf(initial?.stock ?: "") }
    var iso by remember { mutableStateOf(initial?.iso?.toString() ?: "") }
    var capacity by remember { mutableStateOf((initial?.capacity ?: 36).toString()) }
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(stock, { stock = it }, label = { Text("Film stock") }, singleLine = true)
                OutlinedTextField(iso, { iso = it.filter(Char::isDigit) }, label = { Text("ISO") }, singleLine = true, keyboardOptions = number)
                OutlinedTextField(capacity, { capacity = it.filter(Char::isDigit) }, label = { Text("Exposures") }, singleLine = true, keyboardOptions = number)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name.trim(), stock.trim().ifEmpty { null }, iso.toIntOrNull(), capacity.toIntOrNull() ?: 36)
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FrameDialog(
    frame: Frame,
    onDismiss: () -> Unit,
    onSave: (note: String?) -> Unit,
    onInsertBlank: () -> Unit,
    onDelete: () -> Unit,
) {
    var note by remember { mutableStateOf(frame.note ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frame ${frame.number}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                frame.takenAt?.let { Text(dateTime(it)) }
                place(frame)?.let { Text(it) }
                OutlinedTextField(note, { note = it }, label = { Text("Note") })
                Text(
                    "Insert a blank frame before this one for a shot the logger missed; delete a frame the " +
                        "logger counted twice. Later frames are renumbered.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onInsertBlank) { Text("Insert blank before") }
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note.trim().ifEmpty { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun describe(roll: Roll) = describe(roll.stock, roll.iso)

private fun describe(roll: RollSummary) = describe(roll.stock, roll.iso)

private fun describe(stock: String?, iso: Int?): String? =
    listOfNotNull(stock, iso?.let { "ISO $it" }).joinToString(" · ").ifEmpty { null }

/** Where a frame was taken, e.g. "41.87810, -87.62980 ±6 m · approximate · 4 s". */
private fun place(frame: Frame): String? = listOfNotNull(
    if (frame.lat != null && frame.lon != null) "%.5f, %.5f".format(frame.lat, frame.lon) else null,
    frame.accuracyM?.let { "±%.0f m".format(it) },
    if (frame.approximate && frame.takenAt != null) "approximate" else null,
    frame.exposureMs?.let { "%.1f s".format(it / 1000.0) },
).joinToString(" · ").replace(" · ±", " ±").ifEmpty { null }

private fun date(millis: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

private fun dateTime(millis: Long) =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(millis))
