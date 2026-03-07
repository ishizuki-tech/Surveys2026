/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Advanced Answer Validator)
 *  ---------------------------------------------------------------------
 *  File: AnswerValidator.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import com.negi.surveys.logging.SafeLog

/**
 * Thin validator adapter.
 *
 * Design:
 * - Repository owns the structured two-step pipeline.
 * - Validator maps structured repository results into UI-facing ValidationOutcome.
 * - Final accept/follow-up status is derived from step-1 evaluation, not from step-2 text.
 * - Step-2 is used only for assistant-facing follow-up content generation.
 *
 * Privacy:
 * - Never log raw user answers or raw model JSON here.
 * - Logs must stay metadata-only.
 */
class AnswerValidator(
    private val repository: ChatValidation.RepositoryI,
    private val streamBridge: ChatStreamBridge,
    private val logger: ((String) -> Unit)? = null,
) : ChatValidation.AnswerValidatorI {

    override suspend fun validateMain(
        questionId: String,
        answer: String,
    ): ChatModels.ValidationOutcome {
        val qid = questionId.trim()
        val main = answer.trim()

        val result = repository.runTwoStepAssessment(
            request = ChatValidation.TwoStepAssessmentRequest(
                questionId = qid,
                mainAnswer = main,
                followUpAnswerPayload = null,
                fallbackFollowUp = DEFAULT_FOLLOW_UP_MAIN,
            ),
            streamBridge = streamBridge,
        )

        log(
            "validateMain: qid=$qid " +
                    "step1Status=${result.step1.status} " +
                    "step1Score=${result.step1.score} " +
                    "step2Present=${result.step2 != null} " +
                    "rawEvalLen=${result.rawEvalJson?.length ?: 0} " +
                    "rawFollowLen=${result.rawFollowUpJson?.length ?: 0}",
        )

        return mapToValidationOutcome(
            questionId = qid,
            result = result,
            fallbackFollowUp = DEFAULT_FOLLOW_UP_MAIN,
        )
    }

    override suspend fun validateFollowUp(
        questionId: String,
        mainAnswer: String,
        followUpAnswer: String,
    ): ChatModels.ValidationOutcome {
        val qid = questionId.trim()
        val main = mainAnswer.trim()
        val follow = followUpAnswer.trim()

        val result = repository.runTwoStepAssessment(
            request = ChatValidation.TwoStepAssessmentRequest(
                questionId = qid,
                mainAnswer = main,
                followUpAnswerPayload = follow,
                fallbackFollowUp = DEFAULT_FOLLOW_UP_MORE,
            ),
            streamBridge = streamBridge,
        )

        log(
            "validateFollowUp: qid=$qid " +
                    "step1Status=${result.step1.status} " +
                    "step1Score=${result.step1.score} " +
                    "step2Present=${result.step2 != null} " +
                    "rawEvalLen=${result.rawEvalJson?.length ?: 0} " +
                    "rawFollowLen=${result.rawFollowUpJson?.length ?: 0}",
        )

        return mapToValidationOutcome(
            questionId = qid,
            result = result,
            fallbackFollowUp = DEFAULT_FOLLOW_UP_MORE,
        )
    }

    /**
     * Normalize the repository result into a UI-facing outcome.
     *
     * Rules:
     * - Step-1 owns the final status.
     * - Step-2 only contributes assistant text and follow-up wording.
     * - If step-1 says NEED_FOLLOW_UP but step-2 follow-up is blank, use fallback.
     * - If step-1 says ACCEPTED, followUpQuestion must be null.
     */
    private fun mapToValidationOutcome(
        questionId: String,
        result: ChatValidation.TwoStepAssessmentResult,
        fallbackFollowUp: String,
    ): ChatModels.ValidationOutcome {
        val finalStatus = result.step1.status

        val assistantMessageFromStep2 = result.step2
            ?.assistantMessage
            ?.trim()
            .orEmpty()

        val followUpFromStep2 = result.step2
            ?.followUpQuestion
            ?.trim()
            .orEmpty()

        val normalizedFollowUp = when (finalStatus) {
            ChatModels.ValidationStatus.ACCEPTED -> null
            ChatModels.ValidationStatus.NEED_FOLLOW_UP -> {
                followUpFromStep2.takeIf { it.isNotBlank() } ?: fallbackFollowUp
            }
        }

        val normalizedAssistantMessage = when {
            assistantMessageFromStep2.isNotBlank() -> assistantMessageFromStep2
            finalStatus == ChatModels.ValidationStatus.ACCEPTED ->
                DEFAULT_ACCEPTED_ASSISTANT_MESSAGE
            else ->
                DEFAULT_NEED_MORE_ASSISTANT_MESSAGE
        }

        log(
            "mapOutcome: qid=$questionId " +
                    "finalStatus=$finalStatus " +
                    "score=${result.step1.score} " +
                    "assistantLen=${normalizedAssistantMessage.length} " +
                    "followUpLen=${normalizedFollowUp?.length ?: 0}",
        )

        return ChatModels.ValidationOutcome(
            status = finalStatus,
            assistantMessage = normalizedAssistantMessage,
            followUpQuestion = normalizedFollowUp,
            evalStatus = result.step1.status,
            evalScore = result.step1.score,
            evalReason = result.step1.reason,
            step1Raw = result.rawEvalJson,
            step2Raw = result.rawFollowUpJson,
        )
    }

    /**
     * Metadata-only logger.
     */
    private fun log(msg: String) {
        logger?.invoke(msg) ?: SafeLog.d(TAG, msg)
    }

    companion object {
        private const val TAG = "AnswerValidator"

        private const val DEFAULT_FOLLOW_UP_MAIN =
            "Could you add one concrete detail or example?"

        private const val DEFAULT_FOLLOW_UP_MORE =
            "Could you add one more concrete detail?"

        private const val DEFAULT_ACCEPTED_ASSISTANT_MESSAGE =
            "Thanks. This answer is sufficient."

        private const val DEFAULT_NEED_MORE_ASSISTANT_MESSAGE =
            "Thanks. I need one more detail before I can proceed."
    }
}