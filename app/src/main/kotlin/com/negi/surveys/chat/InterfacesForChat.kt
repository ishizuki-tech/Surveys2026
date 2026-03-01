/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Validation Interface)
 *  ---------------------------------------------------------------------
 *  File: InterfacesForChat.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import java.util.Locale
import kotlinx.coroutines.flow.Flow

/**
 * Deterministic normalization utilities for stable comparisons, storage keys, and logs.
 *
 * Design goals:
 * - Stable behavior across devices/locales (avoid locale surprises).
 * - Drop invisible/hostile formatting chars that can poison equality/logs/parsers.
 * - Collapse whitespace deterministically to reduce accidental drift from copy/paste.
 */
internal object AnswerNormalization {

    /**
     * Normalize an identifier-like value for stable comparisons and keys.
     *
     * Rationale:
     * - IDs are logical keys; whitespace/case differences should not fork storage.
     * - Locale.US avoids Turkish-I and other locale-specific casing surprises.
     */
    fun normalizeId(raw: String): String {
        return raw.trim().uppercase(Locale.US)
    }

    /**
     * Normalize free-form user text for stable downstream validation behavior.
     *
     * What this does:
     * - Drops invisible "format" characters commonly introduced by copy/paste:
     *   - Zero-width chars (ZWSP/ZWNJ/ZWJ)
     *   - BOM
     *   - Soft hyphen
     *   - Bidirectional controls (embedding/override/isolate markers)
     * - Converts any Unicode whitespace into a single ASCII space.
     * - Collapses consecutive spaces to one.
     * - Trims leading/trailing spaces deterministically.
     *
     * What this does NOT do:
     * - No punctuation/casing/language-specific transformations.
     */
    fun normalizeAnswerText(raw: String): String {
        if (raw.isEmpty()) return ""

        val sb = StringBuilder(raw.length)
        var lastWasSpace = true // Treat as "already spaced" to drop leading whitespace.

        fun appendSpaceIfNeeded() {
            if (!lastWasSpace) {
                sb.append(' ')
                lastWasSpace = true
            }
        }

        for (ch in raw) {
            if (isIgnorableFormatChar(ch)) {
                continue
            }

            // Normalize any Unicode whitespace to a single ASCII space.
            if (ch.isWhitespace()) {
                appendSpaceIfNeeded()
                continue
            }

            // Drop ISO control characters that are not whitespace.
            if (Character.isISOControl(ch)) {
                continue
            }

            sb.append(ch)
            lastWasSpace = false
        }

        // Trim trailing space if we ended with whitespace.
        if (sb.isNotEmpty() && sb.last() == ' ') {
            sb.setLength(sb.length - 1)
        }

        return sb.toString()
    }

    /**
     * Returns true for invisible "format" characters that tend to cause subtle bugs in:
     * - string equality checks
     * - logging/telemetry readability
     * - downstream parsing/tokenization
     *
     * This list is intentionally conservative and focused on commonly problematic characters.
     */
    private fun isIgnorableFormatChar(ch: Char): Boolean {
        return when (ch) {
            '\u200B', // ZERO WIDTH SPACE (ZWSP)
            '\u200C', // ZERO WIDTH NON-JOINER (ZWNJ)
            '\u200D', // ZERO WIDTH JOINER (ZWJ)
            '\uFEFF', // BYTE ORDER MARK (BOM)
            '\u00AD', // SOFT HYPHEN
                -> true

            // Bidi embedding/override/pop directional formatting
            '\u202A', // LRE
            '\u202B', // RLE
            '\u202C', // PDF
            '\u202D', // LRO
            '\u202E', // RLO
                -> true

            // Bidi isolate markers
            '\u2066', // LRI
            '\u2067', // RLI
            '\u2068', // FSI
            '\u2069', // PDI
                -> true

            else -> false
        }
    }
}

/**
 * Validates user answers (main and follow-up) and returns a structured [ValidationOutcome].
 *
 * Design goals:
 * - Deterministic input normalization (stable keys, stable comparisons, stable logs).
 * - Avoid throwing on user input; return structured outcomes instead.
 * - Prefer `validate*Raw()` at call sites to ensure consistent normalization everywhere.
 *
 * Threading:
 * - Normalization is pure and allocation-bounded; safe on any dispatcher.
 * - Validation may call models/databases; implementations should be coroutine-friendly.
 */
interface AnswerValidatorI {

    /**
     * Validate the main answer.
     *
     * Contract:
     * - [questionId] MUST be stable (e.g., "Q1") and is expected to be normalized already.
     * - [answer] is expected to be normalized already (see [validateMainRaw]).
     *
     * Recommendations:
     * - Treat blank inputs as invalid unless explicitly allowed by the survey.
     * - Express expected user input issues via [ValidationOutcome] rather than throwing.
     *
     * @param questionId Survey question id (e.g., "Q1"), normalized for stable storage/logging.
     * @param answer User's main answer, normalized for stable downstream behavior.
     */
    suspend fun validateMain(questionId: String, answer: String): ValidationOutcome

    /**
     * Validate a follow-up answer, given the main answer.
     *
     * Contract:
     * - [questionId], [mainAnswer], and [followUpAnswer] are expected to be normalized already.
     * - Implementations may use [mainAnswer] as context for judging [followUpAnswer].
     *
     * @param questionId Survey question id, normalized.
     * @param mainAnswer Previously provided main answer, normalized.
     * @param followUpAnswer Follow-up answer, normalized.
     */
    suspend fun validateFollowUp(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String
    ): ValidationOutcome

    /**
     * Convenience wrapper that normalizes inputs before calling [validateMain].
     *
     * Use this at call sites unless you have a strong reason to normalize elsewhere.
     *
     * Normalization policy:
     * - [questionId] is trimmed and uppercased with Locale.US (e.g., "q1" -> "Q1").
     * - [answer] is normalized via [AnswerNormalization.normalizeAnswerText] to remove
     *   invisible/hostile characters and collapse whitespace deterministically.
     */
    suspend fun validateMainRaw(questionId: String, answer: String): ValidationOutcome {
        return validateMain(
            questionId = AnswerNormalization.normalizeId(questionId),
            answer = AnswerNormalization.normalizeAnswerText(answer)
        )
    }

    /**
     * Convenience wrapper that normalizes inputs before calling [validateFollowUp].
     *
     * Normalization policy is identical to [validateMainRaw].
     */
    suspend fun validateFollowUpRaw(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String
    ): ValidationOutcome {
        return validateFollowUp(
            questionId = AnswerNormalization.normalizeId(questionId),
            mainAnswer = AnswerNormalization.normalizeAnswerText(mainAnswer),
            followUpAnswer = AnswerNormalization.normalizeAnswerText(followUpAnswer)
        )
    }
}

/**
 * Prompt phases used by a repository / prompt builder for validation flows.
 *
 * Keep this enum consistent across the validator/repository stack.
 * Any mismatch can lead to "wrong prompt shape" bugs that are painful to trace.
 *
 * If you already define this enum elsewhere, remove the duplicate and import it instead.
 */
enum class PromptPhase {
    /** Phase used when validating the main answer (first user response). */
    VALIDATE_MAIN,

    /** Phase used when validating a follow-up answer (after the model requests clarification). */
    VALIDATE_FOLLOW_UP
}

/**
 * Minimal repository interface for streaming inference + prompt construction.
 *
 * Notes on [request]:
 * - Returned [Flow] is expected to be a token/text stream (delta chunks).
 * - Collectors should be able to cancel collection to stop generation.
 * - Implementations should document whether chunks are raw deltas or post-processed.
 */
interface RepositoryI {

    /**
     * Execute a single streaming inference for the given full model-ready [prompt].
     *
     * @param prompt A fully constructed prompt string that the model can consume directly.
     * @return A flow of incremental text chunks (streaming output).
     */
    suspend fun request(prompt: String): Flow<String>

    /**
     * Build the full model-ready prompt string from a user-level [userPrompt].
     *
     * Implementations should:
     * - apply system/instruction scaffolding,
     * - inject metadata (e.g., question id, expected JSON shape),
     * - keep formatting deterministic to minimize prompt drift.
     */
    fun buildPrompt(userPrompt: String): String

    /**
     * Phase-aware prompt building.
     *
     * Default behavior delegates to [buildPrompt] for backward compatibility.
     * Override when your prompt shape differs between phases.
     */
    fun buildPrompt(userPrompt: String, phase: PromptPhase): String = buildPrompt(userPrompt)
}