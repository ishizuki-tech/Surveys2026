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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.negi.surveys.chat.ChatModels

/**
 * Chat bubble UI for user/assistant/model messages.
 *
 * IMPORTANT:
 * - `assistantMessage` is no longer available; always render the main body using `msg.text`.
 * - Follow-up question is extracted from raw model output (best-effort JSON parsing).
 * - Never log raw user/model text here.
 */
@Composable
internal fun ChatBubbleStructured(
    msg: ChatModels.ChatMessage,
    allowFollowUp: Boolean,
    detailsExpanded: Boolean,
    onToggleDetailsExpand: (Boolean) -> Unit
) {
    // -----------------------------------------------------------------
    // Role flags
    // -----------------------------------------------------------------
    val isUser = msg.role == ChatModels.ChatRole.USER
    val isModel = msg.role == ChatModels.ChatRole.MODEL

    // -----------------------------------------------------------------
    // Eval / Verdict sources (best-effort, no assistantMessage)
    // -----------------------------------------------------------------
    val eval = remember(msg.id, msg.streamText, msg.text) {
        ChatBubbleParsing.extractEvalResultBestEffort(
            text = msg.streamText?.takeIf { it.isNotBlank() } ?: msg.text
        )
    }

    val verdictSource = remember(msg.id, msg.streamText, msg.text) {
        msg.streamText?.takeIf { it.isNotBlank() } ?: msg.text.takeIf { it.isNotBlank() }
    }

    val verdict = remember(msg.id, verdictSource) {
        ChatBubbleParsing.extractLatestVerdictBestEffort(verdictSource)
    }

    // -----------------------------------------------------------------
    // Stream details (keep [EVAL]/[FOLLOWUP] blocks; remove only [EVAL_RESULT] lines)
    // -----------------------------------------------------------------
    val streamRawOriginal = msg.streamText.orEmpty()
    val streamRawForDisplay = remember(msg.id, msg.streamText) {
        if (streamRawOriginal.isNotBlank()) ChatBubbleParsing.stripEvalResultLines(streamRawOriginal) else ""
    }

    val hasStreamDetails =
        streamRawForDisplay.isNotBlank() || (msg.streamState != ChatModels.ChatStreamState.NONE)

    val isStreamingModelBubble =
        isModel && (msg.streamState == ChatModels.ChatStreamState.STREAMING)

    // -----------------------------------------------------------------
    // Layout & interaction
    // -----------------------------------------------------------------
    val rowAlign = if (isUser) Arrangement.End else Arrangement.Start

    val shape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 8.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 8.dp, bottomStart = 18.dp)
    }

    val bubbleColor = when (msg.role) {
        ChatModels.ChatRole.USER -> MaterialTheme.colorScheme.primaryContainer
        ChatModels.ChatRole.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer
        ChatModels.ChatRole.MODEL -> MaterialTheme.colorScheme.surface
    }

    val textColor = when (msg.role) {
        ChatModels.ChatRole.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        ChatModels.ChatRole.ASSISTANT -> MaterialTheme.colorScheme.onSecondaryContainer
        ChatModels.ChatRole.MODEL -> MaterialTheme.colorScheme.onSurface
    }

    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    val expanded = when {
        isStreamingModelBubble -> true
        hasStreamDetails -> (detailsExpanded || !msg.streamCollapsed)
        else -> false
    }

    val isCompactPill =
        (hasStreamDetails && !expanded) ||
                (isModel && !isStreamingModelBubble && !expanded && streamRawForDisplay.isNotBlank())

    val maxWidth = when {
        isCompactPill -> 360.dp
        isModel && !isStreamingModelBubble -> 460.dp
        else -> 520.dp
    }

    val clickEnabled = when {
        isUser -> false
        isStreamingModelBubble -> false
        isCompactPill -> true
        isModel -> msg.text.isNotBlank() || streamRawForDisplay.isNotBlank()
        else -> hasStreamDetails
    }

    val interaction = remember { MutableInteractionSource() }
    val pressScale by animateFloatAsState(
        targetValue = if (clickEnabled && expanded) 0.995f else 1f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "pressScale"
    )

    // -----------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = rowAlign
    ) {
        Surface(
            shape = shape,
            tonalElevation = if (isUser) 2.dp else 1.dp,
            color = bubbleColor,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .scale(pressScale)
                .border(1.dp, outline, shape)
                .then(
                    if (clickEnabled) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null
                        ) { onToggleDetailsExpand(!expanded) }
                    } else {
                        Modifier
                    }
                )
        ) {
            val basePaddingH = if (isCompactPill) 10.dp else 12.dp
            val basePaddingV = if (isCompactPill) 8.dp else 10.dp

            Column(modifier = Modifier.padding(horizontal = basePaddingH, vertical = basePaddingV)) {

                // ---------------------------------------------------------
                // Header chip (Role)
                // ---------------------------------------------------------
                if (!isUser && !isCompactPill) {
                    val (label, chipBg, chipFg, dotColor) = when (msg.role) {
                        ChatModels.ChatRole.MODEL -> ChatBubbleUi.Quad(
                            "MODEL",
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.tertiary
                        )
                        ChatModels.ChatRole.ASSISTANT -> ChatBubbleUi.Quad(
                            "AI",
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.secondary
                        )
                    }

                    ChatBubbleUi.RoleChip(
                        label = label,
                        bg = chipBg,
                        fg = chipFg,
                        dot = dotColor,
                        trailing = when {
                            isStreamingModelBubble -> "Streaming"
                            hasStreamDetails && expanded -> "Details"
                            hasStreamDetails && !expanded -> "Collapsed"
                            else -> null
                        }
                    )

                    Spacer(Modifier.height(8.dp))
                }

                // ---------------------------------------------------------
                // Eval chip
                // ---------------------------------------------------------
                if (!isUser && eval != null) {
                    ChatBubbleUi.EvalScoreChip(
                        status = eval.status,
                        score = eval.score
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // ---------------------------------------------------------
                // Body
                // ---------------------------------------------------------
                when (msg.role) {
                    ChatModels.ChatRole.USER -> {
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }

                    ChatModels.ChatRole.ASSISTANT -> {
                        // `assistantMessage` is gone; render msg.text only.
                        if (msg.text.isNotBlank()) {
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                        }

                        // Follow-up from verdict JSON only (best-effort), no normalization.
                        val followUp = if (allowFollowUp) verdict?.followUpQuestion else null
                        val q = followUp?.trim()?.takeIf { it.isNotBlank() }

                        AnimatedVisibility(
                            visible = (q != null),
                            enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.98f),
                            exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.99f),
                            label = "followUpCard"
                        ) {
                            if (q != null) {
                                Spacer(Modifier.height(10.dp))
                                ChatBubbleUi.FollowUpCard(question = q)
                            }
                        }

                        if (hasStreamDetails) {
                            Spacer(Modifier.height(10.dp))
                            StreamDetailsAnimated(
                                expanded = expanded,
                                raw = streamRawForDisplay,
                                outline = outline,
                                maxBlockHeight = 260.dp,
                                modifier = Modifier
                            )
                        }
                    }

                    ChatModels.ChatRole.MODEL -> {
                        val ttftActive = isStreamingModelBubble && streamRawOriginal.isBlank()
                        val rawBase = if (streamRawOriginal.isNotBlank()) streamRawOriginal else msg.text
                        val raw = if (rawBase.isNotBlank()) ChatBubbleParsing.stripEvalResultLines(rawBase) else rawBase

                        if (isCompactPill && !isStreamingModelBubble) {
                            ChatBubbleUi.CompactDetailsPill(
                                title = "Model output",
                                meta = "(${raw.length} chars)",
                                action = "Expand"
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                val blockShape = RoundedCornerShape(14.dp)

                                if (isStreamingModelBubble) {
                                    FancyStreamingModelBlock(
                                        shape = blockShape,
                                        msg = msg.copy(streamText = raw),
                                        onOutline = outline
                                    )
                                } else {
                                    ChatBubbleUi.CodeLikeScrollableBlock(
                                        shape = blockShape,
                                        outline = outline,
                                        text = raw.ifBlank { "Validating…" },
                                        maxHeight = 300.dp
                                    )
                                }

                                // Follow-up from verdict JSON (best-effort), no normalization.
                                val modelFollowUp = if (allowFollowUp) verdict?.followUpQuestion else null
                                val q = modelFollowUp?.trim()?.takeIf { it.isNotBlank() }
                                if (q != null) {
                                    Spacer(Modifier.height(10.dp))
                                    ChatBubbleUi.FollowUpCard(question = q)
                                }

                                Spacer(Modifier.height(6.dp))

                                val hint = when {
                                    ttftActive -> "Warming up…"
                                    isStreamingModelBubble -> "Streaming…"
                                    expanded -> "Tap to collapse"
                                    else -> "Tap to expand"
                                }

                                Text(
                                    text = hint,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Animated expand/collapse container for stream details.
 */
@Composable
internal fun StreamDetailsAnimated(
    expanded: Boolean,
    raw: String,
    outline: Color,
    maxBlockHeight: Dp,
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
            ChatBubbleUi.CompactDetailsPill(
                title = "Model output",
                meta = "(${raw.length} chars)",
                action = "Expand"
            )
        } else {
            Column(modifier = modifier.fillMaxWidth()) {
                ChatBubbleUi.CodeLikeScrollableBlock(
                    shape = RoundedCornerShape(14.dp),
                    outline = outline,
                    text = raw,
                    maxHeight = maxBlockHeight
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Tap to collapse",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }
        }
    }
}