/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Validation Interface)
 *  ---------------------------------------------------------------------
 *  File: AnswerValidator.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

/**
 * Pluggable validator interface.
 *
 * Design goals:
 * - Keep the call-site API small and stable.
 * - Allow a fake implementation today and an SLM-backed implementation later.
 * - Make input normalization (e.g., trimming) deterministic and centralized.
 *
 * NOTE:
 * - The core methods ([validateMain], [validateFollowUp]) assume inputs are already normalized.
 * - Prefer using the provided convenience wrappers ([validateMainRaw], [validateFollowUpRaw])
 *   at call sites to avoid inconsistencies across screens / viewmodels.
 */
interface AnswerValidator {

    /**
     * Validate the main answer.
     *
     * Contract:
     * - [questionId] should be a stable identifier (e.g., "Q1") used for storage and logging.
     * - [answer] is expected to be already normalized (typically `trim()`).
     *
     * Implementation guidance:
     * - If you want strict behavior, you may treat blank inputs as invalid and return an
     *   appropriate [ValidationOutcome] (recommended).
     * - Avoid throwing for user input unless truly exceptional; a structured outcome is preferred.
     *
     * @param questionId Survey question id (e.g., "Q1").
     * @param answer User's main answer (already normalized).
     */
    suspend fun validateMain(questionId: String, answer: String): ValidationOutcome

    /**
     * Validate a follow-up answer, given the main answer.
     *
     * Contract:
     * - [questionId], [mainAnswer], and [followUpAnswer] are expected to be already normalized.
     * - Implementations may leverage [mainAnswer] as context for judging the follow-up response.
     *
     * @param questionId Survey question id.
     * @param mainAnswer The previously provided main answer (already normalized).
     * @param followUpAnswer Follow-up answer (already normalized).
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
     * @param questionId Survey question id (raw; will be normalized via `trim()`).
     * @param answer User's main answer (raw; will be normalized via `trim()`).
     */
    suspend fun validateMainRaw(questionId: String, answer: String): ValidationOutcome {
        return validateMain(
            questionId = normalizeId(questionId),
            answer = normalizeText(answer)
        )
    }

    /**
     * Convenience wrapper that normalizes inputs before calling [validateFollowUp].
     *
     * @param questionId Survey question id (raw; will be normalized via `trim()`).
     * @param mainAnswer Main answer (raw; will be normalized via `trim()`).
     * @param followUpAnswer Follow-up answer (raw; will be normalized via `trim()`).
     */
    suspend fun validateFollowUpRaw(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String
    ): ValidationOutcome {
        return validateFollowUp(
            questionId = normalizeId(questionId),
            mainAnswer = normalizeText(mainAnswer),
            followUpAnswer = normalizeText(followUpAnswer)
        )
    }

    /**
     * Normalize an identifier-like value for stable comparisons and keys.
     */
    private fun normalizeId(value: String): String = value.trim()

    /**
     * Normalize free-form user text for stable downstream validation behavior.
     */
    private fun normalizeText(value: String): String = value.trim()
}
