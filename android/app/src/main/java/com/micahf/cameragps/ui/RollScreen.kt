package com.micahf.cameragps.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micahf.cameragps.Export
import com.micahf.cameragps.Places
import com.micahf.cameragps.db.FilmDao
import com.micahf.cameragps.db.Frame
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One roll's frames, with editing and export. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RollScreen(dao: FilmDao, rollId: Long, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val roll by dao.roll(rollId).collectAsStateWithLifecycle(null)
    val frames by dao.frames(rollId).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var editingRoll by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Frame?>(null) }
    LaunchedEffect(rollId) { nameMissingPlaces(context, dao, rollId) }
    val r = roll ?: return
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val days = remember(frames) { byDay(frames) }

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { Export.share(context, r, frames) }, enabled = frames.isNotEmpty()) {
                        Icon(Icons.Outlined.Share, "Export CSV")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit roll") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = {
                                menu = false
                                editingRoll = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete roll") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = {
                                menu = false
                                deleting = true
                            },
                        )
                    }
                },
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                    stockLine(r.stock, r.iso)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    val loaded = shortDate(context, r.loadedAt)
                    Text(
                        listOf(
                            r.finishedAt?.let { "$loaded – ${shortDate(context, it)}" } ?: "Loaded $loaded · in the camera",
                            "${frames.size} of ${r.capacity} frames",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (frames.isEmpty()) {
                item {
                    Text(
                        "No frames yet. They appear here as the shutter logger records them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            days.forEach { (date, dayFrames) ->
                // Day headers only help once a roll spans more than one day.
                if (days.size > 1 && date != null) {
                    stickyHeader(key = "day-$date") {
                        Surface(Modifier.fillMaxWidth()) {
                            SectionHeader(day(context, dayFrames.first { it.takenAt != null }.takenAt!!))
                        }
                    }
                }
                items(dayFrames, key = { it.id }) { frame ->
                    FrameRow(frame, Modifier.clickable { selected = frame })
                }
            }
            item {
                TextButton(
                    onClick = { scope.launch { dao.insertBlank(rollId, (frames.lastOrNull()?.number ?: 0) + 1) } },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add blank frame")
                }
            }
        }
    }

    if (editingRoll) {
        RollSheet(
            dao = dao,
            initial = r,
            replacing = null,
            onDismiss = { editingRoll = false },
            onSave = {
                editingRoll = false
                scope.launch { dao.editRoll(it.id, it.name, it.stock, it.iso, it.capacity) }
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
    selected?.let { frame ->
        FrameSheet(
            frame = frame,
            onDismiss = { note ->
                selected = null
                if (note != frame.note) scope.launch { dao.setNote(frame.id, note) }
            },
            onInsertBlank = {
                selected = null
                scope.launch { dao.insertBlank(rollId, frame.number) }
            },
            onDelete = {
                selected = null
                scope.launch { dao.delete(frame) }
            },
        )
    }
}

/**
 * Looks up place names for frames stored without one: taken before place
 * names existed, or while the geocoder was unreachable.
 */
private suspend fun nameMissingPlaces(context: Context, dao: FilmDao, rollId: Long) {
    dao.framesNow(rollId)
        .filter { it.place == null && it.lat != null && it.lon != null }
        .groupBy { it.lat!! to it.lon!! }
        .forEach { (at, frames) ->
            Places.name(context, at.first, at.second)?.let { dao.setPlace(frames.map { it.id }, it) }
        }
}

/**
 * Frames grouped by the day they were taken, in order. Blank frames go with
 * the frame before them.
 */
private fun byDay(frames: List<Frame>): List<Pair<LocalDate?, List<Frame>>> {
    val zone = ZoneId.systemDefault()
    val groups = mutableListOf<Pair<LocalDate?, MutableList<Frame>>>()
    for (frame in frames) {
        val date = frame.takenAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val last = groups.lastOrNull()
        when {
            last == null -> groups += date to mutableListOf(frame)
            date == null || date == last.first -> last.second += frame
            last.first == null -> groups[groups.lastIndex] = date to last.second.apply { add(frame) }
            else -> groups += date to mutableListOf(frame)
        }
    }
    return groups
}

@Composable
private fun FrameRow(frame: Frame, modifier: Modifier) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier = modifier,
        leadingContent = {
            Text(
                "${frame.number}",
                style = MaterialTheme.typography.titleMedium.tabular(),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(28.dp),
            )
        },
        headlineContent = {
            val taken = frame.takenAt
            if (taken == null) Text("Blank", color = muted) else Text(time(context, taken))
        },
        supportingContent = where(frame)?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                frame.exposureMs?.let {
                    Icon(Icons.Outlined.Timer, "Long exposure", Modifier.size(18.dp), tint = muted)
                    Text(seconds(it), style = MaterialTheme.typography.labelMedium, color = muted)
                }
                if (frame.approximate && frame.takenAt != null) {
                    Icon(Icons.Outlined.LocationSearching, "Approximate location", Modifier.size(18.dp), tint = muted)
                }
                if (frame.note != null) Icon(Icons.Outlined.EditNote, "Has a note", Modifier.size(18.dp), tint = muted)
            }
        },
    )
}

/** The place name, or coordinates if the place couldn't be looked up. */
private fun where(frame: Frame): String? = frame.place
    ?: if (frame.lat != null && frame.lon != null) coordinates(frame.lat, frame.lon) else null

private fun seconds(ms: Long) = "%.1f s".format(ms / 1000.0)

/** Details for one frame. [onDismiss] gets the note as edited. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrameSheet(frame: Frame, onDismiss: (note: String?) -> Unit, onInsertBlank: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var note by remember { mutableStateOf(frame.note ?: "") }
    val edited = note.trim().ifEmpty { null }
    ModalBottomSheet(
        onDismissRequest = { onDismiss(edited) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Frame ${frame.number}", style = MaterialTheme.typography.headlineSmall)
            Text(
                frame.takenAt?.let { dayTime(context, it) } ?: "Blank frame, added by hand",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            frame.exposureMs?.let {
                Text("Exposure ${seconds(it)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (frame.lat != null && frame.lon != null) {
                Spacer(Modifier.height(12.dp))
                frame.place?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Text(
                    listOfNotNull(coordinates(frame.lat, frame.lon), frame.accuracyM?.let { "±%.0f m".format(it) })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (frame.approximate) {
                    Text(
                        "Approximate: the location was found a while after the shot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { openMap(context, frame.lat, frame.lon, "Frame ${frame.number}") }) {
                    Icon(Icons.Outlined.Map, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open in Maps")
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(note, { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text(
                "If the logger missed a shot, insert a blank before this frame; if it counted one twice, delete it. " +
                    "Later frames are renumbered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onInsertBlank) { Text("Insert blank before") }
                TextButton(onClick = onDelete) { Text("Delete frame", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun openMap(context: Context, lat: Double, lon: Double, label: String) {
    val uri = Uri.parse("geo:0,0?q=$lat,$lon(${Uri.encode(label)})")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        // No maps app; nothing useful to do.
    }
}
