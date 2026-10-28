package com.labserver.lablink.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.labserver.lablink.R
import com.labserver.lablink.labApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A picture the bot sent, in the reply: it loads in place (a soft placeholder while it comes), keeps its proportions, and opens
 * full screen on a tap. A failed load says so and offers to try again.
 */
@Composable
fun ReplyImage(src: String, botId: String, alt: String, modifier: Modifier = Modifier) {
    val app = LocalContext.current.labApp
    var bmp by remember(src, botId) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(src, botId) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf(false) }
    LaunchedEffect(src, botId, attempt) {
        failed = false
        bmp = app.media.bitmap(src, botId, 1600)
        failed = bmp == null
    }
    val shape = RoundedCornerShape(18.dp)
    val b = bmp
    when {
        b != null -> {
            val shown = remember(b) { b.asImageBitmap() }
            val appear by animateFloatAsState(1f, label = "picture in")
            Image(
                shown, contentDescription = alt.ifBlank { null }, contentScale = ContentScale.Crop,
                modifier = modifier.fillMaxWidth().heightIn(max = 460.dp)
                    .aspectRatio((b.width.toFloat() / b.height).coerceIn(0.55f, 2.4f))
                    .clip(shape).graphicsLayer { alpha = appear }
                    .clickable { viewing = true },
            )
        }
        failed -> Row(
            modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.img_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.img_retry)) }
        }
        else -> Box(
            modifier.fillMaxWidth().height(220.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp) }
    }
    if (viewing) PictureViewer(src, botId, alt) { viewing = false }
}

/** Full screen, black, pinch or double-tap to zoom, drag when zoomed; save to Photos or share from the top bar. */
@Composable
fun PictureViewer(src: String, botId: String, alt: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.labApp
    val scope = rememberCoroutineScope()
    var big by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(src) { big = app.media.bitmap(src, botId, 2800) }
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    val shownScale by animateFloatAsState(scale, label = "zoom")
    val saved = stringResource(R.string.img_saved)
    val saveFailed = stringResource(R.string.img_save_failed)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            big?.let { b ->
                val shown = remember(b) { b.asImageBitmap() }
                Image(
                    shown, contentDescription = alt.ifBlank { null }, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { if (scale > 1f) { scale = 1f; ox = 0f; oy = 0f } else scale = 2.5f },
                                onTap = { onClose() },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f }
                            }
                        }
                        .graphicsLayer { scaleX = shownScale; scaleY = shownScale; translationX = ox; translationY = oy },
                )
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            Row(
                Modifier.systemBarsPadding().fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                IconButton(onClick = {
                    scope.launch {
                        val f = app.media.file(src, botId)
                        val ok = f != null && withContext(Dispatchers.IO) { saveToPhotos(ctx, f) }
                        Toast.makeText(ctx, if (ok) saved else saveFailed, Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.img_save), tint = Color.White) }
                IconButton(onClick = { scope.launch { app.media.file(src, botId)?.let { sharePicture(ctx, it) } } }) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.img_share), tint = Color.White)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.img_close), tint = Color.White) }
            }
            if (alt.isNotBlank()) Column(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(16.dp)) {
                Text(alt, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun mimeOf(f: File): String {
    val head = f.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
    return when {
        head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "image/png"
        head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
        head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() -> "image/gif"
        head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() -> "image/webp"
        else -> "image/png"
    }
}

/** Copies the picture into Pictures/LabLink, where the gallery finds it. */
private fun saveToPhotos(ctx: Context, f: File): Boolean = try {
    val mime = mimeOf(f)
    val ext = mime.substringAfter('/').replace("jpeg", "jpg")
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "LabLink-${System.currentTimeMillis()}.$ext")
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LabLink")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val r = ctx.contentResolver
    val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no place in the gallery")
    r.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
    if (Build.VERSION.SDK_INT >= 29) r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    true
} catch (e: Exception) {
    android.util.Log.w("LabLink", "could not save picture", e)
    false
}

private fun sharePicture(ctx: Context, f: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType(mimeOf(f)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
