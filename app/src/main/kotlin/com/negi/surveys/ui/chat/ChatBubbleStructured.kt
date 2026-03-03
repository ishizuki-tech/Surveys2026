/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Question UI)
 *  ---------------------------------------------------------------------
 *  File: ChatBubbleStructured.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.negi.surveys.chat.ChatMessage
import com.negi.surveys.chat.ChatRole
import com.negi.surveys.chat.ChatStreamState

@Composable
internal fun ChatBubbleStructured(
    msg: ChatMessage,
    allowFollowUp: Boolean,
    detailsExpanded: Boolean,
    onToggleDetailsExpand: (Boolean) -> Unit
) {
    val isUser = msg.role == ChatRole.USER
    val isModel = msg.role == ChatRole.MODEL
    val isAssistant = msg.role == ChatRole.ASSISTANT

    val hasStreamDetails = !msg.streamText.isNullOrBlank() && msg.streamState != ChatStreamState.NONE
    val isStreamingModelBubble = isModel && (msg.streamState == ChatStreamState.STREAMING)

    val rowAlign = if (isUser) Arrangement.End else Arrangement.Start

    val shape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
    }

    val bubbleColor = when (msg.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primaryContainer
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer
        ChatRole.MODEL -> MaterialTheme.colorScheme.surface
    }

    val textColor = when (msg.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.onSecondaryContainer
        ChatRole.MODEL -> MaterialTheme.colorScheme.onSurface
    }

    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    val expanded = when {
        isStreamingModelBubble -> true
        hasStreamDetails -> (detailsExpanded || !msg.streamCollapsed)
        else -> false
    }

    val isCompactPill =
        (hasStreamDetails && !expanded) ||
                (isModel && !isStreamingModelBubble && !expanded && !msg.streamText.isNullOrBlank())

    val maxWidth = when {
        isCompactPill -> 360.dp
        isModel && !isStreamingModelBubble -> 460.dp
        else -> 520.dp
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = rowAlign
    ) {
        Surface(
            shape = shape,
            tonalElevation = 1.dp,
            color = bubbleColor,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .border(1.dp, outline, shape)
        ) {
            val basePaddingH = if (isCompactPill) 10.dp else 12.dp
            val basePaddingV = if (isCompactPill) 8.dp else 10.dp

            Column(modifier = Modifier.padding(horizontal = basePaddingH, vertical = basePaddingV)) {

                if (!isUser && !isCompactPill) {
                    val header = if (isModel) "MODEL" else "AI"
                    val headerBg = if (isModel) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.0f)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(headerBg)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = header,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                }

                when {
                    isUser -> {
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }

                    isAssistant -> {
                        val a = msg.assistantMessage?.trim().orEmpty()
                        val q = if (allowFollowUp) normalizeFollowUp(msg.followUpQuestion) else null

                        if (a.isNotEmpty()) {
                            Text(
                                text = a,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                        } else if (msg.text.isNotBlank()) {
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                        }

                        if (q != null) {
                            Spacer(Modifier.height(10.dp))

                            val qShape = RoundedCornerShape(14.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(qShape)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        shape = qShape
                                    )
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Question",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = q,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }

                        if (hasStreamDetails) {
                            Spacer(Modifier.height(10.dp))

                            val raw = msg.streamText.orEmpty()
                            val canClick = raw.isNotBlank()
                            val clickMod = if (canClick) {
                                Modifier.clickable { onToggleDetailsExpand(!expanded) }
                            } else {
                                Modifier
                            }

                            StreamDetailsAnimated(
                                expanded = expanded,
                                raw = raw,
                                outline = outline,
                                modifier = Modifier.then(clickMod)
                            )
                        }
                    }

                    isModel -> {
                        val streamRaw = msg.streamText.orEmpty()
                        val ttftActive = isStreamingModelBubble && streamRaw.isBlank()
                        val raw = if (streamRaw.isNotBlank()) streamRaw else msg.text

                        val canClick = !isStreamingModelBubble && raw.isNotBlank()
                        val clickMod = if (canClick) {
                            Modifier.clickable { onToggleDetailsExpand(!expanded) }
                        } else {
                            Modifier
                        }

                        if (isCompactPill && !isStreamingModelBubble) {
                            val pillShape = RoundedCornerShape(12.dp)
                            Row(
                                modifier = Modifier
                                    .then(clickMod)
                                    .clip(pillShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .border(1.dp, outline, pillShape)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Model output",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "(${raw.length} chars)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "Expand",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(clickMod)
                            ) {
                                val blockShape = RoundedCornerShape(14.dp)

                                if (isStreamingModelBubble) {
                                    FancyStreamingModelBlock(
                                        shape = blockShape,
                                        ttftActive = ttftActive,
                                        raw = raw,
                                        onOutline = outline
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(blockShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .border(1.dp, outline, blockShape)
                                            .padding(horizontal = 10.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = raw.ifBlank { "Validating…" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                Text(
                                    text = when {
                                        ttftActive -> "Warming up…"
                                        isStreamingModelBubble -> "Streaming…"
                                        expanded -> "Tap to collapse"
                                        else -> "Tap to expand"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StreamDetailsAnimated(
    expanded: Boolean,
    raw: String,
    outline: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = 90, easing = LinearEasing))
        },
        label = "streamDetails"
    ) { isExpanded ->
        if (!isExpanded) {
            val pillShape = RoundedCornerShape(12.dp)
            Row(
                modifier = modifier
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .border(1.dp, outline, pillShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model output",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "(${raw.length} chars)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Expand",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val blockShape = RoundedCornerShape(14.dp)
            Column(modifier = modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(blockShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(1.dp, outline, blockShape)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = raw,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Tap to collapse",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}