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
import com.negi.surveys.ui.chat.ChatReviewLogBuilders.normalizeFollowUp

/**
 * Chat bubble UI for user / assistant / model messages.
 *
 * Design:
 * - Assistant bubbles render step 1 and step 2 as separate sections.
 * - Raw details for both phases are inspectable via expandable details.
 * - MODEL bubbles remain ephemeral streaming views.
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

    val hasAssistantDetails =
        !msg.step1Raw.isNullOrBlank() || !msg.step2Raw.isNullOrBlank()

    val expanded = when {
        isModel && msg.streamState == ChatModels.ChatStreamState.STREAMING -> true
        hasAssistantDetails -> detailsExpanded
        else -> false
    }

    val isCompactPill = hasAssistantDetails && !expanded && isAssistant

    val maxWidth = when {
        isCompactPill -> 360.dp
        isModel && msg.streamState != ChatModels.ChatStreamState.STREAMING -> 460.dp
        else -> 520.dp
    }

    val clickEnabled = when {
        isUser -> false
        isModel && msg.streamState == ChatModels.ChatStreamState.STREAMING -> false
        isAssistant && hasAssistantDetails -> true
        isModel -> !msg.streamText.isNullOrBlank() || msg.text.isNotBlank()
        else -> false
    }

    val interaction = remember { MutableInteractionSource() }
    val pressScale by animateFloatAsState(
        targetValue = if (clickEnabled && expanded) 0.995f else 1f,
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
                        ) { onToggleDetailsExpand(!expanded) }
                    } else {
                        Modifier
                    }
                ),
        ) {
            val basePaddingH = if (isCompactPill) 10.dp else 12.dp
            val basePaddingV = if (isCompactPill) 8.dp else 10.dp

            Column(
                modifier = Modifier.padding(
                    horizontal = basePaddingH,
                    vertical = basePaddingV,
                ),
            ) {
                if (!isUser && !isCompactPill) {
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
                            Color.Transparent
                        )
                    }

                    ChatBubbleUi.RoleChip(
                        label = label,
                        bg = chipBg,
                        fg = chipFg,
                        dot = dotColor,
                        trailing = when {
                            isModel && msg.streamState == ChatModels.ChatStreamState.STREAMING -> "Streaming"
                            hasAssistantDetails && expanded -> "Details"
                            hasAssistantDetails && !expanded -> "Collapsed"
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
                        val evalStatusForUi = msg.evalStatus?.name
                        val evalScoreForUi = msg.evalScore
                        val evalReasonForUi = msg.evalReason?.trim()?.takeIf { it.isNotBlank() }

                        val assistantText =
                            msg.assistantMessage
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }

                        val followUpText = when {
                            !allowFollowUp -> null
                            msg.followUpQuestion.isNullOrBlank() -> null
                            else -> normalizeFollowUp(msg.followUpQuestion)
                        }

                        val hasStepOneSection = evalScoreForUi != null || !evalReasonForUi.isNullOrBlank()
                        val hasStepTwoSection = assistantText != null || followUpText != null

                        if (hasStepOneSection) {
                            SectionLabel("Step 1 · Evaluation")

                            if (evalScoreForUi != null) {
                                ChatBubbleUi.EvalScoreChip(
                                    status = evalStatusForUi,
                                    score = evalScoreForUi,
                                )
                            }

                            if (!evalReasonForUi.isNullOrBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = evalReasonForUi,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textColor,
                                )
                            }
                        }

                        if (hasStepTwoSection) {
                            if (hasStepOneSection) {
                                Spacer(Modifier.height(12.dp))
                            }

                            SectionLabel("Step 2 · Follow-up")
                            Spacer(Modifier.height(6.dp))

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

                        if (hasAssistantDetails) {
                            Spacer(Modifier.height(10.dp))
                            AssistantStepDetailsAnimated(
                                expanded = expanded,
                                step1Raw = msg.step1Raw.orEmpty(),
                                step2Raw = msg.step2Raw.orEmpty(),
                                outline = outline,
                                maxBlockHeight = 260.dp,
                            )
                        }
                    }

                    isModel -> {
                        val phaseLabel = remember(msg.text) {
                            when {
                                msg.text.startsWith("[Step 1]") -> "Step 1 · Evaluating"
                                msg.text.startsWith("[Step 2]") -> "Step 2 · Generating"
                                else -> "Streaming"
                            }
                        }

                        val raw = msg.streamText?.ifBlank { msg.text } ?: msg.text

                        Column(modifier = Modifier.fillMaxWidth()) {
                            SectionLabel(phaseLabel)
                            Spacer(Modifier.height(6.dp))

                            ChatBubbleUi.CodeLikeScrollableBlock(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                outline = outline,
                                text = raw.ifBlank { "Validating…" },
                                maxHeight = 300.dp,
                            )

                            Spacer(Modifier.height(6.dp))

                            val hint = when (msg.streamState) {
                                ChatModels.ChatStreamState.STREAMING -> "Streaming…"
                                ChatModels.ChatStreamState.ENDED -> "Done"
                                ChatModels.ChatStreamState.ERROR -> "Stopped"
                                ChatModels.ChatStreamState.NONE -> "Idle"
                            }

                            Text(
                                text = hint,
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

/**
 * Expand/collapse details for the final assistant bubble.
 */
@Composable
private fun AssistantStepDetailsAnimated(
    expanded: Boolean,
    step1Raw: String,
    step2Raw: String,
    outline: Color,
    maxBlockHeight: Dp,
) {
    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = 90, easing = LinearEasing))
        },
        label = "assistantStepDetails",
    ) { isExpanded ->
        if (!isExpanded) {
            val totalLen = step1Raw.length + step2Raw.length
            ChatBubbleUi.CompactDetailsPill(
                title = "Model details",
                meta = "(${totalLen} chars)",
                action = "Expand",
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (step1Raw.isNotBlank()) {
                    SectionLabel("Step 1 raw")
                    Spacer(Modifier.height(6.dp))
                    ChatBubbleUi.CodeLikeScrollableBlock(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        outline = outline,
                        text = step1Raw,
                        maxHeight = maxBlockHeight,
                    )
                }

                if (step2Raw.isNotBlank()) {
                    if (step1Raw.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                    }
                    SectionLabel("Step 2 raw")
                    Spacer(Modifier.height(6.dp))
                    ChatBubbleUi.CodeLikeScrollableBlock(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        outline = outline,
                        text = step2Raw,
                        maxHeight = maxBlockHeight,
                    )
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Tap to collapse",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                )
            }
        }
    }
}