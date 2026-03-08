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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.negi.surveys.chat.ChatModels
import com.negi.surveys.chat.ChatModels.ModelPhase
import com.negi.surveys.ui.chat.ChatReviewLogBuilders.normalizeFollowUp

/**
 * Chat bubble UI for user / assistant / model messages.
 *
 * Design:
 * - USER bubbles show submitted text.
 * - ASSISTANT bubbles show only user-facing text.
 * - MODEL bubbles show step-1 / step-2 raw JSON or active streaming output.
 */
@Composable
internal fun ChatBubbleStructured(
    msg: ChatModels.ChatMessage,
    allowFollowUp: Boolean,
    detailsExpanded: Boolean,
    onToggleDetailsExpand: (Boolean) -> Unit,
) {
    val isUser = msg.role == ChatModels.ChatRole.USER
    val isModel = msg.role == ChatModels.ChatRole.MODEL
    val isAssistant = msg.role == ChatModels.ChatRole.ASSISTANT

    val rowAlign = if (isUser) Arrangement.End else Arrangement.Start

    val shape = if (isUser) {
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomEnd = 18.dp,
            bottomStart = 8.dp,
        )
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomEnd = 8.dp,
            bottomStart = 18.dp,
        )
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

    val maxWidth = when {
        isModel && msg.streamState != ChatModels.ChatStreamState.STREAMING -> 460.dp
        else -> 520.dp
    }

    val clickEnabled = when {
        isUser -> false
        isModel -> false
        isAssistant -> false
        else -> false
    }

    val interaction = remember { MutableInteractionSource() }
    val pressScale by animateFloatAsState(
        targetValue = if (clickEnabled && detailsExpanded) 0.995f else 1f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "pressScale",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = rowAlign,
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
                            indication = null,
                        ) { onToggleDetailsExpand(!detailsExpanded) }
                    } else {
                        Modifier
                    }
                ),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = 12.dp,
                    vertical = 10.dp,
                ),
            ) {
                if (!isUser) {
                    val (label, chipBg, chipFg, dotColor) = when (msg.role) {
                        ChatModels.ChatRole.MODEL -> ChatBubbleUi.Quad(
                            "MODEL",
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.tertiary,
                        )
                        ChatModels.ChatRole.ASSISTANT -> ChatBubbleUi.Quad(
                            "AI",
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.secondary,
                        )
                        ChatModels.ChatRole.USER -> ChatBubbleUi.Quad(
                            "USER",
                            Color.Transparent,
                            textColor,
                            Color.Transparent,
                        )
                    }

                    ChatBubbleUi.RoleChip(
                        label = label,
                        bg = chipBg,
                        fg = chipFg,
                        dot = dotColor,
                        trailing = when {
                            isModel && msg.streamState == ChatModels.ChatStreamState.STREAMING -> "Streaming"
                            isModel -> "Stored"
                            else -> null
                        },
                    )

                    Spacer(Modifier.height(8.dp))
                }

                when {
                    isUser -> {
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                        )
                    }

                    isAssistant -> {
                        val assistantText = msg.assistantMessage
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }

                        val followUpText = when {
                            !allowFollowUp -> null
                            msg.followUpQuestion.isNullOrBlank() -> null
                            else -> normalizeFollowUp(msg.followUpQuestion)
                        }

                        if (assistantText != null) {
                            Text(
                                text = assistantText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor,
                            )
                        }

                        AnimatedVisibility(
                            visible = (followUpText != null),
                            enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.98f),
                            exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.99f),
                            label = "followUpCard",
                        ) {
                            if (followUpText != null) {
                                if (assistantText != null) {
                                    Spacer(Modifier.height(10.dp))
                                }
                                ChatBubbleUi.FollowUpCard(question = followUpText)
                            }
                        }
                    }

                    isModel -> {
                        val raw = (msg.streamText?.takeIf { it.isNotBlank() } ?: msg.text).ifBlank {
                            "Validating…"
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            SectionLabel(modelPhaseLabel(msg.modelPhase, msg.streamState))
                            Spacer(Modifier.height(6.dp))

                            ChatBubbleUi.CodeLikeScrollableBlock(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                outline = outline,
                                text = raw,
                                maxHeight = 300.dp,
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                text = modelStateHint(msg.streamState),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun modelPhaseLabel(
    phase: ModelPhase?,
    streamState: ChatModels.ChatStreamState,
): String {
    return when (phase) {
        ModelPhase.STEP1_EVAL -> {
            if (streamState == ChatModels.ChatStreamState.STREAMING) {
                "Step 1 · Evaluating"
            } else {
                "Step 1 · Model JSON"
            }
        }
        ModelPhase.STEP2_FOLLOW_UP -> {
            if (streamState == ChatModels.ChatStreamState.STREAMING) {
                "Step 2 · Generating"
            } else {
                "Step 2 · Model JSON"
            }
        }
        null -> {
            if (streamState == ChatModels.ChatStreamState.STREAMING) {
                "Streaming"
            } else {
                "Model"
            }
        }
    }
}

private fun modelStateHint(streamState: ChatModels.ChatStreamState): String {
    return when (streamState) {
        ChatModels.ChatStreamState.STREAMING -> "Streaming…"
        ChatModels.ChatStreamState.ENDED -> "Done"
        ChatModels.ChatStreamState.ERROR -> "Stopped"
        ChatModels.ChatStreamState.NONE -> "Stored"
    }
}
