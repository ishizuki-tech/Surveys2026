/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Shared Prompt Builder)
 *  ---------------------------------------------------------------------
 *  File: SlmPromptBuilder.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

/**
 * Shared prompt builder contract used by both real and fake repositories.
 *
 * Design notes:
 * - Keep prompt composition centralized to avoid drift between implementations.
 * - Do NOT perform I/O here. All config/system prompt resolution must be done by the caller.
 */
interface SlmPromptBuilder {

    /**
     * Builds an answer-like prompt (one-step generation).
     *
     * @param systemPrompt Optional system prompt (already resolved by the caller).
     * @param userPrompt User prompt (raw). This function will trim and validate it.
     */
    fun buildAnswerLikePrompt(
        systemPrompt: String?,
        userPrompt: String,
    ): String

    /**
     * Builds the step-1 evaluation prompt that must output exactly one JSON object.
     */
    fun buildEvalScorePrompt(
        questionId: String,
        userPrompt: String,
        acceptScoreThreshold: Int,
        userPromptMaxChars: Int,
    ): String

    /**
     * Builds the step-2 follow-up prompt that must output exactly one JSON object.
     */
    fun buildFollowUpPrompt(
        questionId: String,
        userPrompt: String,
        evalJson: String,
        acceptScoreThreshold: Int,
        userPromptMaxChars: Int,
        evalJsonMaxChars: Int,
    ): String
}

/**
 * Default implementation shared across repositories.
 */
object DefaultSlmPromptBuilder : SlmPromptBuilder {

    override fun buildEvalScorePrompt(
        questionId: String,
        userPrompt: String,
        acceptScoreThreshold: Int,
        userPromptMaxChars: Int,
    ): String {
        val clippedUser = clipForEval(userPrompt, userPromptMaxChars)

        return """
            Return exactly ONE JSON object and nothing else.
            - No markdown, no code fences, no backticks.
            - Output must start with "{" and end with "}".
            - Use double quotes for all JSON strings.
            - Do not include trailing commas.

            Required keys:
            - "status": either "ACCEPTED" or "NEED_FOLLOW_UP"
            - "score": integer from 0 to 100
            - "reason": short string (<= 160 chars)

            Optional key:
            - "missing": array of short strings describing missing info.

            Scoring guidance:
            - 90-100: fully answerable, clear constraints, no key ambiguity.
            - 70-89: answerable but minor ambiguity.
            - 40-69: missing important constraints; follow-up is needed.
            - 0-39: very unclear; must ask follow-up.

            Decision rule:
            - If score >= $acceptScoreThreshold => status="ACCEPTED"
            - Else => status="NEED_FOLLOW_UP"

            QUESTION_ID: $questionId

            USER_PROMPT:
            $clippedUser
        """.trimIndent()
    }

    override fun buildFollowUpPrompt(
        questionId: String,
        userPrompt: String,
        evalJson: String,
        acceptScoreThreshold: Int,
        userPromptMaxChars: Int,
        evalJsonMaxChars: Int,
    ): String {
        val clippedEval = clipForEval(evalJson, evalJsonMaxChars)
        val clippedUser = clipForEval(userPrompt, userPromptMaxChars)

        return """
            Return exactly ONE JSON object and nothing else.
            - No markdown, no code fences, no backticks.
            - Output must start with "{" and end with "}".
            - Use double quotes for all JSON strings.
            - Do not include trailing commas.

            Valid shapes:
            1) {"status":"ACCEPTED","followUpQuestion":""}
            2) {"status":"NEED_FOLLOW_UP","followUpQuestion":"..."}

            Rules:
            - Read EVAL_JSON and USER_PROMPT.
            - If EVAL_JSON.score >= $acceptScoreThreshold:
                - Return ACCEPTED with followUpQuestion="".
            - Else:
                - Return NEED_FOLLOW_UP and ask exactly ONE concise follow-up question.
                - The question must target the most important missing constraint.
                - Do not repeat large chunks of USER_PROMPT verbatim.

            QUESTION_ID: $questionId

            EVAL_JSON:
            $clippedEval

            USER_PROMPT:
            $clippedUser
        """.trimIndent()
    }

    override fun buildAnswerLikePrompt(
        systemPrompt: String?,
        userPrompt: String,
    ): String {
        val u = userPrompt.trim()
        if (u.isBlank()) return ""

        val sys = systemPrompt.orEmpty().trim()
        if (sys.isBlank()) return u

        return """
            $sys

            $u
        """.trimIndent()
    }

    private fun clipForEval(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text

        var end = maxChars.coerceAtMost(text.length)
        if (end <= 0) return ""

        // Prevent cutting surrogate pairs.
        if (end < text.length) {
            val last = text[end - 1]
            val next = text[end]
            if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
                end -= 1
            }
        }

        if (end <= 0) return ""
        return text.take(end)
    }
}