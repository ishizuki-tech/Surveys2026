/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Validation Contracts)
 *  ---------------------------------------------------------------------
 *  File: ChatValidation.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

/**
 * Validation contracts used across repository, validator, and UI layers.
 */
object ChatValidation {

    /**
     * Prompt phase.
     *
     * Notes:
     * - VALIDATE_MAIN and VALIDATE_FOLLOW_UP are kept for compatibility.
     * - STEP1_EVAL and STEP2_FOLLOW_UP are the structured two-step pipeline phases.
     */
    enum class PromptPhase {
        VALIDATE_MAIN,
        VALIDATE_FOLLOW_UP,
        STEP1_EVAL,
        STEP2_FOLLOW_UP,
    }

    /**
     * Repository contract.
     *
     * Notes:
     * - [request] is kept for legacy/simple flows.
     * - [runTwoStepAssessment] is the preferred structured pipeline for 2-step validation.
     */
    interface RepositoryI {
        fun buildPrompt(
            userPrompt: String,
            phase: PromptPhase,
        ): String

        suspend fun request(prompt: String): kotlinx.coroutines.flow.Flow<String>

        suspend fun runTwoStepAssessment(
            request: TwoStepAssessmentRequest,
            streamBridge: ChatStreamBridge? = null,
        ): TwoStepAssessmentResult
    }

    /**
     * Validator contract used by the ViewModel.
     */
    interface AnswerValidatorI {
        suspend fun validateMain(
            questionId: String,
            answer: String,
        ): ChatModels.ValidationOutcome

        suspend fun validateFollowUp(
            questionId: String,
            mainAnswer: String,
            followUpAnswer: String,
        ): ChatModels.ValidationOutcome
    }

    /**
     * Structured request for the two-step assessment pipeline.
     */
    data class TwoStepAssessmentRequest(
        val questionId: String,
        val mainAnswer: String,
        val followUpAnswerPayload: String?,
        val fallbackFollowUp: String,
    )

    /**
     * Structured result for the full two-step pipeline.
     *
     * Notes:
     * - [step1] is always present.
     * - [step2] is present only when a follow-up generation pass was needed.
     * - [rawEvalJson] / [rawFollowUpJson] are safe-to-render raw model outputs for details UI.
     */
    data class TwoStepAssessmentResult(
        val step1: Step1EvalResult,
        val step2: Step2FollowUpResult?,
        val rawEvalJson: String?,
        val rawFollowUpJson: String?,
    )

    /**
     * Structured step-1 evaluation result.
     */
    data class Step1EvalResult(
        val status: ChatModels.ValidationStatus,
        val score: Int,
        val reason: String,
        val missing: List<String> = emptyList(),
    )

    /**
     * Structured step-2 follow-up generation result.
     *
     * Notes:
     * - [assistantMessage] is optional.
     * - [followUpQuestion] is expected when [status] is NEED_FOLLOW_UP.
     */
    data class Step2FollowUpResult(
        val status: ChatModels.ValidationStatus,
        val assistantMessage: String?,
        val followUpQuestion: String?,
    )
}