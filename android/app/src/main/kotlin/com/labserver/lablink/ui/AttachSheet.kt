package com.labserver.lablink.ui

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.labserver.lablink.R

/** Where the attachment comes from: the camera, the photo picker or any file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(onCamera: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit, onLibrary: () -> Unit, onDismiss: () -> Unit) {
    LabSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Text(stringResource(R.string.attach_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))
            // four big tiles, each in its own colour, as in the ChatGPT and Telegram attach menus
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Option(Icons.Rounded.CameraAlt, stringResource(R.string.attach_camera), Color(0xFFFF7A59), onCamera)
                Option(Icons.Rounded.Image, stringResource(R.string.attach_photos), Color(0xFF3B9BE8), onPhotos)
                Option(Icons.Rounded.AttachFile, stringResource(R.string.attach_files), Color(0xFF2FBF8F), onFiles)
                Option(Icons.Rounded.PhotoLibrary, stringResource(R.string.attach_library), Color(0xFF8E5CF6), onLibrary)
            }
        }
    }
}

@Composable
private fun Option(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).pressable(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = color, spotColor = color).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(color, neighbour(color, 22f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color.White) }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
    }
}
