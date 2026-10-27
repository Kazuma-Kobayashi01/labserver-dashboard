package com.labserver.lablink.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.labserver.lablink.R
import com.labserver.lablink.data.AttachProblem
import com.labserver.lablink.data.Attachment
import com.labserver.lablink.data.AttachmentKind
import com.labserver.lablink.data.humanSize
import com.labserver.lablink.labApp
import com.labserver.lablink.net.Pending
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun problemText(p: AttachProblem?): String = when (p) {
    AttachProblem.TooLarge -> stringResource(R.string.attach_problem_large)
    AttachProblem.TooManyImages -> stringResource(R.string.attach_problem_images)
    AttachProblem.NoSandbox -> stringResource(R.string.attach_problem_nosandbox)
    AttachProblem.UploadFailed -> stringResource(R.string.attach_problem_upload)
    else -> stringResource(R.string.attach_problem_unreadable)
}

/** A stored picture, decoded small off the main thread. */
@Composable
private fun rememberThumb(a: Attachment, edge: Int): ImageBitmap? {
    val store = LocalContext.current.labApp.attachments
    val bmp by produceState<ImageBitmap?>(null, a.id, edge) { value = withContext(Dispatchers.IO) { store.thumbnail(a, edge)?.asImageBitmap() } }
    return bmp
}

@Composable
fun Thumb(a: Attachment, size: Dp, modifier: Modifier = Modifier) {
    val bmp = rememberThumb(a, with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() * 2 })
    Box(modifier.size(size).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** A file as a small pill: its icon, name and size. */
@Composable
fun FilePill(a: Attachment, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (a.kind == AttachmentKind.Text) Icons.Rounded.Description else Icons.Rounded.InsertDriveFile, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 8.dp).widthIn(max = 180.dp)) {
                Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(humanSize(a.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What a sent message carried: pictures as thumbnails (tap to see them big), files as pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SentAttachments(list: List<Attachment>, onView: (Attachment) -> Unit, modifier: Modifier = Modifier) {
    if (list.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in list) {
            if (a.kind == AttachmentKind.Image) Thumb(a, if (list.size == 1) 168.dp else 108.dp, Modifier.clickable { onView(a) }) else FilePill(a)
        }
    }
}

/** The files lined up above the text field: thumbnails and pills with a remove button; busy ones spin, failed ones say why. */
@Composable
fun PendingRow(list: List<Pending>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    if (list.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
        items(list.size, key = { list[it].id }) { i ->
            val p = list[i]
            Box {
                when {
                    p.state == Pending.State.Failed -> Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(top = 6.dp, end = 6.dp)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).widthIn(max = 230.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            Text(problemText(p.problem), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 8.dp), maxLines = 3)
                        }
                    }
                    p.attachment?.kind == AttachmentKind.Image -> Thumb(p.attachment, 64.dp, Modifier.padding(top = 6.dp, end = 6.dp))
                    p.attachment != null -> FilePill(p.attachment, Modifier.padding(top = 6.dp, end = 6.dp))
                    else -> Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.padding(top = 6.dp, end = 6.dp).size(64.dp)) {}
                }
                if (p.state == Pending.State.Working) {
                    Box(Modifier.padding(top = 6.dp, end = 6.dp).size(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp) }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.inverseSurface).clickable { onRemove(p.id) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.attach_remove), tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

/** A picture full screen: pinch to zoom, drag to move. */
@Composable
fun ImageViewer(a: Attachment, onClose: () -> Unit) {
    val bmp = rememberThumb(a, 2400)
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bmp != null) {
                Image(
                    bmp, contentDescription = a.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f }
                            }
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = ox; translationY = oy },
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.systemBarsPadding().align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.attach_view_close), tint = Color.White)
            }
        }
    }
}
