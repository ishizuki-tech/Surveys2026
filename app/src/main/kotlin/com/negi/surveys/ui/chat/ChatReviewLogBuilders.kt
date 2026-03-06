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
import com.negi.surveys.logging.AppLog
import com.negi.surveys.ui.ReviewChatKind
import com.negi.surveys.ui.ReviewChatLine
import com.negi.surveys.ui.ReviewQuestionLog
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min

/**
 * Review log builders for the Chat Question UI.
 *
 * Logging / privacy note:
 * - We never log user/model raw text to AppLog.
 * - This builder *does* put user/model text into [ReviewQuestionLog] for in-app review.
 *   Assumption: This object stays local to the device and is not shipped to analytics.
 *   If it is persisted or uploaded, caller must redact/clip further.
 */
internal object ChatReviewLogBuilders {

    private const val TAG = "ChatReviewLogBuilders"

    /**
     * Upper bound for any text we store into [ReviewChatLine.text] to avoid OOM / huge payloads.
     * Keep this conservative; the UI can still show the full text from the message list.
     */
    private const val MAX_LINE_CHARS = 12_000

    /**
     * Upper bound for raw model output lines (can be very large in failure cases).
     */
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

    /**
     * Stable 32-bit hash for a prompt.
     *
     * Why:
     * - [String.hashCode] is stable in JVM, but it is weak and collision-prone for short prompts.
     * - This uses SHA-256 and truncates to 32-bit to keep compatibility with existing `DraftKey(promptHash:Int)`.
     */
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

        fun appendModelRawOnce(raw: String) {
            val clipped = clip(raw.trim(), MAX_MODEL_RAW_CHARS)
            if (clipped.isEmpty()) return

            val last = lines.lastOrNull()
            if (last != null && last.kind == ReviewChatKind.MODEL_RAW && last.text == clipped) {
                return
            }

            lines += ReviewChatLine(
                kind = ReviewChatKind.MODEL_RAW,
                text = clipped,
            )
        }

        for (message in messagesSnapshot) {
            when (message.role) {
                ChatModels.ChatRole.USER -> {
                    val text = clip(message.text.trim(), MAX_LINE_CHARS)
                    if (text.isNotEmpty()) {
                        lines += ReviewChatLine(
                            kind = ReviewChatKind.USER,
                            text = text,
                        )
                        seenUser = true
                    }
                }

                ChatModels.ChatRole.ASSISTANT -> {
                    val assistant = clip(message.assistantMessage?.trim().orEmpty(), MAX_LINE_CHARS)
                    val fallback = clip(message.text.trim(), MAX_LINE_CHARS)
                    val followUp = normalizeFollowUp(message.followUpQuestion)

                    /**
                     * Skip the initial seed prompt bubble if present.
                     */
                    val isSeedPrompt =
                        !seenUser &&
                                assistant == "Question: $questionId" &&
                                followUp?.trim().orEmpty() == promptTrimmed

                    if (isSeedPrompt) continue

                    if (isSkipped) {
                        /**
                         * When skipped, keep assistant/model outputs only after the user answered at least once.
                         */
                        if (!seenUser) continue

                        if (assistant.isNotEmpty()) {
                            lines += ReviewChatLine(
                                kind = ReviewChatKind.AI,
                                text = assistant,
                            )
                        } else if (fallback.isNotEmpty()) {
                            lines += ReviewChatLine(
                                kind = ReviewChatKind.AI,
                                text = fallback,
                            )
                        }

                        val followUpText = followUp?.trim()
                        if (!followUpText.isNullOrBlank() && followUpText != promptTrimmed) {
                            lines += ReviewChatLine(
                                kind = ReviewChatKind.FOLLOW_UP,
                                text = clip(followUpText, MAX_LINE_CHARS),
                            )
                        }

                        val raw = message.streamText?.trim().orEmpty()
                        if (raw.isNotEmpty() && message.streamState != ChatModels.ChatStreamState.NONE) {
                            appendModelRawOnce(raw)
                        }
                        continue
                    }

                    if (assistant.isNotEmpty()) {
                        lines += ReviewChatLine(
                            kind = ReviewChatKind.AI,
                            text = assistant,
                        )
                    } else if (fallback.isNotEmpty()) {
                        lines += ReviewChatLine(
                            kind = ReviewChatKind.AI,
                            text = fallback,
                        )
                    }

                    val followUpText = followUp?.trim()
                    if (!followUpText.isNullOrBlank() && followUpText != promptTrimmed) {
                        lines += ReviewChatLine(
                            kind = ReviewChatKind.FOLLOW_UP,
                            text = clip(followUpText, MAX_LINE_CHARS),
                        )
                    }

                    val raw = message.streamText?.trim().orEmpty()
                    if (raw.isNotEmpty() && message.streamState != ChatModels.ChatStreamState.NONE) {
                        appendModelRawOnce(raw)
                    }
                }

                ChatModels.ChatRole.MODEL -> {
                    if (isSkipped && !seenUser) continue

                    val raw = (message.streamText?.takeIf { it.isNotBlank() } ?: message.text).trim()
                    if (raw.isNotEmpty()) {
                        appendModelRawOnce(raw)
                    }
                }
            }
        }

        AppLog.d(
            TAG,
            "buildReviewQuestionLog: qid=$questionId skipped=$isSkipped lines=${lines.size} payloadLen=${completionPayload.length}",
        )

        return ReviewQuestionLog(
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

    private fun clip(
        text: String,
        maxChars: Int,
    ): String {
        if (text.length <= maxChars) return text
        val keep = min(maxChars, text.length)
        return text.take(keep) + "…[TRUNCATED]"
    }
}