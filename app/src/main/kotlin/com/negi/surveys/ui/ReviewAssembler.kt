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
import java.security.MessageDigest

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
     * De-dup policy (important for Compose Lazy* keys safety):
     * - If ANY ASSISTANT message contains an embedded model output (streamText),
     *   then MODEL-role messages are treated as transient and are ignored.
     * - MODEL_RAW lines are de-duplicated by content hash to avoid double counting
     *   (e.g., when both embedded output and leftover MODEL bubble exist).
     */
    private fun List<ChatMessage>.toReviewLines(): List<ReviewChatLine> {
        if (isEmpty()) return emptyList()

        // English comments only.
        /** Prefer embedded model output on ASSISTANT messages over transient MODEL bubbles. */
        val hasEmbeddedModelOutput = any { it.role == ChatRole.ASSISTANT && !it.streamText.isNullOrBlank() }

        val out = ArrayList<ReviewChatLine>(size * 2)

        // English comments only.
        /** De-duplicate MODEL_RAW lines by content to prevent identical keys in UI. */
        val seenModelRaw = HashSet<String>(8)

        fun addLine(kind: ReviewChatKind, text: String) {
            val t = text.trim()
            if (t.isEmpty()) return

            if (kind == ReviewChatKind.MODEL_RAW) {
                val sig = sha256Hex(t)
                if (!seenModelRaw.add(sig)) return
            }

            out += ReviewChatLine(kind, t)
        }

        for (m in this) {
            when (m.role) {
                ChatRole.USER -> {
                    addLine(ReviewChatKind.USER, m.text.orEmpty())
                }

                ChatRole.ASSISTANT -> {
                    addLine(ReviewChatKind.AI, m.assistantMessage.orEmpty())
                    addLine(ReviewChatKind.FOLLOW_UP, m.followUpQuestion.orEmpty())
                    addLine(ReviewChatKind.MODEL_RAW, m.streamText.orEmpty())
                }

                ChatRole.MODEL -> {
                    if (hasEmbeddedModelOutput) {
                        // Skip transient streaming bubbles when the embedded result exists.
                        continue
                    }
                    val raw = (m.streamText ?: m.text).orEmpty()
                    addLine(ReviewChatKind.MODEL_RAW, raw)
                }
            }
        }

        return out
    }

    // English comments only.
    /** SHA-256 hex for content de-dup signatures (fast enough for review assembly). */
    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))

        val hex = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hex[(v ushr 4) and 0xF])
            sb.append(hex[v and 0xF])
        }
        return sb.toString()
    }
}