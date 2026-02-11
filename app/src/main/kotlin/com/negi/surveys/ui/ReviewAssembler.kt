/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Review Assembler)
 *  ---------------------------------------------------------------------
 *  File: ReviewAssembler.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui

import com.negi.surveys.chat.ChatDraftStore
import com.negi.surveys.chat.ChatMessage
import com.negi.surveys.chat.ChatRole
import com.negi.surveys.chat.DraftKey

/**
 * Review assembly helpers.
 *
 * Responsibilities:
 * - Build ReviewQuestionLog list from survey question definitions + ChatDraftStore.
 * - Avoid relying on DraftStore key enumeration (SmallStep "latter" approach).
 *
 * Notes:
 * - This layer intentionally stays "dumb": no persistence, no navigation.
 * - It is designed to be called from SurveyAppRoot/SurveyVM at Review entry.
 */
object ReviewAssembler {

    /**
     * Minimal survey question definition required to build review logs.
     */
    data class ReviewQuestionDef(
        val questionId: String,
        val prompt: String
    )

    /**
     * Build review logs for all questions in [defs].
     *
     * @param defs Survey-owned question list (stable order is preferred; ReviewScreen also sorts).
     * @param draftStore Chat draft storage used by ChatQuestionViewModel.
     * @param keyForQuestion A provider that maps questionId -> DraftKey.
     *
     * @return Immutable snapshot logs suitable for ReviewScreen/Export.
     */
    fun buildLogs(
        defs: List<ReviewQuestionDef>,
        draftStore: ChatDraftStore,
        keyForQuestion: (questionId: String) -> DraftKey
    ): List<ReviewQuestionLog> {
        if (defs.isEmpty()) return emptyList()

        return defs.map { def ->
            val key = keyForQuestion(def.questionId)
            val draft = draftStore.load(key)

            if (draft == null) {
                ReviewQuestionLog(
                    questionId = def.questionId,
                    prompt = def.prompt,
                    isSkipped = true,
                    completionPayload = "(no draft)",
                    lines = emptyList()
                )
            } else {
                val lines = draft.messages.toReviewLines()
                val completion = draft.completionPayload?.trim().orEmpty().ifEmpty { "(no completionPayload)" }

                val isSkipped = lines.none { it.kind == ReviewChatKind.USER } &&
                        completion == "(no completionPayload)"

                ReviewQuestionLog(
                    questionId = def.questionId,
                    prompt = def.prompt,
                    isSkipped = isSkipped,
                    completionPayload = completion,
                    lines = lines
                )
            }
        }
    }

    /**
     * Convert chat draft messages into review transcript lines.
     *
     * Mapping policy:
     * - USER -> ReviewChatKind.USER
     * - ASSISTANT -> split into AI + FOLLOW_UP (+ optional MODEL_RAW if model output exists)
     * - MODEL (streaming bubble) -> MODEL_RAW
     *
     * Important:
     * - This assumes ChatMessage exposes the fields used by your VM:
     *   role/text/assistantMessage/followUpQuestion/streamText.
     * - If your ChatMessage names differ, adjust only this adapter.
     */
    private fun List<ChatMessage>.toReviewLines(): List<ReviewChatLine> {
        if (isEmpty()) return emptyList()

        val out = ArrayList<ReviewChatLine>(size * 2)

        for (m in this) {
            when (m.role) {
                ChatRole.USER -> {
                    val t = m.text.orEmpty().trim()
                    if (t.isNotEmpty()) out += ReviewChatLine(ReviewChatKind.USER, t)
                }

                ChatRole.ASSISTANT -> {
                    val ai = m.assistantMessage.orEmpty().trim()
                    val fu = m.followUpQuestion.orEmpty().trim()

                    if (ai.isNotEmpty()) out += ReviewChatLine(ReviewChatKind.AI, ai)
                    if (fu.isNotEmpty()) out += ReviewChatLine(ReviewChatKind.FOLLOW_UP, fu)

                    val raw = m.streamText.orEmpty().trim()
                    if (raw.isNotEmpty()) out += ReviewChatLine(ReviewChatKind.MODEL_RAW, raw)
                }

                ChatRole.MODEL -> {
                    val raw = (m.streamText ?: m.text).orEmpty().trim()
                    if (raw.isNotEmpty()) out += ReviewChatLine(ReviewChatKind.MODEL_RAW, raw)
                }
            }
        }

        return out
    }
}
