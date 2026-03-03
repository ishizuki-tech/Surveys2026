/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Question UI)
 *  ---------------------------------------------------------------------
 *  File: FancyStreamingModel.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Immutable
internal data class StreamAnimPhases(
    val borderPulse: Float,
    val bgPulse: Float,
    val shimmerPhase: Float,
    val headerDotAlpha: Float,
    val cursorAlpha: Float,
    val miniBarPhase: Float,
    val ttftDot1: Float,
    val ttftDot2: Float,
    val ttftDot3: Float,
    val ttftShimmerPhase: Float,
)

@Composable
internal fun rememberStreamAnimPhases(): StreamAnimPhases {
    val t = rememberInfiniteTransition(label = "streamAnim")

    val borderPulse by t.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "borderPulse"
    )

    val bgPulse by t.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgPulse"
    )

    val shimmerPhase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerPhase"
    )

    val headerDotAlpha by t.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "headerDotAlpha"
    )

    val cursorAlpha by t.animateFloat(
        initialValue = 0.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    val miniBarPhase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "miniBarPhase"
    )

    val ttftDot1 by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ttftDot1"
    )

    val ttftDot2 by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, delayMillis = 140, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ttftDot2"
    )

    val ttftDot3 by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, delayMillis = 280, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ttftDot3"
    )

    val ttftShimmerPhase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ttftShimmerPhase"
    )

    return StreamAnimPhases(
        borderPulse = borderPulse,
        bgPulse = bgPulse,
        shimmerPhase = shimmerPhase,
        headerDotAlpha = headerDotAlpha,
        cursorAlpha = cursorAlpha,
        miniBarPhase = miniBarPhase,
        ttftDot1 = ttftDot1,
        ttftDot2 = ttftDot2,
        ttftDot3 = ttftDot3,
        ttftShimmerPhase = ttftShimmerPhase,
    )
}

@Composable
internal fun FancyStreamingModelBlock(
    shape: RoundedCornerShape,
    ttftActive: Boolean,
    raw: String,
    onOutline: Color
) {
    val phases = rememberStreamAnimPhases()

    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = phases.borderPulse * 0.9f)
    val baseBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f + phases.bgPulse)

    val brush = Brush.linearGradient(
        colors = listOf(
            baseBg,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
            baseBg
        ),
        start = Offset(x = -400f + phases.shimmerPhase * 800f, y = 0f),
        end = Offset(x = 400f + phases.shimmerPhase * 800f, y = 220f)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(brush)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Column {
            FancyStreamingHeader(ttftActive = ttftActive, outline = onOutline, dotAlpha = phases.headerDotAlpha)

            Spacer(Modifier.height(10.dp))

            if (ttftActive) {
                TtftIndicator(
                    dot1 = phases.ttftDot1,
                    dot2 = phases.ttftDot2,
                    dot3 = phases.ttftDot3,
                    shimmerPhase = phases.ttftShimmerPhase
                )
            } else {
                StreamingMonospaceTextWithCursor(
                    raw = raw.ifBlank { "Validating…" },
                    cursorAlpha = phases.cursorAlpha
                )
            }

            Spacer(Modifier.height(12.dp))
            StreamingMiniShimmerBar(phase = phases.miniBarPhase)
        }
    }
}

@Composable
internal fun StreamingMonospaceTextWithCursor(
    raw: String,
    cursorAlpha: Float,
    modifier: Modifier = Modifier
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val textToRender = raw

    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = baseColor.copy(alpha = cursorAlpha)

    Text(
        text = textToRender,
        style = MaterialTheme.typography.bodySmall,
        color = baseColor,
        fontFamily = FontFamily.Monospace,
        modifier = modifier.drawWithContent {
            drawContent()
            val lr = layoutResult ?: return@drawWithContent

            val caret = lr.getCursorRect(textToRender.length.coerceAtLeast(0))
            val w = 2.dp.toPx().coerceAtLeast(1f)
            val h = caret.height.coerceAtLeast(10f)

            drawRoundRect(
                color = cursorColor,
                topLeft = Offset(caret.left, caret.top),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f)
            )
        },
        onTextLayout = { layoutResult = it }
    )
}

@Composable
internal fun FancyStreamingHeader(
    ttftActive: Boolean,
    outline: Color,
    dotAlpha: Float
) {
    val chipShape = RoundedCornerShape(999.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(chipShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                .border(1.dp, outline.copy(alpha = 0.55f), chipShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha))
                    .width(7.dp)
                    .height(7.dp)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = if (ttftActive) "WARMUP" else "STREAM",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .clip(chipShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f))
                .border(1.dp, outline.copy(alpha = 0.45f), chipShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MODEL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
internal fun StreamingMiniShimmerBar(phase: Float) {
    val barShape = RoundedCornerShape(999.dp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(barShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f))
    ) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        val x = (phase * 2f - 1f) * wPx

        val brush = Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            ),
            start = Offset(x - wPx, 0f),
            end = Offset(x + wPx, 0f)
        )

        Box(modifier = Modifier.fillMaxSize().background(brush))
    }
}

@Composable
internal fun TtftIndicator(
    dot1: Float,
    dot2: Float,
    dot3: Float,
    shimmerPhase: Float,
    modifier: Modifier = Modifier
) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Thinking",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TtftDot(alpha = dot1, color = base)
                Spacer(Modifier.width(4.dp))
                TtftDot(alpha = dot2, color = base)
                Spacer(Modifier.width(4.dp))
                TtftDot(alpha = dot3, color = base)
            }
        }

        Spacer(Modifier.height(10.dp))

        val barShape = RoundedCornerShape(999.dp)

        @Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(barShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
        ) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
            val x = (shimmerPhase * 2f - 1f) * wPx

            val brush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                ),
                start = Offset(x - wPx, 0f),
                end = Offset(x + wPx, 0f)
            )

            Box(modifier = Modifier.fillMaxSize().background(brush))
        }
    }
}

@Composable
internal fun TtftDot(alpha: Float, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = alpha))
            .width(6.dp)
            .height(6.dp)
    )
}