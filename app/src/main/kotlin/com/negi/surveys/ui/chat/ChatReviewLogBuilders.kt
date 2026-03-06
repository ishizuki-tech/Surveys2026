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
 * Logging / privacy notes:
 * - We never log raw user/model text to AppLog.
 * - This builder does put user/model text into [ReviewQuestionLog] for in-app review.
 * - If the resulting log is later persisted or uploaded, the caller must redact or clip further.
 *
 * Protocol notes:
 * - Step-1 is user-facing and mapped to [ReviewChatKind.ASSESSMENT].
 * - Step-2 is user-facing and mapped to [ReviewChatKind.FOLLOW_UP].
 * - Raw/debug model stream is mapped to [ReviewChatKind.DEBUG_RAW].
 */
internal object ChatReviewLogBuilders {

    private const val TAG = "ChatReviewLogBuilders"

    /**
     * Upper bound for any text stored into [ReviewChatLine.text].
     *
     * Notes:
     * - Keeps review snapshots bounded in memory.
     * - The live chat UI may still show the full underlying message text.
     */
    private const val MAX_LINE_CHARS = 12_000

    /**
     * Upper bound for raw model output lines.
     *
     * Notes:
     * - Raw stream output can explode in failure cases.
     * - Keep this larger than [MAX_LINE_CHARS], but still bounded.
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
     * - [String.hashCode] is stable on the JVM but relatively weak for short prompts.
     * - This uses SHA-256 and truncates to 32-bit to stay compatible with existing DraftKey usage.
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

        fun appendLine(
            kind: ReviewChatKind,
            text: String,
            maxChars: Int,
        ) {
            val clipped = clip(text.trim(), maxChars)
            if (clipped.isEmpty()) return
            lines += ReviewChatLine.of(
                kind = kind,
                text = clipped,
            )
        }

        fun appendDebugRawOnce(raw: String) {
            val clipped = clip(raw.trim(), MAX_MODEL_RAW_CHARS)
            if (clipped.isEmpty()) return

            val last = lines.lastOrNull()
            if (last != null &&
                last.kind.canonical() == ReviewChatKind.DEBUG_RAW &&
                last.text == clipped
            ) {
                return
            }

            lines += ReviewChatLine.of(
                kind = ReviewChatKind.DEBUG_RAW,
                text = clipped,
            )
        }

        for (message in messagesSnapshot) {
            when (message.role) {
                ChatModels.ChatRole.USER -> {
                    val text = message.text.trim()
                    if (text.isNotEmpty()) {
                        appendLine(
                            kind = ReviewChatKind.USER,
                            text = text,
                            maxChars = MAX_LINE_CHARS,
                        )
                        seenUser = true
                    }
                }

                ChatModels.ChatRole.ASSISTANT -> {
                    val assessment = message.assistantMessage?.trim().orEmpty()
                    val fallback = message.text.trim()
                    val followUp = normalizeFollowUp(message.followUpQuestion)

                    /**
                     * Skip the initial seed prompt bubble if present.
                     */
                    val isSeedPrompt =
                        !seenUser &&
                                assessment == "Question: $questionId" &&
                                followUp?.trim().orEmpty() == promptTrimmed

                    if (isSeedPrompt) continue

                    if (isSkipped) {
                        /**
                         * When skipped, keep assistant/debug outputs only after the user answered at least once.
                         */
                        if (!seenUser) continue

                        if (assessment.isNotEmpty()) {
                            appendLine(
                                kind = ReviewChatKind.ASSESSMENT,
                                text = assessment,
                                maxChars = MAX_LINE_CHARS,
                            )
                        } else if (fallback.isNotEmpty()) {
                            appendLine(
                                kind = ReviewChatKind.ASSESSMENT,
                                text = fallback,
                                maxChars = MAX_LINE_CHARS,
                            )
                        }

                        val followUpText = followUp?.trim()
                        if (!followUpText.isNullOrBlank() && followUpText != promptTrimmed) {
                            appendLine(
                                kind = ReviewChatKind.FOLLOW_UP,
                                text = followUpText,
                                maxChars = MAX_LINE_CHARS,
                            )
                        }

                        val raw = message.streamText?.trim().orEmpty()
                        if (raw.isNotEmpty() && message.streamState != ChatModels.ChatStreamState.NONE) {
                            appendDebugRawOnce(raw)
                        }
                        continue
                    }

                    if (assessment.isNotEmpty()) {
                        appendLine(
                            kind = ReviewChatKind.ASSESSMENT,
                            text = assessment,
                            maxChars = MAX_LINE_CHARS,
                        )
                    } else if (fallback.isNotEmpty()) {
                        appendLine(
                            kind = ReviewChatKind.ASSESSMENT,
                            text = fallback,
                            maxChars = MAX_LINE_CHARS,
                        )
                    }

                    val followUpText = followUp?.trim()
                    if (!followUpText.isNullOrBlank() && followUpText != promptTrimmed) {
                        appendLine(
                            kind = ReviewChatKind.FOLLOW_UP,
                            text = followUpText,
                            maxChars = MAX_LINE_CHARS,
                        )
                    }

                    val raw = message.streamText?.trim().orEmpty()
                    if (raw.isNotEmpty() && message.streamState != ChatModels.ChatStreamState.NONE) {
                        appendDebugRawOnce(raw)
                    }
                }

                ChatModels.ChatRole.MODEL -> {
                    if (isSkipped && !seenUser) continue

                    val raw = (message.streamText?.takeIf { it.isNotBlank() } ?: message.text).trim()
                    if (raw.isNotEmpty()) {
                        appendDebugRawOnce(raw)
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

    /**
     * Normalize a follow-up question and remove garbage values.
     *
     * Notes:
     * - Uses Locale.US to avoid locale-dependent case folding surprises.
     * - Removes common wrapper prefixes produced by prompting layers.
     */
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

    /**
     * Clip oversized text to a stable bounded representation.
     *
     * Notes:
     * - Appends a marker so the review surface makes truncation visible.
     */
    private fun clip(
        text: String,
        maxChars: Int,
    ): String {
        if (text.length <= maxChars) return text
        val keep = min(maxChars, text.length)
        return text.take(keep) + "…[TRUNCATED]"
    }
}