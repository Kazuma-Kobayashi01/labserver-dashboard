package com.labserver.lablink.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labserver.lablink.R
import com.labserver.lablink.data.Conversation
import com.labserver.lablink.data.Period
import com.labserver.lablink.data.exportMarkdown
import com.labserver.lablink.data.periodOf
import com.labserver.lablink.labApp
import kotlinx.coroutines.delay

private const val SHOWN = 30

/**
 * The open bot's chats for the drawer, as in ChatGPT: a new-chat row, a search box, then the chats grouped by when they were
 * last used. A long press (or the ⋮ of the open one) gives rename, pin, share and delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationSection(
    conversations: List<Conversation>,
    currentId: String,
    busy: Set<String>,
    botName: String,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val app = LocalContext.current.labApp
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<Set<String>?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    // searching reads every message file, so it waits for a pause in typing
    LaunchedEffect(query, conversations) {
        if (query.isBlank()) { found = null; return@LaunchedEffect }
        delay(250)
        found = app.chat.search(query)
    }
    val visible = found?.let { ids -> conversations.filter { it.id in ids } } ?: conversations
    val shown = if (showAll || found != null) visible else visible.take(SHOWN)

    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(50))
            .background(if (conversations.none { it.id == currentId }) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .combinedClickable(onClick = onNew).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.bot_new_chat), style = MaterialTheme.typography.bodyLarge)
    }
    if (conversations.size > 3 || query.isNotEmpty()) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.chats_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = null) } } } else null,
            singleLine = true,
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    when {
        visible.isEmpty() -> Hint(stringResource(R.string.chats_none_found))
    }
    var last: Period? = null
    for (c in shown) {
        val p = periodOf(c)
        if (p != last) {
            Text(
                periodLabel(p), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            last = p
        }
        ConversationRow(
            c, selected = c.id == currentId, busy = c.id in busy,
            onOpen = { onOpen(c.id) },
            onRename = { renaming = c },
            onPin = { app.chat.setPinned(c.id, !c.pinned) },
            onShare = { shareConversation(app, c, botName) },
            onDelete = { deleting = c },
        )
    }
    if (found == null && visible.size > SHOWN) {
        TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(start = 4.dp)) {
            Text(if (showAll) stringResource(R.string.chats_less) else stringResource(R.string.chats_more, visible.size))
        }
    }

    renaming?.let { c -> RenameDialog(c.title, onDone = { app.chat.renameConversation(c.id, it); renaming = null }, onDismiss = { renaming = null }) }
    deleting?.let { c -> DeleteDialog(c.title, onConfirm = { app.chat.deleteConversation(c.id); deleting = null }, onDismiss = { deleting = null }) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
}

@Composable
fun periodLabel(p: Period): String = stringResource(
    when (p) {
        Period.Pinned -> R.string.period_pinned
        Period.Today -> R.string.period_today
        Period.Yesterday -> R.string.period_yesterday
        Period.Week -> R.string.period_week
        Period.Month -> R.string.period_month
        Period.Older -> R.string.period_older
    },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: Conversation, selected: Boolean, busy: Boolean,
    onOpen: () -> Unit, onRename: () -> Unit, onPin: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(50))
                .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else Color.Transparent)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (c.pinned) {
                Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                c.title.ifBlank { stringResource(R.string.bot_new_chat) },
                style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (busy) Box(Modifier.padding(horizontal = 8.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            if (selected) IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.chat_more), modifier = Modifier.size(18.dp))
            } else Spacer(Modifier.width(12.dp))
        }
        ConversationMenu(menu, c.pinned, onDismiss = { menu = false }, onRename = onRename, onPin = onPin, onShare = onShare, onDelete = onDelete)
    }
}

/** Rename, pin, share, delete: the same four wherever a chat can be acted on. */
@Composable
fun ConversationMenu(
    expanded: Boolean, pinned: Boolean, onDismiss: () -> Unit,
    onRename: () -> Unit, onPin: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(16.dp)) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_rename)) },
            leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (pinned) R.string.chat_unpin else R.string.chat_pin)) },
            leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
            onClick = { onDismiss(); onPin() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_share)) },
            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
            onClick = { onDismiss(); onShare() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

@Composable
fun RenameDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(stringResource(R.string.chat_rename_hint)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun DeleteDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete)) },
        text = { Text(stringResource(R.string.chat_delete_confirm, title.ifBlank { stringResource(R.string.bot_new_chat) })) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Hands a whole chat, as Markdown, to whatever the user picks (notes, mail, a messenger). */
fun shareConversation(app: com.labserver.lablink.LabLinkApp, c: Conversation, botName: String) {
    val text = exportMarkdown(c.title, botName, app.chat.messagesOf(c.id), app.getString(R.string.export_you))
    shareText(app, c.title.ifBlank { botName }, text)
}

fun shareText(ctx: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
