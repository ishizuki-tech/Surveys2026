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
 * Validates user answers (main and follow-up) and returns a structured [ValidationOutcome].
 *
 * Design goals:
 * - Deterministic input normalization (stable keys, stable comparisons, stable logs).
 * - Validator implementations should avoid throwing on user input; return structured outcomes instead.
 * - Call sites can use `validate*Raw()` to guarantee consistent normalization everywhere.
 *
 * Threading:
 * - Normalization is pure and allocation-bounded; safe to call on any dispatcher.
 * - Actual validation may call out to models / databases; implementations should be coroutine-friendly.
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
     * - Treat blank inputs as invalid unless the survey explicitly allows them.
     * - Avoid throwing for expected user input issues; express them in [ValidationOutcome].
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
     * - [answer] is normalized via [normalizeAnswerText] to remove invisible/hostile characters and
     *   collapse whitespace deterministically.
     */
    suspend fun validateMainRaw(questionId: String, answer: String): ValidationOutcome {
        return validateMain(
            questionId = normalizeId(questionId),
            answer = normalizeAnswerText(answer)
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
            questionId = normalizeId(questionId),
            mainAnswer = normalizeAnswerText(mainAnswer),
            followUpAnswer = normalizeAnswerText(followUpAnswer)
        )
    }

    /**
     * Normalize an identifier-like value for stable comparisons and keys.
     *
     * Rationale:
     * - IDs are usually logical keys; accidental whitespace/case differences should not fork storage.
     * - Locale.US avoids Turkish-I and other locale-specific case surprises.
     */
    private fun normalizeId(raw: String): String {
        return raw.trim().uppercase(Locale.US)
    }

    /**
     * Normalize free-form user text for stable downstream validation behavior.
     *
     * What this does:
     * - Drops "invisible" format characters that commonly appear in copy/paste and can break
     *   comparisons, logs, or downstream parsing:
     *   - Zero-width chars: ZWSP/ZWNJ/ZWJ
     *   - BOM
     *   - Soft hyphen
     *   - Bidirectional controls (Bidi override/embedding/isolate markers)
     * - Converts all Unicode whitespace (including newlines/tabs) into a single ASCII space.
     * - Collapses consecutive spaces to one.
     * - Trims leading/trailing spaces deterministically.
     *
     * What this does NOT do:
     * - It does not change visible punctuation, casing, or language-specific characters.
     * - It does not perform locale-sensitive transformations (those belong in higher-level logic).
     *
     * Security/debuggability note:
     * - Dropping Bidi controls helps prevent visually confusing logs and UI strings that appear
     *   reordered or "spoofed" in a way that's hard to debug.
     */
    private fun normalizeAnswerText(raw: String): String {
        if (raw.isEmpty()) return ""

        val sb = StringBuilder(raw.length)
        var lastWasSpace = true // Treat as "already spaced" to drop leading whitespace.

        for (ch in raw) {
            // Drop invisible or potentially hostile formatting characters.
            if (isIgnorableFormatChar(ch)) {
                continue
            }

            // Drop ISO control characters, except whitespace-like ones which we normalize to spaces.
            if (Character.isISOControl(ch)) {
                when (ch) {
                    '\n', '\r', '\t' -> {
                        if (!lastWasSpace) {
                            sb.append(' ')
                            lastWasSpace = true
                        }
                    }
                    else -> {
                        // Drop remaining control chars (including DEL and C1 controls).
                    }
                }
                continue
            }

            // Normalize any Unicode whitespace to a single ASCII space.
            if (ch.isWhitespace()) {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
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
     * This is intentionally conservative and focused on commonly problematic characters.
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
 * Keep this enum consistent across the validator/repository stack:
 * - Any mismatch can lead to "wrong prompt shape" bugs that are painful to trace.
 * - If you already define this enum elsewhere, remove the duplicate and import it instead.
 */
enum class PromptPhase {
    /**
     * Phase used when validating the main answer (first user response).
     */
    VALIDATE_MAIN,

    /**
     * Phase used when validating a follow-up answer (after the model requests clarification).
     */
    VALIDATE_FOLLOW_UP
}

/**
 * Minimal repository interface for streaming inference + prompt construction.
 *
 * Notes on [request]:
 * - The returned [Flow] is expected to be a token/text stream (delta chunks).
 * - Collectors should be able to cancel collection to stop generation.
 * - Implementations should document whether chunks are:
 *   - raw model deltas, or
 *   - already post-processed (e.g., de-tokenized, sanitized, etc.).
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
     * - and keep formatting deterministic to minimize prompt drift.
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