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
import com.negi.surveys.BuildConfig

/**
 * Review model types.
 *
 * Notes:
 * - Immutable snapshot models for Review/Export.
 * - Keep deterministic and stable across recompositions.
 */

/**
 * Chat line kind for review/export.
 *
 * Notes:
 * - [wireName] is a stable string used for export (rename-safe).
 * - Prefer using [wireName] for persisted/exported formats (avoid enum ordinal).
 */
enum class ReviewChatKind(val wireName: String) {
    USER("user"),
    AI("ai"),
    FOLLOW_UP("follow_up"),
    MODEL_RAW("model_raw");

    companion object {
        /**
         * Parse a persisted/exported wire name into an enum.
         *
         * Notes:
         * - Returns null when unknown to avoid inventing semantics.
         * - Call sites may fallback to MODEL_RAW or AI depending on their policy.
         */
        fun fromWireName(wireName: String?): ReviewChatKind? {
            val w = wireName?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wireName == w }
        }
    }
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
) {
    companion object {
        /**
         * Safe factory to normalize input.
         *
         * Notes:
         * - Preserves content as-is (no trim) to avoid surprising UI diffs.
         * - Guards against null-like accidents by converting to empty string.
         */
        fun of(kind: ReviewChatKind, text: String?): ReviewChatLine {
            return ReviewChatLine(kind = kind, text = text.orEmpty())
        }
    }
}

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
) {
    /**
     * Returns a defensively-copied version so external mutable lists cannot mutate stored logs.
     *
     * Notes:
     * - Useful when you need to store a snapshot derived from a MutableList.
     */
    fun freeze(): ReviewQuestionLog = copy(lines = lines.toList())

    companion object {
        /**
         * Safe factory that creates a defensive snapshot.
         *
         * Why:
         * - Callers might pass a MutableList and mutate it later by mistake.
         * - This factory snapshots the list to reduce "accidental mutation" bugs.
         *
         * Notes:
         * - In debug builds, we validate invariants more strictly.
         * - Does not trim prompt/payload to preserve export fidelity.
         */
        fun of(
            questionId: String,
            prompt: String,
            isSkipped: Boolean,
            completionPayload: String,
            lines: List<ReviewChatLine>
        ): ReviewQuestionLog {
            val qid = questionId.trim()

            if (BuildConfig.DEBUG) {
                require(qid.isNotBlank()) { "questionId must not be blank" }
                require(prompt.isNotBlank()) { "prompt must not be blank" }
                require(completionPayload.isNotBlank()) { "completionPayload must not be blank" }
            }

            return ReviewQuestionLog(
                questionId = qid,
                prompt = prompt,
                isSkipped = isSkipped,
                completionPayload = completionPayload,
                lines = lines.toList()
            )
        }
    }
}

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

    /** Question id associated with this timeline item. */
    val questionId: String

    /**
     * Header marker for a question boundary.
     *
     * Notes:
     * - Includes [completionPayload] so Timeline view can remain self-contained.
     */
    @Immutable
    data class QuestionHeader(
        override val questionId: String,
        val prompt: String,
        val isSkipped: Boolean,
        val completionPayload: String
    ) : ReviewTimelineItem

    /**
     * A single chat line that belongs to a question.
     */
    @Immutable
    data class Line(
        override val questionId: String,
        val prompt: String,
        val isSkipped: Boolean,
        val line: ReviewChatLine
    ) : ReviewTimelineItem
}