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
