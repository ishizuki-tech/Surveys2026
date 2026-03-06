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

import com.negi.surveys.chat.ChatDrafts
import com.negi.surveys.chat.ChatModels
import java.security.MessageDigest
import java.util.Locale

/**
 * Review assembly helpers.
 *
 * Responsibilities:
 * - Build [ReviewQuestionLog] snapshots from survey question definitions + ChatDraftStore.
 * - Avoid relying on DraftStore key enumeration.
 * - Convert draft chat messages into canonical review transcript kinds.
 *
 * Notes:
 * - This layer intentionally stays dumb: no persistence, no navigation.
 * - It is designed to be called from SurveyAppRoot / Survey VM at Review entry.
 * - This assembler follows the user-facing two-step protocol:
 *   - Step-1 => ASSESSMENT
 *   - Step-2 => FOLLOW_UP
 *   - Raw model/debug stream => DEBUG_RAW
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
     * @param defs Survey-owned question list.
     *   Stable order is preferred, though ReviewScreen also sorts naturally.
     * @param draftStore Chat draft storage used by ChatQuestionViewModel.
     * @param keyForQuestion A provider that maps questionId -> DraftKey.
     *
     * @return Immutable snapshot logs suitable for ReviewScreen / Export.
     */
    fun buildLogs(
        defs: List<ReviewQuestionDef>,
        draftStore: ChatDrafts.ChatDraftStore,
        keyForQuestion: (questionId: String) -> ChatDrafts.DraftKey
    ): List<ReviewQuestionLog> {
        if (defs.isEmpty()) return emptyList()

        return defs.map { def ->
            val qid = def.questionId.trim()
            val pr = def.prompt

            val key = keyForQuestion(qid)
            val draft = draftStore.load(key)

            if (draft == null) {
                ReviewQuestionLog.of(
                    questionId = qid,
                    prompt = pr,
                    isSkipped = true,
                    completionPayload = buildMetaPayload(
                        kind = PayloadKind.SKIPPED_NO_DRAFT,
                        questionId = qid,
                        promptHash = key.promptHash
                    ),
                    lines = emptyList()
                )
            } else {
                val lines = draft.messages.toReviewLines(
                    questionId = qid,
                    prompt = pr
                )

                val hasUser = lines.any { it.kind.canonical() == ReviewChatKind.USER }

                val completionRaw = draft.completionPayload?.trim().orEmpty()
                val completion = when {
                    completionRaw.isNotEmpty() -> completionRaw
                    hasUser -> buildMetaPayload(
                        kind = PayloadKind.DRAFT_NO_COMPLETION,
                        questionId = qid,
                        promptHash = key.promptHash
                    )

                    else -> buildMetaPayload(
                        kind = PayloadKind.SKIPPED_NO_COMPLETION,
                        questionId = qid,
                        promptHash = key.promptHash
                    )
                }

                val isSkipped = (!hasUser) && completionRaw.isBlank()

                ReviewQuestionLog.of(
                    questionId = qid,
                    prompt = pr,
                    isSkipped = isSkipped,
                    completionPayload = completion,
                    lines = lines
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // Transcript conversion
    // ---------------------------------------------------------------------

    /**
     * Convert chat draft messages into review transcript lines.
     *
     * Mapping policy:
     * - USER -> USER
     * - ASSISTANT role:
     *   - assistantMessage -> ASSESSMENT
     *   - followUpQuestion -> FOLLOW_UP
     *   - embedded streamText -> DEBUG_RAW
     * - MODEL role (streaming bubble) -> DEBUG_RAW
     *
     * De-dup policy:
     * - If any ASSISTANT-role message contains an embedded model output (streamText),
     *   MODEL-role streaming bubbles are treated as transient and ignored.
     * - DEBUG_RAW lines are de-duplicated by SHA-256(content) to avoid double counting.
     *
     * Seed filtering:
     * - Drop the initial seed message from ChatQuestionViewModel:
     *   assistantMessage = "Question: <qid>"
     *   followUpQuestion = "<prompt>"
     *   before any USER line exists.
     */
    private fun List<ChatModels.ChatMessage>.toReviewLines(
        questionId: String,
        prompt: String
    ): List<ReviewChatLine> {
        if (isEmpty()) return emptyList()

        /**
         * Prefer embedded model output on ASSISTANT messages over transient MODEL bubbles.
         */
        val hasEmbeddedModelOutput =
            any { it.role == ChatModels.ChatRole.ASSISTANT && !it.streamText.isNullOrBlank() }

        val out = ArrayList<ReviewChatLine>(size * 2)

        /**
         * De-duplicate DEBUG_RAW lines by content signature to reduce double counting.
         */
        val seenDebugRaw = HashSet<String>(8)

        var seenUser = false
        val promptTrimmed = prompt.trim()

        fun addLine(kind: ReviewChatKind, text: String) {
            val canonicalKind = kind.canonical()
            val t = text.trim()
            if (t.isEmpty()) return

            if (canonicalKind == ReviewChatKind.DEBUG_RAW) {
                val sig = sha256Hex(t)
                if (!seenDebugRaw.add(sig)) return
            }

            out += ReviewChatLine.of(kind = canonicalKind, text = t)
        }

        for (m in this) {
            when (m.role) {
                ChatModels.ChatRole.USER -> {
                    val t = m.text.trim()
                    if (t.isNotEmpty()) {
                        addLine(ReviewChatKind.USER, t)
                        seenUser = true
                    }
                }

                ChatModels.ChatRole.ASSISTANT -> {
                    val assessment = m.assistantMessage?.trim().orEmpty()
                    val followUp = normalizeFollowUp(m.followUpQuestion)

                    /**
                     * Drop the initial seed prompt bubble to avoid duplication in Review.
                     */
                    val isSeedPrompt =
                        !seenUser &&
                                assessment == "Question: $questionId" &&
                                (followUp?.trim().orEmpty() == promptTrimmed)

                    if (isSeedPrompt) {
                        continue
                    }

                    if (assessment.isNotEmpty()) {
                        addLine(ReviewChatKind.ASSESSMENT, assessment)
                    }

                    if (!followUp.isNullOrBlank() && followUp.trim() != promptTrimmed) {
                        addLine(ReviewChatKind.FOLLOW_UP, followUp)
                    }

                    val embedded = m.streamText?.trim().orEmpty()
                    if (embedded.isNotEmpty()) {
                        addLine(ReviewChatKind.DEBUG_RAW, embedded)
                    }
                }

                ChatModels.ChatRole.MODEL -> {
                    if (hasEmbeddedModelOutput) {
                        // Skip transient streaming bubbles when an embedded result exists.
                        continue
                    }

                    val raw = (m.streamText?.takeIf { it.isNotBlank() } ?: m.text).trim()
                    if (raw.isNotEmpty()) {
                        addLine(ReviewChatKind.DEBUG_RAW, raw)
                    }
                }
            }
        }

        return out
    }

    // ---------------------------------------------------------------------
    // Follow-up normalization
    // ---------------------------------------------------------------------

    /**
     * Normalize a follow-up question and drop garbage values.
     *
     * Notes:
     * - Uses Locale.US for deterministic case folding.
     * - Removes common wrapper prefixes produced by prompting layers.
     */
    private fun normalizeFollowUp(text: String?): String? {
        val raw = text ?: return null
        var t = raw.replace("\u0000", "").trim()
        if (t.isBlank()) return null

        val prefixPatterns = listOf(
            "follow-up question:",
            "follow up question:",
            "follow-up:",
            "follow up:",
            "followup:",
            "question:",
            "next question:"
        )

        val lower0 = t.lowercase(Locale.US)
        for (p in prefixPatterns) {
            if (lower0.startsWith(p)) {
                t = t.drop(p.length).trim()
                break
            }
        }

        val l2 = t.lowercase(Locale.US)
        val garbage = setOf(
            "none",
            "(none)",
            "n/a",
            "na",
            "null",
            "nil",
            "no",
            "nope",
            "no follow up",
            "no follow-up",
            "skip",
            "0",
            "-"
        )
        if (t.isBlank()) return null
        if (l2 in garbage) return null
        if (t.length < 3) return null

        return t
    }

    // ---------------------------------------------------------------------
    // Payload helpers
    // ---------------------------------------------------------------------

    private enum class PayloadKind {
        SKIPPED_NO_DRAFT,
        SKIPPED_NO_COMPLETION,
        DRAFT_NO_COMPLETION
    }

    /**
     * Build a non-PII payload fallback.
     *
     * Notes:
     * - Safe to export and log.
     * - Must not include raw user answers.
     */
    private fun buildMetaPayload(kind: PayloadKind, questionId: String, promptHash: Int): String {
        return buildString {
            append("[")
            append(
                when (kind) {
                    PayloadKind.SKIPPED_NO_DRAFT -> "SKIPPED"
                    PayloadKind.SKIPPED_NO_COMPLETION -> "SKIPPED"
                    PayloadKind.DRAFT_NO_COMPLETION -> "DRAFT"
                }
            )
            append("]\n")
            append("QUESTION_ID: ").append(questionId).append('\n')
            append("PROMPT_HASH: ").append(promptHash).append('\n')
            append(
                when (kind) {
                    PayloadKind.SKIPPED_NO_DRAFT -> "REASON: no_draft\n"
                    PayloadKind.SKIPPED_NO_COMPLETION -> "REASON: no_completion\n"
                    PayloadKind.DRAFT_NO_COMPLETION -> "REASON: no_completion_yet\n"
                }
            )
            append("ANSWER: (not included)\n")
        }.trim()
    }

    // ---------------------------------------------------------------------
    // Signatures
    // ---------------------------------------------------------------------

    /**
     * SHA-256 hex used for DEBUG_RAW content de-dup signatures.
     *
     * Notes:
     * - Fast enough for review assembly.
     * - Produces lowercase fixed-width hex.
     */
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