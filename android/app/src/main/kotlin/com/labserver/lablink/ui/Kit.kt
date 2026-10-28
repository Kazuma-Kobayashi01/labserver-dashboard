package com.labserver.lablink.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * The app's own controls, in place of Material's stock ones: pill buttons, a springy switch, soft filled fields, dialogs
 * as floating glass cards, page headers with a round glass back button, and a glass snackbar. Their parameters follow
 * the Material components they replace, so screens swap one for the other without other changes.
 */

/** How strongly a [PillButton] speaks: the main action, a secondary one, a destructive one, or a quiet one. */
enum class PillKind { Primary, Tonal, Danger, Quiet }

/** The kind a [PillButton] takes when the call does not say (a dialog makes its confirm button [PillKind.Primary]). */
val LocalPillKind = compositionLocalOf { PillKind.Quiet }

/** A rounded pill button; it gives a little under the finger. Replaces TextButton, Button and its tonal and outlined kinds. */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: PillKind = LocalPillKind.current,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val brand = Color(0xFF5546E0)
    val (fill, ink) = when (kind) {
        PillKind.Primary -> bubbleBrush(brand, isDark()) to Color.White
        PillKind.Danger -> SolidColor(c.error) to c.onError
        PillKind.Tonal -> SolidColor(accentInk(brand).copy(alpha = 0.14f)) to accentInk(brand)
        PillKind.Quiet -> SolidColor(c.onSurface.copy(alpha = 0.06f)) to c.onSurface
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier.heightIn(min = 40.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable { if (enabled) onClick() }
            .then(if (kind == PillKind.Primary && enabled) Modifier.shadow(8.dp, shape, ambientColor = brand, spotColor = brand) else Modifier)
            .clip(shape).background(fill).padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = ink)) { content() }
        }
    }
}

/** A pill-shaped switch whose knob springs across, stretching a little on the way (as the iOS 26 toggle does). */
@Composable
fun LabSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val on = accentInk(Color(0xFF5546E0)).let { if (isDark()) it else Color(0xFF5546E0) }
    val track by animateColorAsState(if (checked) on else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = 500f), label = "knob")
    val stretch = remember { Animatable(0f) }
    LaunchedEffect(checked) {
        stretch.snapTo(1f)
        stretch.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 300f))
    }
    Box(
        modifier.size(width = 52.dp, height = 30.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(RoundedCornerShape(50)).background(track)
            .pressable { if (enabled) onCheckedChange?.invoke(!checked) }
            .padding(3.dp),
    ) {
        Box(
            Modifier.offset(x = x).size(24.dp)
                .graphicsLayer { scaleX = 1f + 0.25f * stretch.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (checked) 1f else 0f, 0.5f) }
                .shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White),
        )
    }
}

/** A soft filled field: no outline, a tinted rounded well, the label inside. Takes OutlinedTextField's parameters. */
@Composable
fun LabTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    @Suppress("UNUSED_PARAMETER") shape: androidx.compose.ui.graphics.Shape? = null,
) {
    val well = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark()) 0.08f else 0.05f)
    TextField(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
        label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine,
        maxLines = maxLines, minLines = minLines,
        shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = well, unfocusedContainerColor = well, disabledContainerColor = well, errorContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * A dialog as a floating glass card that springs in: an optional title, the text, and the buttons side by side (the
 * confirm button is the main one). Takes AlertDialog's parameters.
 */
@Composable
fun LabAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pop = remember { Animatable(0.88f) }
        LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
        Column(
            modifier.padding(horizontal = 28.dp).widthIn(max = 420.dp).fillMaxWidth().imePadding()
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.88f) / 0.12f).coerceIn(0f, 1f) }
                .glass(RoundedCornerShape(32.dp), elevation = 24.dp, strong = true)
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Box(Modifier.align(Alignment.CenterHorizontally)) { icon() }
            if (title != null) ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)) { title() }
            if (text != null) ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) { text() }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (dismissButton != null) CompositionLocalProvider(LocalPillKind provides PillKind.Quiet) { dismissButton() }
                CompositionLocalProvider(LocalPillKind provides PillKind.Primary) { confirmButton() }
            }
        }
    }
}

/**
 * The top of a page: a round glass back button, then the title, big and bold, with the page's [actions] on the right.
 * Replaces TopAppBar and LargeTopAppBar.
 */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, null, onBack)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp))
    }
}

/** A snackbar as a dark glass pill near the bottom. */
@Composable
fun LabSnackbar(data: SnackbarData) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.shadow(16.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                .background(Color(0xFF1E1E28).copy(alpha = 0.94f))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(data.visuals.message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            data.visuals.actionLabel?.let { label ->
                Spacer(Modifier.size(12.dp))
                Text(label, color = Color(0xFFC5BFFF), fontWeight = FontWeight.Bold, modifier = Modifier.pressable { data.performAction() })
            }
        }
    }
}


/** A segmented control: the options in a soft well, the chosen one on a white (or light) pill that slides across. */
@Composable
fun SegmentedPills(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)).padding(4.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.7f, stiffness = 500f), label = "segment")
        Box(
            Modifier.offset(x = x).size(width = w, height = 36.dp).shadow(4.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                .background(if (isDark()) Color.White.copy(alpha = 0.16f) else Color.White),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                Box(Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(50)).pressable { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(
                        label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** An empty page: a large icon in a glass circle, and a line saying why it is empty and what will appear. */
@Composable
fun EmptyHint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).glass(CircleShape, elevation = 10.dp, strong = true), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(38.dp), tint = accentInk(Color(0xFF5546E0)))
        }
        Text(
            text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 18.dp),
        )
    }
}
