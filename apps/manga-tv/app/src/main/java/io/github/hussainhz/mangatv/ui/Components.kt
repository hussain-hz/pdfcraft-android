package io.github.hussainhz.mangatv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

private val CardShape = RoundedCornerShape(10.dp)

private fun focusBorder() = Border(BorderStroke(3.dp, Accent), shape = CardShape)

/** Portrait cover tile for series and chapters. */
@Composable
fun PosterCard(
    title: String,
    cover: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    width: Dp = 150.dp,
) {
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = CardDefaults.shape(CardShape),
            border = CardDefaults.border(focusedBorder = focusBorder()),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            colors = CardDefaults.colors(containerColor = SurfaceColor),
        ) {
            Box(Modifier.fillMaxSize()) {
                Placeholder(title)
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (progress != null) {
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Accent))
                    }
                }
            }
        }
        Text(
            title,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Placeholder(title: String) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF2B2B36), Color(0xFF18181F)))
        ).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Wide tile for library sources and actions on the home screen. */
@Composable
fun SourceCard(
    glyph: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(250.dp).height(120.dp),
        shape = CardDefaults.shape(CardShape),
        border = CardDefaults.border(focusedBorder = focusBorder()),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        colors = CardDefaults.colors(containerColor = SurfaceColor, focusedContainerColor = Color(0xFF2E2A2A)),
    ) {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).background(Accent.copy(alpha = 0.18f), RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(glyph, fontSize = 24.sp, color = Accent) }
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun CenteredMessage(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val focus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp).focusRequester(focus)) { Text(actionLabel) }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

/** Remote-friendly confirmation dialog. */
@Composable
fun ConfirmDialog(title: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, cancelLabel: String) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(SurfaceColor, RoundedCornerShape(16.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text(cancelLabel) }
                Button(onClick = onConfirm) { Text(confirmLabel) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
