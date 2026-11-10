/*
 * Copyright (C) 2026 HushhBoard
 * Licensed under the Apache License, Version 2.0.
 */

package dev.patrickgold.florisboard.scanner

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Google Lens-style text picking on a frozen frame: every recognised line is outlined, tap or drag
 * across lines to choose them, then insert or copy just those.
 *
 * [lines] boxes are in [bitmap] pixel coordinates; the bitmap is shown fitted to the screen.
 */
@Composable
fun TextPicker(
    bitmap: Bitmap,
    lines: List<ScanLine>,
    preselected: Set<Int>,
    canInsert: Boolean,
    onInsert: (String) -> Unit,
    onMore: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val selected = remember(lines) { mutableStateListOf<Int>().apply { addAll(preselected.sorted()) } }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current

    fun selectedText(): String = selected.sorted().joinToString("\n") { lines[it].text }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewW = with(density) { maxWidth.toPx() }
            val viewH = with(density) { maxHeight.toPx() }
            val scale = minOf(viewW / bitmap.width, viewH / bitmap.height)
            val offX = (viewW - bitmap.width * scale) / 2f
            val offY = (viewH - bitmap.height * scale) / 2f
            fun toView(r: RectF) = RectF(r.left * scale + offX, r.top * scale + offY, r.right * scale + offX, r.bottom * scale + offY)
            val viewBoxes = remember(lines, scale, offX, offY) { lines.map { toView(it.box) } }
            val slop = with(density) { 10.dp.toPx() }
            fun hit(o: Offset): Int = viewBoxes.indexOfFirst { b ->
                o.x >= b.left - slop && o.x <= b.right + slop && o.y >= b.top - slop && o.y <= b.bottom + slop
            }

            Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(viewBoxes) {
                        detectTapGestures { o ->
                            val i = hit(o)
                            if (i >= 0) { if (i in selected) selected.remove(i) else selected.add(i) }
                        }
                    }
                    .pointerInput(viewBoxes) {
                        // Drag across lines to add them, like highlighting with a finger.
                        detectDragGestures { change, _ ->
                            val i = hit(change.position)
                            if (i >= 0 && i !in selected) selected.add(i)
                        }
                    },
            ) {
                // Dim everything, then lift the lines.
                drawRect(Color.Black.copy(alpha = 0.45f))
                for ((i, b) in viewBoxes.withIndex()) {
                    val on = i in selected
                    val pad = 4.dp.toPx()
                    val topLeft = Offset(b.left - pad, b.top - pad)
                    val size = Size(b.width() + pad * 2, b.height() + pad * 2)
                    drawRoundRect(
                        color = if (on) ScanColors.Link.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f),
                        topLeft = topLeft, size = size, cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                    drawRoundRect(
                        color = if (on) ScanColors.Link else Color.White.copy(alpha = 0.55f),
                        topLeft = topLeft, size = size, cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(width = if (on) 2.dp.toPx() else 1.dp.toPx()),
                    )
                }
            }
        }

        // Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Icons.Rounded.Close, "Back to camera") { onClose() }
            Spacer(Modifier.weight(1f))
            Text(
                if (lines.isEmpty()) "No text found" else "Tap or swipe the lines you want",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            RoundIcon(Icons.Rounded.SelectAll, "Select all") {
                if (selected.size == lines.size) selected.clear() else { selected.clear(); selected.addAll(lines.indices) }
            }
        }

        // Bottom panel
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(ScanColors.Sheet)
                .navigationBarsPadding().padding(20.dp),
        ) {
            val text = selectedText()
            Text(
                if (text.isEmpty()) "Nothing selected yet" else text,
                color = if (text.isEmpty()) ScanColors.Muted else ScanColors.Text,
                fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val enabled = text.isNotEmpty()
                PickerButton(
                    if (canInsert) "Insert" else "Copy", if (canInsert) Icons.Rounded.Keyboard else Icons.Rounded.ContentCopy,
                    primary = true, enabled = enabled, modifier = Modifier.weight(1.4f),
                ) {
                    if (canInsert) onInsert(text) else { ScanActions.copy(context, text); onClose() }
                }
                if (canInsert) {
                    PickerButton("Copy", Icons.Rounded.ContentCopy, enabled = enabled, modifier = Modifier.weight(1f)) {
                        ScanActions.copy(context, text)
                    }
                }
                PickerButton("More", Icons.Rounded.MoreHoriz, enabled = enabled, modifier = Modifier.weight(1f)) { onMore(text) }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun PickerButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> ScanColors.Card.copy(alpha = 0.6f)
        primary -> ScanColors.Link
        else -> ScanColors.Card
    }
    val fg = if (primary && enabled) Color(0xFF0B0D10) else if (enabled) ScanColors.Text else ScanColors.Muted
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(16.dp)).background(bg).clickable(enabled = enabled) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
