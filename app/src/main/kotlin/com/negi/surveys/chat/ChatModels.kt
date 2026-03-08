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

/**
 * Namespace object for chat-related models.
 */
object ChatModels {

    enum class ChatRole {
        USER,
        ASSISTANT,
        MODEL,
    }

    /**
     * Distinguishes the two structured model phases.
     *
     * Notes:
     * - STEP1_EVAL is the score / status assessment phase.
     * - STEP2_FOLLOW_UP is the follow-up generation / verdict phase.
     */
    enum class ModelPhase {
        STEP1_EVAL,
        STEP2_FOLLOW_UP,
    }

    enum class ChatStreamState {
        NONE,
        STREAMING,
        ENDED,
        ERROR,
    }

    enum class ValidationStatus {
        ACCEPTED,
        NEED_FOLLOW_UP,
    }

    @Immutable
    data class ChatMessage(
        val id: String,
        val role: ChatRole,
        val text: String,
        val modelPhase: ModelPhase? = null,
        val assistantMessage: String? = null,
        val followUpQuestion: String? = null,

        /**
         * Ephemeral stream text for live MODEL bubbles.
         *
         * Notes:
         * - Final stored MODEL messages should prefer [text] and keep this null.
         * - Persisted drafts must not keep transient stream text.
         */
        val streamText: String? = null,
        val streamState: ChatStreamState = ChatStreamState.NONE,
        val streamCollapsed: Boolean = false,
        val streamSessionId: Long? = null,

        /**
         * Legacy compatibility fields kept for migration of older drafts.
         *
         * Notes:
         * - New code should store step-1 / step-2 raw JSON as MODEL messages.
         * - These fields may still appear in restored drafts from older app versions.
         */
        val evalStatus: ValidationStatus? = null,
        val evalScore: Int? = null,
        val evalReason: String? = null,
        val step1Raw: String? = null,
        val step2Raw: String? = null,
    ) {
        val isFromUser: Boolean get() = role == ChatRole.USER
        val isAssistantSide: Boolean get() = role == ChatRole.ASSISTANT || role == ChatRole.MODEL
        val isStreaming: Boolean get() = streamState == ChatStreamState.STREAMING

        val hasEvalMeta: Boolean
            get() = evalStatus != null || evalScore != null || !evalReason.isNullOrBlank()

        val hasLegacyStepDetails: Boolean
            get() = !step1Raw.isNullOrBlank() || !step2Raw.isNullOrBlank()

        val isStableModelMessage: Boolean
            get() = role == ChatRole.MODEL &&
                    modelPhase != null &&
                    streamState == ChatStreamState.NONE &&
                    streamText.isNullOrBlank() &&
                    streamSessionId == null

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

        fun markStreamEnded(collapsed: Boolean = true): ChatMessage {
            return copy(
                streamState = ChatStreamState.ENDED,
                streamCollapsed = collapsed,
            )
        }

        fun markStreamError(collapsed: Boolean = true): ChatMessage {
            return copy(
                streamState = ChatStreamState.ERROR,
                streamCollapsed = collapsed,
            )
        }

        fun debugSummary(): String {
            val id8 = id.take(8)
            val textLen = text.length
            val aLen = assistantMessage?.length ?: 0
            val qLen = followUpQuestion?.length ?: 0
            val sLen = streamText?.length ?: 0
            val eLen = evalReason?.length ?: 0
            val r1 = step1Raw?.length ?: 0
            val r2 = step2Raw?.length ?: 0
            val es = evalStatus?.name ?: "-"
            val sc = evalScore ?: -1
            val phase = modelPhase?.name ?: "-"
            return "ChatMessage(id=$id8 role=$role phase=$phase state=$streamState collapsed=$streamCollapsed " +
                    "lens[text=$textLen a=$aLen q=$qLen stream=$sLen evalReason=$eLen step1=$r1 step2=$r2] " +
                    "eval[$es,$sc] sid=${streamSessionId ?: 0})"
        }

        companion object {
            fun user(id: String, text: String): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.USER,
                    text = text,
                )
            }

            fun assistant(
                id: String,
                assistantMessage: String,
                followUpQuestion: String? = null,
                textFallback: String = "",
                evalStatus: ValidationStatus? = null,
                evalScore: Int? = null,
                evalReason: String? = null,
                step1Raw: String? = null,
                step2Raw: String? = null,
            ): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.ASSISTANT,
                    text = textFallback,
                    modelPhase = null,
                    assistantMessage = assistantMessage,
                    followUpQuestion = followUpQuestion,
                    streamText = null,
                    streamState = ChatStreamState.NONE,
                    streamCollapsed = true,
                    streamSessionId = null,
                    evalStatus = evalStatus,
                    evalScore = evalScore,
                    evalReason = evalReason,
                    step1Raw = step1Raw,
                    step2Raw = step2Raw,
                )
            }

            fun modelStreaming(
                id: String,
                phase: ModelPhase,
                text: String = "",
                streamSessionId: Long? = null,
            ): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.MODEL,
                    text = text,
                    modelPhase = phase,
                    streamText = text,
                    streamState = ChatStreamState.STREAMING,
                    streamCollapsed = false,
                    streamSessionId = streamSessionId,
                )
            }

            fun modelFinal(
                id: String,
                phase: ModelPhase,
                text: String,
            ): ChatMessage {
                return ChatMessage(
                    id = id,
                    role = ChatRole.MODEL,
                    text = text,
                    modelPhase = phase,
                    streamText = null,
                    streamState = ChatStreamState.NONE,
                    streamCollapsed = false,
                    streamSessionId = null,
                )
            }
        }
    }

    @Immutable
    data class ValidationOutcome(
        val status: ValidationStatus,
        val assistantMessage: String,
        val followUpQuestion: String? = null,
        val evalStatus: ValidationStatus? = null,
        val evalScore: Int? = null,
        val evalReason: String? = null,
        val step1Raw: String? = null,
        val step2Raw: String? = null,
    )
}
