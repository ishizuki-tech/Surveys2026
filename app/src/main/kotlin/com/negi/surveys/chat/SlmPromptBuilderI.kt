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
interface SlmPromptBuilderI {

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
