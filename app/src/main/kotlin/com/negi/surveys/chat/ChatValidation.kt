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
 * Validation-related types for the chat subsystem.
 *
 * Top-level cleanup policy:
 * - Expose only the minimal public API at top-level.
 * - Keep helper utilities and internal contracts namespaced to avoid file-level clutter.
 */
object ChatValidation {

    /**
     * Deterministic normalization utilities for stable comparisons, storage keys, and logs.
     *
     * Design goals:
     * - Stable behavior across devices/locales (avoid locale surprises).
     * - Drop invisible/hostile formatting chars that can poison equality/logs/parsers.
     * - Collapse whitespace deterministically to reduce accidental drift from copy/paste.
     *
     * Notes:
     * - This object is intentionally conservative and biased toward safety and determinism.
     * - Do not log raw user answers; normalize first and/or log only metadata (length/hash).
     */
    internal object AnswerNormalization {

        /**
         * Normalize an identifier-like value for stable comparisons and keys.
         *
         * Rationale:
         * - IDs are logical keys; whitespace/case differences should not fork storage.
         * - Locale.US avoids Turkish-I and other locale-specific casing surprises.
         *
         * Contract:
         * - Empty input returns empty output. Call sites should treat empty IDs as invalid.
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
         * - Drops ISO control characters that are not whitespace (defensive against hostile input).
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
     * Prompt phases used by a repository / prompt builder for validation flows.
     *
     * Keep this enum consistent across the validator/repository stack.
     * Any mismatch can lead to "wrong prompt shape" bugs that are painful to trace.
     */
    enum class PromptPhase {
        VALIDATE_MAIN,
        VALIDATE_FOLLOW_UP
    }

    /**
     * Minimal repository interface for streaming inference + prompt construction.
     */
    interface RepositoryI {

        /**
         * Execute a single streaming inference for the given full model-ready [prompt].
         */
        suspend fun request(prompt: String): Flow<String>

        /**
         * Build the full model-ready prompt string from a user-level [userPrompt].
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

    /**
     * Validates user answers (main and follow-up) and returns a structured [ChatModels.ValidationOutcome].
     *
     * Design goals:
     * - Deterministic input normalization (stable keys, stable comparisons, stable logs).
     * - Avoid throwing on user input; return structured outcomes instead.
     * - Prefer `validate*Raw()` at call sites to ensure consistent normalization everywhere.
     */
    interface AnswerValidatorI {

        /**
         * Validate the main answer.
         *
         * Contract:
         * - [questionId] MUST be stable (e.g., "Q1") and is expected to be normalized already.
         * - [answer] is expected to be normalized already.
         */
        suspend fun validateMain(questionId: String, answer: String): ChatModels.ValidationOutcome

        /**
         * Validate a follow-up answer, given the main answer.
         */
        suspend fun validateFollowUp(
            questionId: String,
            mainAnswer: String,
            followUpAnswer: String
        ): ChatModels.ValidationOutcome

        /**
         * Convenience wrapper that normalizes inputs before calling [validateMain].
         */
        suspend fun validateMainRaw(questionId: String, answer: String): ChatModels.ValidationOutcome {
            return validateMain(
                questionId = AnswerNormalization.normalizeId(questionId),
                answer = AnswerNormalization.normalizeAnswerText(answer)
            )
        }

        /**
         * Convenience wrapper that normalizes inputs before calling [validateFollowUp].
         */
        suspend fun validateFollowUpRaw(
            questionId: String,
            mainAnswer: String,
            followUpAnswer: String
        ): ChatModels.ValidationOutcome {
            return validateFollowUp(
                questionId = AnswerNormalization.normalizeId(questionId),
                mainAnswer = AnswerNormalization.normalizeAnswerText(mainAnswer),
                followUpAnswer = AnswerNormalization.normalizeAnswerText(followUpAnswer)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Backward-compatible typealiases (optional but recommended during migration)
// -----------------------------------------------------------------------------

//typealias AnswerValidatorI = ChatValidation.AnswerValidatorI
//typealias RepositoryI = ChatValidation.RepositoryI
//typealias PromptPhase = ChatValidation.PromptPhase