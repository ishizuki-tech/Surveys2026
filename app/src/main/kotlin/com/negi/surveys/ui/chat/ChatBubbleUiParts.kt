/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Bubble UI Parts)
 *  ---------------------------------------------------------------------
 *  File: ChatBubbleUiParts.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * UI parts for chat bubbles.
 *
 * IMPORTANT:
 * - No parsing / no logging here.
 * - Pure UI building blocks.
 */
internal object ChatBubbleUi {

    data class Quad(
        val a: String,
        val b: Color,
        val c: Color,
        val d: Color,
    )

    @Composable
    fun RoleChip(
        label: String,
        bg: Color,
        fg: Color,
        dot: Color,
        trailing: String?
    ) {
        val chipShape = RoundedCornerShape(999.dp)

        Row(
            modifier = Modifier
                .clip(chipShape)
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(dot)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg
            )

            if (!trailing.isNullOrBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    fun EvalScoreChip(
        status: String?,
        score: Int,
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EVAL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = score.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )

                val st = status?.trim().orEmpty()
                if (st.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = st,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }

    @Composable
    fun FollowUpCard(question: String) {
        val qShape = RoundedCornerShape(14.dp)
        val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(qShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .border(1.dp, outline, qShape)
                .padding(horizontal = 12.dp, vertical = 12.dp)
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
                    text = question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }

    @Composable
    fun CompactDetailsPill(
        title: String,
        meta: String,
        action: String
    ) {
        val pillShape = RoundedCornerShape(12.dp)
        val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

        Row(
            modifier = Modifier
                .clip(pillShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .border(1.dp, outline, pillShape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = action,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            )
        }
    }

    @Composable
    fun CodeLikeScrollableBlock(
        shape: RoundedCornerShape,
        outline: Color,
        text: String,
        maxHeight: Dp,
        modifier: Modifier = Modifier,
    ) {
        val scroll = rememberScrollState()

        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f))
                .border(1.dp, outline, shape)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )

            val showHint = text.length >= 600
            AnimatedVisibility(
                visible = showHint,
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(90)),
                label = "scrollHint"
            ) {
                Text(
                    text = "Scroll",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 10.dp, bottom = 8.dp)
                )
            }
        }
    }
}