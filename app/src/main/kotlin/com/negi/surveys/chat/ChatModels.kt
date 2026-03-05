/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Models)
 *  ---------------------------------------------------------------------
 *  File: ChatModels.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import androidx.compose.runtime.Immutable
import java.util.Locale

/**
 * Namespace object for chat-related models.
 *
 * Goal:
 * - Reduce top-level symbol clutter in the package.
 * - Keep call sites stable: ChatModels.ChatMessage, ChatModels.ChatStreamEvent, etc.
 */
object ChatModels {

    /**
     * Chat message role.
     *
     * - [USER]: End-user input.
     * - [ASSISTANT]: Assistant-facing, user-visible feedback (structured).
     * - [MODEL]: Raw model output or system/model-side content that may be rendered differently.
     */
    enum class ChatRole {
        USER,
        ASSISTANT,
        MODEL
    }

    /**
     * Streaming state for model/assistant output.
     */
    enum class ChatStreamState {
        /** No streaming involved (static message). */
        NONE,

        /** Streaming is currently in progress. */
        STREAMING,

        /** Streaming ended normally. */
        ENDED,

        /** Streaming ended with an error. */
        ERROR
    }

    /**
     * Chat message model for UI rendering.
     *
     * Backward-compatibility note:
     * - The primary constructor keeps (id, role, text) as the first 3 params.
     * - New fields are appended with defaults, so existing call sites keep compiling.
     */
    @Immutable
    data class ChatMessage(
        val id: String,
        val role: ChatRole,
        val text: String,
        val assistantMessage: String? = null,
        val followUpQuestion: String? = null,
        val streamText: String? = null,
        val streamState: ChatStreamState = ChatStreamState.NONE,
        val streamCollapsed: Boolean = false,
        val streamSessionId: Long? = null
    ) {
        /** True when this message is authored by the end user. */
        val isFromUser: Boolean get() = role == ChatRole.USER

        /** True when this message is authored by assistant/model side. */
        val isAssistantSide: Boolean get() = role == ChatRole.ASSISTANT || role == ChatRole.MODEL

        /** True when this message has structured assistant fields. */
        val hasStructuredAssistant: Boolean
            get() = !assistantMessage.isNullOrBlank() || !followUpQuestion.isNullOrBlank()

        /**
         * True when this message includes stream details.
         *
         * Important:
         * - A STREAMING message must be treated as having stream details even if [streamText] is still empty.
         */
        val hasStreamDetails: Boolean
            get() = streamState != ChatStreamState.NONE &&
                    (streamState == ChatStreamState.STREAMING || !streamText.isNullOrBlank())

        /** True when this message is actively streaming. */
        val isStreaming: Boolean get() = streamState == ChatStreamState.STREAMING

        /**
         * A best-effort fallback string to render when the UI does not use structured fields.
         */
        fun fallbackDisplayText(): String {
            val a = assistantMessage?.trim().orEmpty()
            val q = followUpQuestion?.trim().orEmpty()

            if (a.isNotEmpty() || q.isNotEmpty()) {
                return buildString {
                    if (a.isNotEmpty()) append(a)
                    if (q.isNotEmpty()) {
                        if (length > 0) append("\n\n")
                        append(q)
                    }
                }
            }

            val t = text.trim()
            if (t.isNotEmpty()) return t

            val s = streamText?.trim().orEmpty()
            if (s.isNotEmpty()) return s

            return ""
        }

        /**
         * Append a streaming chunk to [streamText] and return a new instance.
         *
         * Notes:
         * - Applies an optional [maxChars] cap to bound memory usage.
         * - Avoids surrogate pair splits when clipping (emoji-safe).
         */
        fun appendStreamChunk(chunk: String, maxChars: Int = Int.MAX_VALUE): ChatMessage {
            if (chunk.isEmpty()) return this

            val cap = maxChars.coerceAtLeast(0)
            if (cap == 0) return this

            val base = (streamText ?: "")
            val remain = cap - base.length
            if (remain <= 0) return this

            var toAdd = ChatModels.clipToRemainPreserveSurrogates(chunk, remain)
            if (toAdd.isEmpty()) return this

            // Boundary guard:
            // If base ends with a high surrogate and toAdd begins with a low surrogate, drop the low surrogate.
            if (base.isNotEmpty() && toAdd.isNotEmpty()) {
                val lastBase = base[base.lastIndex]
                val firstAdd = toAdd[0]
                if (Character.isHighSurrogate(lastBase) && Character.isLowSurrogate(firstAdd)) {
                    toAdd = toAdd.drop(1)
                    if (toAdd.isEmpty()) return this
                }
            }

            // Front guard (rare): avoid starting with a low surrogate if base is empty.
            if (base.isEmpty() && toAdd.isNotEmpty() && Character.isLowSurrogate(toAdd[0])) {
                toAdd = toAdd.drop(1)
                if (toAdd.isEmpty()) return this
            }

            return copy(streamText = base + toAdd)
        }

        /** Mark the stream as ended (normal). */
        fun markStreamEnded(collapsed: Boolean = true): ChatMessage {
            return copy(
                streamState = ChatStreamState.ENDED,
                streamCollapsed = collapsed
            )
        }

        /** Mark the stream as failed (error). */
        fun markStreamError(collapsed: Boolean = true): ChatMessage {
            return copy(
                streamState = ChatStreamState.ERROR,
                streamCollapsed = collapsed
            )
        }

        /**
         * A PII-safe debug summary suitable for logs.
         *
         * Important:
         * - This intentionally does not include message content.
         */
        fun debugSummary(): String {
            val id8 = id.take(8)
            val textLen = text.length
            val aLen = assistantMessage?.length ?: 0
            val qLen = followUpQuestion?.length ?: 0
            val sLen = streamText?.length ?: 0
            return "ChatMessage(id=$id8 role=$role state=$streamState collapsed=$streamCollapsed " +
                    "lens[text=$textLen a=$aLen q=$qLen stream=$sLen] sid=${streamSessionId ?: 0})"
        }

        companion object {

            /** Factory: user message. */
            fun user(id: String, text: String): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.USER,
                    text = text
                )
            }

            /**
             * Factory: assistant message (structured).
             *
             * Notes:
             * - Use [assistantMessage] and [followUpQuestion] for colored/structured UI.
             * - Keep [textFallback] for legacy rendering or searchability if needed.
             */
            fun assistant(
                id: String,
                assistantMessage: String,
                followUpQuestion: String? = null,
                textFallback: String = ""
            ): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.ASSISTANT,
                    text = textFallback,
                    assistantMessage = assistantMessage,
                    followUpQuestion = followUpQuestion,
                    streamText = null,
                    streamState = ChatStreamState.NONE,
                    streamCollapsed = true,
                    streamSessionId = null
                )
            }

            /**
             * Factory: assistant message (structured) with embedded model output details.
             */
            fun assistantWithModelOutput(
                id: String,
                assistantMessage: String,
                followUpQuestion: String? = null,
                modelOutput: String,
                modelState: ChatStreamState = ChatStreamState.ENDED,
                streamCollapsed: Boolean = true,
                textFallback: String = "",
                streamSessionId: Long? = null
            ): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.ASSISTANT,
                    text = textFallback,
                    assistantMessage = assistantMessage,
                    followUpQuestion = followUpQuestion,
                    streamText = modelOutput,
                    streamState = modelState,
                    streamCollapsed = streamCollapsed,
                    streamSessionId = streamSessionId
                )
            }

            /**
             * Factory: streaming model output bubble.
             */
            fun modelStreaming(
                id: String,
                streamSessionId: Long? = null
            ): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.MODEL,
                    text = "",
                    assistantMessage = null,
                    followUpQuestion = null,
                    streamText = "",
                    streamState = ChatStreamState.STREAMING,
                    streamCollapsed = false,
                    streamSessionId = streamSessionId
                )
            }

            /** Factory: completed model output bubble. */
            fun modelEnded(
                id: String,
                output: String,
                collapsed: Boolean = true,
                streamSessionId: Long? = null
            ): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.MODEL,
                    text = "",
                    assistantMessage = null,
                    followUpQuestion = null,
                    streamText = output,
                    streamState = ChatStreamState.ENDED,
                    streamCollapsed = collapsed,
                    streamSessionId = streamSessionId
                )
            }

            /** Factory: model error bubble. */
            fun modelError(
                id: String,
                outputOrError: String,
                collapsed: Boolean = true,
                streamSessionId: Long? = null
            ): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.MODEL,
                    text = "",
                    assistantMessage = null,
                    followUpQuestion = null,
                    streamText = outputOrError,
                    streamState = ChatStreamState.ERROR,
                    streamCollapsed = collapsed,
                    streamSessionId = streamSessionId
                )
            }
        }
    }

    /**
     * Validation status for an answer.
     */
    enum class ValidationStatus {
        /** The current answer is acceptable and the flow can proceed. */
        ACCEPTED,

        /** The current answer is insufficient and requires a follow-up question. */
        NEED_FOLLOW_UP
    }

    /**
     * Validator output.
     */
    @Immutable
    data class ValidationOutcome(
        val status: ValidationStatus,
        val assistantMessage: String,
        val followUpQuestion: String? = null
    ) {
        /** True if this outcome is logically consistent with the recommended contract. */
        fun isConsistent(): Boolean {
            return when (status) {
                ValidationStatus.ACCEPTED -> followUpQuestion == null
                ValidationStatus.NEED_FOLLOW_UP -> !followUpQuestion.isNullOrBlank()
            }
        }

        /**
         * Enforce the recommended contract at runtime.
         *
         * This is intentionally opt-in to avoid surprising crashes in legacy call sites.
         */
        fun requireConsistent(): ValidationOutcome {
            check(isConsistent()) {
                "Invalid ValidationOutcome: status=$status, followUpQuestion=$followUpQuestion"
            }
            return this
        }

        companion object {
            /** Factory for an accepted outcome with no follow-up question. */
            fun accepted(assistantMessage: String): ValidationOutcome {
                return ValidationOutcome(
                    status = ValidationStatus.ACCEPTED,
                    assistantMessage = assistantMessage,
                    followUpQuestion = null
                )
            }

            /** Factory for a follow-up outcome with a required follow-up question. */
            fun needFollowUp(assistantMessage: String, followUpQuestion: String): ValidationOutcome {
                return ValidationOutcome(
                    status = ValidationStatus.NEED_FOLLOW_UP,
                    assistantMessage = assistantMessage,
                    followUpQuestion = followUpQuestion
                )
            }
        }
    }

    /**
     * Clips [chunk] to at most [remain] UTF-16 code units, avoiding surrogate pair splits.
     */
    private fun clipToRemainPreserveSurrogates(chunk: String, remain: Int): String {
        if (remain <= 0) return ""
        if (chunk.length <= remain) return chunk

        var end = remain.coerceAtMost(chunk.length)
        if (end <= 0) return ""

        if (end < chunk.length) {
            val last = chunk[end - 1]
            val next = chunk[end]
            if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
                end -= 1
            }
        }

        if (end <= 0) return ""
        return chunk.take(end)
    }
}