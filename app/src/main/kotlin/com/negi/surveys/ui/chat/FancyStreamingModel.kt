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
import com.negi.surveys.chat.ChatModels
import kotlin.math.max

/**
 * Fancy streaming block for MODEL bubbles, aligned to [ChatModels.ChatMessage].
 *
 * Policy:
 * - TTFT state = STREAMING and streamText is blank.
 * - Render body = streamText if present, else fall back to [ChatModels.ChatMessage.text].
 */
@Composable
internal fun FancyStreamingModelBlock(
    shape: RoundedCornerShape,
    msg: ChatModels.ChatMessage,
    onOutline: Color,
) {
    val phases = FancyStreamingModelParts.rememberStreamAnimPhases()

    val isStreaming = msg.streamState == ChatModels.ChatStreamState.STREAMING
    val streamBody = msg.streamText.orEmpty()
    val ttftActive = isStreaming && streamBody.isBlank()
    val raw = if (streamBody.isNotBlank()) streamBody else msg.text

    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = phases.borderPulse * 0.9f)
    val baseBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f + phases.bgPulse)

    val brush = Brush.linearGradient(
        colors = listOf(
            baseBg,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
            baseBg,
        ),
        start = Offset(x = -400f + phases.shimmerPhase * 800f, y = 0f),
        end = Offset(x = 400f + phases.shimmerPhase * 800f, y = 220f),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(brush)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Column {
            FancyStreamingModelParts.Header(
                ttftActive = ttftActive,
                outline = onOutline,
                dotAlpha = phases.headerDotAlpha,
            )

            Spacer(Modifier.height(10.dp))

            if (ttftActive) {
                FancyStreamingModelParts.TtftIndicator(
                    dot1 = phases.ttftDot1,
                    dot2 = phases.ttftDot2,
                    dot3 = phases.ttftDot3,
                    shimmerPhase = phases.ttftShimmerPhase,
                )
            } else {
                FancyStreamingModelParts.StreamingMonospaceTextWithCursor(
                    raw = raw.ifBlank { "Validating…" },
                    cursorAlpha = phases.cursorAlpha,
                )
            }

            Spacer(Modifier.height(12.dp))
            FancyStreamingModelParts.MiniShimmerBar(phase = phases.miniBarPhase)
        }
    }
}

/**
 * Internal parts for the fancy streaming model UI.
 *
 * Rationale:
 * - Keep the file-level public surface small.
 * - Group private animation and rendering helpers by feature.
 */
private object FancyStreamingModelParts {

    @Immutable
    data class StreamAnimPhases(
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

    /**
     * Builds the shared animation phase bundle for the streaming block.
     */
    @Composable
    fun rememberStreamAnimPhases(): StreamAnimPhases {
        val transition = rememberInfiniteTransition(label = "streamAnim")

        val borderPulse by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "borderPulse",
        )

        val bgPulse by transition.animateFloat(
            initialValue = 0.10f,
            targetValue = 0.22f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bgPulse",
        )

        val shimmerPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1350, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmerPhase",
        )

        val headerDotAlpha by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "headerDotAlpha",
        )

        val cursorAlpha by transition.animateFloat(
            initialValue = 0.0f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cursorAlpha",
        )

        val miniBarPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "miniBarPhase",
        )

        val ttftDot1 by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ttftDot1",
        )

        val ttftDot2 by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 520,
                    delayMillis = 140,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ttftDot2",
        )

        val ttftDot3 by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 520,
                    delayMillis = 280,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ttftDot3",
        )

        val ttftShimmerPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1050, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ttftShimmerPhase",
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

    /**
     * Renders the streaming text body with a blinking cursor.
     */
    @Composable
    fun StreamingMonospaceTextWithCursor(
        raw: String,
        cursorAlpha: Float,
        modifier: Modifier = Modifier,
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
                val layout = layoutResult ?: return@drawWithContent

                val cursorIndex = textToRender.length
                val caret = layout.getCursorRect(cursorIndex)
                val widthPx = max(1f, 2.dp.toPx())
                val heightPx = max(10f, caret.height)

                val x = caret.left.coerceIn(0f, size.width - widthPx)
                val y = caret.top.coerceIn(0f, size.height - heightPx)

                drawRoundRect(
                    color = cursorColor,
                    topLeft = Offset(x, y),
                    size = Size(widthPx, heightPx),
                    cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
                )
            },
            onTextLayout = { layoutResult = it },
        )
    }

    /**
     * Renders the header chips for warmup/streaming state.
     */
    @Composable
    fun Header(
        ttftActive: Boolean,
        outline: Color,
        dotAlpha: Float,
    ) {
        val chipShape = RoundedCornerShape(999.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(chipShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                    .border(1.dp, outline.copy(alpha = 0.55f), chipShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha))
                        .width(7.dp)
                        .height(7.dp),
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = if (ttftActive) "WARMUP" else "STREAM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .clip(chipShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f))
                    .border(1.dp, outline.copy(alpha = 0.45f), chipShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MODEL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    /**
     * Renders the footer shimmer bar.
     */
    @Composable
    fun MiniShimmerBar(phase: Float) {
        val barShape = RoundedCornerShape(999.dp)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(barShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f)),
        ) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
            val x = (phase * 2f - 1f) * widthPx

            val brush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                ),
                start = Offset(x - widthPx, 0f),
                end = Offset(x + widthPx, 0f),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush),
            )
        }
    }

    /**
     * Renders the TTFT placeholder state before streamed text arrives.
     */
    @Composable
    fun TtftIndicator(
        dot1: Float,
        dot2: Float,
        dot3: Float,
        shimmerPhase: Float,
        modifier: Modifier = Modifier,
    ) {
        val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

        Column(modifier = modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Thinking",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
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

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(barShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
            ) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
                val x = (shimmerPhase * 2f - 1f) * widthPx

                val brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                    ),
                    start = Offset(x - widthPx, 0f),
                    end = Offset(x + widthPx, 0f),
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brush),
                )
            }
        }
    }

    /**
     * Renders a single animated TTFT dot.
     */
    @Composable
    fun TtftDot(alpha: Float, color: Color) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(color.copy(alpha = alpha))
                .width(6.dp)
                .height(6.dp),
        )
    }
}