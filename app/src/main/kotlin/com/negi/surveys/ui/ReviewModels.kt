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
import java.util.Locale

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
 * - [wireName] is the stable export/persistence key.
 * - Canonical kinds are:
 *   - USER
 *   - ASSESSMENT
 *   - FOLLOW_UP
 *   - ASSISTANT
 *   - DEBUG_RAW
 * - Legacy aliases remain for source compatibility during migration.
 * - Prefer using [canonical] and [wireName] for new code and exported artifacts.
 */
enum class ReviewChatKind(val wireName: String) {
    USER("user"),
    ASSESSMENT("assessment"),
    FOLLOW_UP("follow_up"),
    ASSISTANT("assistant"),
    DEBUG_RAW("debug_raw");

//    /**
//     * Legacy alias for generic assistant output.
//     *
//     * Notes:
//     * - Kept to avoid breaking existing call sites immediately.
//     * - Canonical form is [ASSISTANT].
//     * - New code should not use this enum entry directly.
//     */
//    @Deprecated(
//        message = "Use ASSISTANT for canonical assistant output.",
//        replaceWith = ReplaceWith("ASSISTANT"),
//        level = DeprecationLevel.WARNING
//    )
//    AI("assistant"),
//
//    /**
//     * Legacy alias for raw/debug model output.
//     *
//     * Notes:
//     * - Kept to avoid breaking existing call sites immediately.
//     * - Canonical form is [DEBUG_RAW].
//     * - New code should not use this enum entry directly.
//     */
//    @Deprecated(
//        message = "Use DEBUG_RAW for canonical raw/debug output.",
//        replaceWith = ReplaceWith("DEBUG_RAW"),
//        level = DeprecationLevel.WARNING
//    )
//    MODEL_RAW("debug_raw");

    /**
     * Returns the canonical kind for persistence, export, and rendering.
     *
     * Notes:
     * - Legacy aliases are normalized to their canonical counterparts.
     * - Canonical kinds return themselves unchanged.
     */
    fun canonical(): ReviewChatKind {
        return when (this) {
//            AI -> ASSISTANT
//            MODEL_RAW -> DEBUG_RAW
            USER -> USER
            ASSESSMENT -> ASSESSMENT
            FOLLOW_UP -> FOLLOW_UP
            ASSISTANT -> ASSISTANT
            DEBUG_RAW -> DEBUG_RAW
        }
    }

//    /**
//     * True when this enum entry is a compatibility alias.
//     */
//    val isLegacyAlias: Boolean
//        get() = this == AI || this == MODEL_RAW

    companion object {
        private val CANONICAL_BY_WIRE_NAME: Map<String, ReviewChatKind> =
            mapOf(
                USER.wireName to USER,
                ASSESSMENT.wireName to ASSESSMENT,
                FOLLOW_UP.wireName to FOLLOW_UP,
                ASSISTANT.wireName to ASSISTANT,
                DEBUG_RAW.wireName to DEBUG_RAW,
            )

        private val LEGACY_BY_WIRE_NAME: Map<String, ReviewChatKind> =
            mapOf(
                "ai" to ASSISTANT,
                "model_raw" to DEBUG_RAW,
            )

        /**
         * Parse a persisted/exported wire name into a canonical enum.
         *
         * Notes:
         * - Returns null when unknown to avoid inventing semantics.
         * - Old wire names remain accepted for backward compatibility.
         * - Locale.ROOT is required for deterministic case folding.
         * - This function always returns canonical kinds for stable downstream behavior.
         */
        fun fromWireName(wireName: String?): ReviewChatKind? {
            val w = wireName?.trim()?.lowercase(Locale.ROOT) ?: return null
            return CANONICAL_BY_WIRE_NAME[w] ?: LEGACY_BY_WIRE_NAME[w]
        }

        /**
         * Parse a persisted/exported wire name into a canonical enum, with a fallback.
         *
         * Notes:
         * - This is useful at rendering time where you prefer "show something" over null handling.
         * - The provided fallback is canonicalized before returning.
         */
        fun fromWireNameOr(wireName: String?, fallback: ReviewChatKind): ReviewChatKind {
            return fromWireName(wireName) ?: fallback.canonical()
        }
    }
}

/**
 * A single chat transcript line for Review/Export.
 *
 * Notes:
 * - Treat as an immutable value object.
 * - Store canonical kinds to keep exports and rendering stable.
 */
@Immutable
data class ReviewChatLine(
    val kind: ReviewChatKind,
    val text: String
) {
    /**
     * Returns a normalized copy whose kind is canonical.
     *
     * Notes:
     * - Useful when instances may have been constructed directly with legacy aliases.
     */
    fun normalized(): ReviewChatLine {
        val canonicalKind = kind.canonical()
        return if (canonicalKind == kind) {
            this
        } else {
            copy(kind = canonicalKind)
        }
    }

    companion object {
        /**
         * Safe factory to normalize input.
         *
         * Notes:
         * - Preserves content as-is (no trim) to avoid surprising UI diffs.
         * - Guards against null-like accidents by converting to empty string.
         * - Always canonicalizes the kind so new snapshots use stable semantics.
         */
        fun of(kind: ReviewChatKind, text: String?): ReviewChatLine {
            return ReviewChatLine(
                kind = kind.canonical(),
                text = text.orEmpty(),
            )
        }
    }
}

/**
 * Snapshot log for a single question.
 *
 * Notes:
 * - [lines] should be treated as an immutable snapshot (no mutation after creation).
 * - [completionPayload] should be non-empty by contract.
 *   If [isSkipped] is true and a blank payload is provided, we auto-generate a stable skipped payload.
 *
 * Important:
 * - This type is intentionally NOT annotated with @Immutable because constructor call sites
 *   can still provide a mutable list reference. Use [of] or [freeze] when storing snapshots.
 */
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
     * - Also canonicalizes any legacy line kinds during snapshotting.
     */
    fun freeze(): ReviewQuestionLog = copy(lines = lines.map { it.normalized() })

    companion object {
        /**
         * Safe factory that creates a defensive snapshot.
         *
         * Why:
         * - Callers might pass a MutableList and mutate it later by mistake.
         * - This factory snapshots the list to reduce accidental mutation bugs.
         *
         * Notes:
         * - In debug builds, we validate invariants more strictly.
         * - Does not trim prompt/payload to preserve export fidelity.
         * - Always trims [questionId] to make keys stable for map usage.
         * - All lines are canonicalized so exports avoid legacy aliases.
         */
        fun of(
            questionId: String,
            prompt: String,
            isSkipped: Boolean,
            completionPayload: String,
            lines: List<ReviewChatLine>
        ): ReviewQuestionLog {
            val qid = questionId.trim()

            // Generate a stable payload for skipped questions if the caller forgets to provide one.
            val payload =
                if (completionPayload.isNotBlank()) {
                    completionPayload
                } else {
                    if (isSkipped) SKIPPED_PAYLOAD_V1 else completionPayload
                }

            if (BuildConfig.DEBUG) {
                require(qid.isNotBlank()) { "questionId must not be blank" }
                require(prompt.isNotBlank()) { "prompt must not be blank" }
                require(payload.isNotBlank()) {
                    "completionPayload must not be blank (or provide isSkipped=true)"
                }
            }

            return ReviewQuestionLog(
                questionId = qid,
                prompt = prompt,
                isSkipped = isSkipped,
                completionPayload = payload,
                lines = lines.map { it.normalized() }
            )
        }

        /**
         * Stable skipped payload for export/debug.
         *
         * Notes:
         * - Keep this string stable across releases to avoid unnecessary diffs in exported artifacts.
         */
        private const val SKIPPED_PAYLOAD_V1 = "{\"skipped\":true}"
    }
}

/**
 * Flattened, review-friendly timeline items.
 *
 * Why:
 * - Review UI often wants a single list that shows everything in order.
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