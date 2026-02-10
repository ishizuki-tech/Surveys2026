/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Fake Validator)
 *  ---------------------------------------------------------------------
 *  File: FakeAnswerValidator.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Fake validator to prove the UI + state machine works.
 *
 * Goals:
 * - Provide deterministic, easy-to-reason rules that simulate an SLM validator.
 * - Produce consistent outcomes for "main" vs "follow-up" paths.
 * - Emit debug signals without logging user content (PII-safe).
 *
 * Notes:
 * - This class is intentionally simple and synchronous in logic, but suspends to simulate latency.
 * - This implementation assumes inputs are already normalized (trimmed) as per [AnswerValidator] contract.
 * - Prefer calling [AnswerValidator.validateMainRaw] / [AnswerValidator.validateFollowUpRaw] at call sites.
 */
class FakeAnswerValidator(
    /**
     * Minimum number of non-whitespace characters required to accept the main answer immediately.
     */
    minMainChars: Int = 12,

    /**
     * Minimum number of non-whitespace characters required to accept a follow-up answer.
     * (Typically smaller than main, since it's "one extra detail".)
     */
    minFollowUpChars: Int = 3,

    /**
     * Simulated validator latency (e.g., network/compute delay).
     */
    simulatedLatencyMs: Long = 250L,

    /**
     * Optional debug logger. If provided, receives PII-safe messages.
     * Example injection:
     *   FakeAnswerValidator(logger = { Log.d("FakeAnswerValidator", it) })
     */
    private val logger: ((String) -> Unit)? = null
) : AnswerValidator {

    private val minMainCharsSafe: Int = minMainChars.coerceAtLeast(0)
    private val minFollowUpCharsSafe: Int = minFollowUpChars.coerceAtLeast(0)
    private val simulatedLatencyMsSafe: Long = simulatedLatencyMs.coerceAtLeast(0L)

    override suspend fun validateMain(questionId: String, answer: String): ValidationOutcome {
        delay(simulatedLatencyMsSafe)

        // Contract says inputs are normalized, but we keep this stable and defensive.
        val qid = questionId.trim()
        val trimmed = answer.trim()
        val nonWsLen = nonWhitespaceLength(trimmed)

        log("validateMain: qid=$qid nonWsLen=$nonWsLen threshold=$minMainCharsSafe")

        // Treat very short or obvious placeholders as needing follow-up.
        if (nonWsLen < minMainCharsSafe || looksLikeNonAnswer(trimmed)) {
            log("validateMain: NEED_FOLLOW_UP qid=$qid nonWsLen=$nonWsLen")
            return ValidationOutcome.needFollowUp(
                assistantMessage = "Thanks. I need one more detail to validate your answer.",
                followUpQuestion = "Could you add one concrete detail or example?"
            )
        }

        log("validateMain: ACCEPTED qid=$qid nonWsLen=$nonWsLen")
        return ValidationOutcome.accepted(
            assistantMessage = "Looks good. Thanks!"
        )
    }

    override suspend fun validateFollowUp(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String
    ): ValidationOutcome {
        delay(simulatedLatencyMsSafe)

        val qid = questionId.trim()
        val mainTrimmed = mainAnswer.trim()
        val fuTrimmed = followUpAnswer.trim()

        val mainNonWsLen = nonWhitespaceLength(mainTrimmed)
        val fuNonWsLen = nonWhitespaceLength(fuTrimmed)

        log(
            "validateFollowUp: qid=$qid mainNonWsLen=$mainNonWsLen " +
                    "fuNonWsLen=$fuNonWsLen threshold=$minFollowUpCharsSafe"
        )

        // Follow-up must contain at least a small amount of detail.
        if (fuNonWsLen < minFollowUpCharsSafe || looksLikeNonAnswer(fuTrimmed)) {
            log("validateFollowUp: NEED_FOLLOW_UP qid=$qid fuNonWsLen=$fuNonWsLen")
            return ValidationOutcome.needFollowUp(
                assistantMessage = "I still need a bit more detail to proceed.",
                followUpQuestion = "Please add one short concrete detail (e.g., a number, a place, or a specific example)."
            )
        }

        log("validateFollowUp: ACCEPTED qid=$qid fuNonWsLen=$fuNonWsLen")
        return ValidationOutcome.accepted(
            assistantMessage = "Great. Now your answer is complete."
        )
    }

    /**
     * Heuristic: detect low-information placeholders that should trigger follow-up.
     *
     * Keep this conservative: we only flag obvious placeholders.
     */
    private fun looksLikeNonAnswer(s: String): Boolean {
        if (s.isBlank()) return true

        // Locale-stable normalization for simple placeholder matching.
        val t = s.trim().lowercase(Locale.ROOT)

        // Common "no answer" placeholders.
        return t == "n/a" ||
                t == "na" ||
                t == "none" ||
                t == "unknown" ||
                t == "idk" ||
                t == "-" ||
                t == "--" ||
                t == "?" ||
                t == "??"
    }

    /**
     * Counts non-whitespace characters to match the documented "non-whitespace" contract.
     */
    private fun nonWhitespaceLength(s: String): Int {
        if (s.isEmpty()) return 0
        var c = 0
        for (ch in s) {
            if (!ch.isWhitespace()) c++
        }
        return c
    }

    /**
     * Emit PII-safe debug logs if a logger was provided.
     */
    private fun log(msg: String) {
        logger?.invoke(msg)
    }
}
