package com.labserver.lablink.ui

import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labserver.lablink.R
import com.labserver.lablink.data.Attachment
import com.labserver.lablink.data.AttachmentKind
import com.labserver.lablink.data.LibraryFilter
import com.labserver.lablink.data.LibraryItem
import com.labserver.lablink.data.filtered
import com.labserver.lablink.data.humanSize
import com.labserver.lablink.labApp

@Composable
private fun FilterRow(filter: LibraryFilter, onChange: (LibraryFilter) -> Unit, modifier: Modifier = Modifier) {
    SegmentedPills(
        listOf(stringResource(R.string.library_all), stringResource(R.string.library_images), stringResource(R.string.library_files)),
        LibraryFilter.entries.indexOf(filter), { onChange(LibraryFilter.entries[it]) }, modifier,
    )
}

/** One square of the library: a picture, or a file with its name; with a tick when picked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(item: LibraryItem, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val a = item.attachment
    // a picked tile shrinks a little, on a spring, as if pressed in
    val pressed by animateFloatAsState(if (selected) 0.9f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "tile")
    Box(Modifier.aspectRatio(1f).scale(pressed).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        if (a.kind == AttachmentKind.Image) {
            Thumb(a, 200.dp, Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(if (a.kind == AttachmentKind.Text) Icons.Rounded.Description else Icons.Rounded.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Column {
                    Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(humanSize(a.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AnimatedVisibility(selecting, Modifier.align(Alignment.TopStart).padding(6.dp), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    .border(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp)) }
        }
    }
}

@Composable
private fun LibraryGrid(
    items: List<LibraryItem>, selected: Set<String>, selecting: Boolean,
    onClick: (LibraryItem) -> Unit, onLongClick: (LibraryItem) -> Unit, modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        EmptyHint(androidx.compose.material.icons.Icons.Rounded.PhotoLibrary, stringResource(R.string.library_empty), modifier)
        return
    }
    LazyVerticalGrid(
        GridCells.Fixed(3), modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.attachment.local }) { item ->
            LibraryTile(item, item.attachment.local in selected, selecting, { onClick(item) }, { onLongClick(item) })
        }
    }
}

/** Everything that was ever sent, newest first. Long-press (or tap a file) to pick; then send it again or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onSendAgain: (List<String>) -> Unit) {
    val app = LocalContext.current.labApp
    val ui by app.chat.ui.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val shown = ui.library.filtered(filter)
    val selecting = picked.isNotEmpty()
    fun toggle(item: LibraryItem) { val l = item.attachment.local; picked = if (l in picked) picked - l else picked + l }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            if (selecting) stringResource(R.string.library_selected, picked.size) else stringResource(R.string.library_title),
            onBack = { if (selecting) picked = emptySet() else onBack() },
        ) {
            if (selecting) {
                GlassIconButton(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.library_send_again), { onSendAgain(picked.toList()); picked = emptySet() })
                GlassIconButton(Icons.Rounded.Delete, stringResource(R.string.library_delete), { confirmDelete = true })
            }
        }
        if (!selecting && ui.library.isNotEmpty()) Text(
            stringResource(R.string.library_total, ui.library.size, humanSize(ui.library.sumOf { it.attachment.size })),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        FilterRow(filter, { filter = it; picked = emptySet() }, Modifier.padding(horizontal = 16.dp))
        LibraryGrid(
            shown, picked, selecting,
            onClick = { item -> if (selecting || item.attachment.kind != AttachmentKind.Image) toggle(item) else viewing = item.attachment },
            onLongClick = ::toggle, modifier = Modifier.fillMaxSize(),
        )
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
    if (confirmDelete) {
        LabAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.library_delete)) },
            text = { Text(stringResource(R.string.library_delete_confirm, picked.size)) },
            confirmButton = { PillButton(onClick = { app.chat.deleteFromLibrary(picked); picked = emptySet(); confirmDelete = false }) { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { PillButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** The library as a sheet over the chat: tap what to attach to the message being written. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryPickerSheet(onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.labApp
    val ui by app.chat.ui.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    LabSheet(onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(stringResource(R.string.attach_library), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
            FilterRow(filter, { filter = it }, Modifier.padding(horizontal = 16.dp))
            LibraryGrid(
                ui.library.filtered(filter), picked, true,
                onClick = { item -> val l = item.attachment.local; picked = if (l in picked) picked - l else picked + l },
                onLongClick = { }, modifier = Modifier.height(360.dp),
            )
            PillButton(
                onClick = { onAdd(picked.toList()); onDismiss() }, enabled = picked.isNotEmpty(), kind = PillKind.Primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
            ) { Text(stringResource(R.string.library_pick, picked.size)) }
        }
    }
}
