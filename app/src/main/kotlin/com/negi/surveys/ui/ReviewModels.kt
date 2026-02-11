/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: ReviewModels.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui

import androidx.compose.runtime.Immutable

/**
 * Review model types.
 *
 * Notes:
 * - Immutable snapshot models for Review/Export.
 * - Keep deterministic and stable across recompositions.
 */
enum class ReviewChatKind {
    USER,
    AI,
    FOLLOW_UP,
    MODEL_RAW
}

/**
 * A single chat transcript line for Review/Export.
 *
 * Notes:
 * - Treat as an immutable value object.
 */
@Immutable
data class ReviewChatLine(
    val kind: ReviewChatKind,
    val text: String
)

/**
 * Snapshot log for a single question.
 *
 * Notes:
 * - [lines] must be treated as an immutable snapshot (no mutation after creation).
 * - [completionPayload] is expected to be non-empty by contract (skipped payload is generated if needed).
 */
@Immutable
data class ReviewQuestionLog(
    val questionId: String,
    val prompt: String,
    val isSkipped: Boolean,
    val completionPayload: String,
    val lines: List<ReviewChatLine>
)

/**
 * Flattened, review-friendly timeline items.
 *
 * Why:
 * - Review UI often wants a single list that shows *everything* in order.
 * - Keeping this as a model avoids duplicating flattening logic across screens.
 *
 * Ordering:
 * - The producer (e.g., Session VM) is responsible for stable ordering.
 */
@Immutable
sealed interface ReviewTimelineItem {

    /**
     * Header marker for a question boundary.
     *
     * Notes:
     * - Includes [completionPayload] so Timeline view can remain self-contained.
     */
    @Immutable
    data class QuestionHeader(
        val questionId: String,
        val prompt: String,
        val isSkipped: Boolean,
        val completionPayload: String
    ) : ReviewTimelineItem

    /**
     * A single chat line that belongs to a question.
     */
    @Immutable
    data class Line(
        val questionId: String,
        val prompt: String,
        val isSkipped: Boolean,
        val line: ReviewChatLine
    ) : ReviewTimelineItem
}
