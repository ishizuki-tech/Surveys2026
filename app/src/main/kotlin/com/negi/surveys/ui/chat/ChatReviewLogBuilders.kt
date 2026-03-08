/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Question UI)
 *  ---------------------------------------------------------------------
 *  File: ChatReviewLogBuilders.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui.chat

import com.negi.surveys.chat.ChatModels
import com.negi.surveys.chat.ChatModels.ModelPhase
import com.negi.surveys.logging.AppLog
import com.negi.surveys.ui.ReviewChatKind
import com.negi.surveys.ui.ReviewChatLine
import com.negi.surveys.ui.ReviewQuestionLog
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min
import org.json.JSONObject

/**
 * Review log builders for the Chat Question UI.
 *
 * Logging / privacy notes:
 * - We never log raw user/model text to AppLog.
 * - This builder does put user/model text into [ReviewQuestionLog] for in-app review.
 * - If the resulting log is later persisted or uploaded, the caller must redact or clip further.
 *
 * Protocol notes:
 * - USER remains [ReviewChatKind.USER].
 * - Final step-1 MODEL JSON is summarized as [ReviewChatKind.ASSESSMENT].
 * - User-facing follow-up remains [ReviewChatKind.FOLLOW_UP].
 * - Raw/debug model JSON is additionally kept as [ReviewChatKind.DEBUG_RAW].
 */
internal object ChatReviewLogBuilders {

    private const val TAG = "ChatReviewLogBuilders"

    private const val MAX_LINE_CHARS = 12_000
    private const val MAX_MODEL_RAW_CHARS = 24_000

    private val FOLLOW_UP_PREFIX_PATTERNS: List<String> = listOf(
        "follow-up question:",
        "follow up question:",
        "follow-up:",
        "follow up:",
        "followup:",
        "question:",
        "next question:",
    )

    private val FOLLOW_UP_GARBAGE: Set<String> = setOf(
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
        "-",
    )

    internal fun stablePromptHash(prompt: String): Int {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(prompt.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes, 0, 4).int
    }

    internal fun buildSkippedPayload(
        questionId: String,
        prompt: String,
    ): String {
        val promptHash = stablePromptHash(prompt)
        return buildString {
            append("[SKIPPED]\n")
            append("QUESTION_ID: ").append(questionId).append('\n')
            append("PROMPT_HASH: ").append(promptHash).append('\n')
            append("ANSWER: (none)\n")
        }.trim()
    }

    internal fun buildReviewQuestionLog(
        questionId: String,
        prompt: String,
        isSkipped: Boolean,
        completionPayload: String,
        messagesSnapshot: List<ChatModels.ChatMessage>,
    ): ReviewQuestionLog {
        val lines = ArrayList<ReviewChatLine>(messagesSnapshot.size * 2)

        val promptTrimmed = prompt.trim()
        var seenUser = false

        fun appendLineOnce(
            kind: ReviewChatKind,
            text: String,
            maxChars: Int,
        ) {
            val clipped = clip(text.trim(), maxChars)
            if (clipped.isEmpty()) return

            val last = lines.lastOrNull()
            if (last != null && last.kind.canonical() == kind.canonical() && last.text == clipped) {
                return
            }

            lines += ReviewChatLine.of(
                kind = kind,
                text = clipped,
            )
        }

        fun appendDebugRawOnce(raw: String) {
            appendLineOnce(
                kind = ReviewChatKind.DEBUG_RAW,
                text = raw,
                maxChars = MAX_MODEL_RAW_CHARS,
            )
        }

        for (message in messagesSnapshot) {
            when (message.role) {
                ChatModels.ChatRole.USER -> {
                    val text = message.text.trim()
                    if (text.isNotEmpty()) {
                        appendLineOnce(
                            kind = ReviewChatKind.USER,
                            text = text,
                            maxChars = MAX_LINE_CHARS,
                        )
                        seenUser = true
                    }
                }

                ChatModels.ChatRole.ASSISTANT -> {
                    if (isSkipped && !seenUser) continue

                    val assistant = message.assistantMessage?.trim().orEmpty()
                    val fallback = message.text.trim()
                    val followUp = normalizeFollowUp(message.followUpQuestion)

                    val isSeedPrompt =
                        !seenUser &&
                                assistant == "Question: $questionId" &&
                                followUp?.trim().orEmpty() == promptTrimmed

                    if (isSeedPrompt) continue

                    if (assistant.isNotEmpty()) {
                        appendLineOnce(
                            kind = ReviewChatKind.ASSISTANT,
                            text = assistant,
                            maxChars = MAX_LINE_CHARS,
                        )
                    } else if (fallback.isNotEmpty() && followUp.isNullOrBlank()) {
                        appendLineOnce(
                            kind = ReviewChatKind.ASSISTANT,
                            text = fallback,
                            maxChars = MAX_LINE_CHARS,
                        )
                    }

                    val followUpText = followUp?.trim()
                    if (!followUpText.isNullOrBlank() && followUpText != promptTrimmed) {
                        appendLineOnce(
                            kind = ReviewChatKind.FOLLOW_UP,
                            text = followUpText,
                            maxChars = MAX_LINE_CHARS,
                        )
                    }
                }

                ChatModels.ChatRole.MODEL -> {
                    if (isSkipped && !seenUser) continue

                    val raw = (message.streamText?.takeIf { it.isNotBlank() } ?: message.text).trim()
                    if (raw.isEmpty()) continue

                    when (message.modelPhase) {
                        ModelPhase.STEP1_EVAL -> {
                            appendLineOnce(
                                kind = ReviewChatKind.ASSESSMENT,
                                text = summarizeStep1(raw),
                                maxChars = MAX_LINE_CHARS,
                            )
                            appendDebugRawOnce(raw)
                        }

                        ModelPhase.STEP2_FOLLOW_UP -> {
                            val verdict = ChatBubbleParsing.extractLatestVerdictBestEffort(raw)
                            val modelFollowUp = normalizeFollowUp(verdict?.followUpQuestion)
                            if (!modelFollowUp.isNullOrBlank() && modelFollowUp != promptTrimmed) {
                                appendLineOnce(
                                    kind = ReviewChatKind.FOLLOW_UP,
                                    text = modelFollowUp,
                                    maxChars = MAX_LINE_CHARS,
                                )
                            }
                            appendDebugRawOnce(raw)
                        }

                        null -> {
                            appendDebugRawOnce(raw)
                        }
                    }
                }
            }
        }

        AppLog.d(
            TAG,
            "buildReviewQuestionLog: qid=$questionId skipped=$isSkipped lines=${lines.size} payloadLen=${completionPayload.length}",
        )

        return ReviewQuestionLog.of(
            questionId = questionId,
            prompt = prompt,
            isSkipped = isSkipped,
            completionPayload = completionPayload,
            lines = lines,
        )
    }

    internal fun normalizeFollowUp(text: String?): String? {
        val raw = text ?: return null
        var normalized = raw.trim().replace("\u0000", "")
        if (normalized.isBlank()) return null

        val lower = normalized.lowercase(Locale.US)
        for (prefix in FOLLOW_UP_PREFIX_PATTERNS) {
            if (lower.startsWith(prefix)) {
                normalized = normalized.drop(prefix.length).trim()
                break
            }
        }

        if (normalized.isBlank()) return null

        val folded = normalized.lowercase(Locale.US)
        if (folded in FOLLOW_UP_GARBAGE) return null
        if (normalized.length < 3) return null

        return normalized
    }

    private fun summarizeStep1(raw: String): String {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) {
            return clip(raw, MAX_LINE_CHARS)
        }

        val status = obj.optString("status", "").trim().takeIf { it.isNotEmpty() }
        val score = when (val s = obj.opt("score")) {
            is Number -> s.toInt()
            is String -> s.trim().toIntOrNull()
            else -> null
        }
        val reason = obj.optString("reason", "").trim().takeIf { it.isNotEmpty() }

        if (status == null && score == null && reason == null) {
            return clip(raw, MAX_LINE_CHARS)
        }

        return buildString {
            if (status != null) append(status)
            if (score != null) {
                if (isNotEmpty()) append(" ")
                append("(").append(score).append(")")
            }
            if (reason != null) {
                if (isNotEmpty()) append(" — ")
                append(reason)
            }
        }.ifBlank { clip(raw, MAX_LINE_CHARS) }
    }

    private fun clip(
        text: String,
        maxChars: Int,
    ): String {
        if (text.length <= maxChars) return text
        val keep = min(maxChars, text.length)
        return text.take(keep) + "…[TRUNCATED]"
    }
}
