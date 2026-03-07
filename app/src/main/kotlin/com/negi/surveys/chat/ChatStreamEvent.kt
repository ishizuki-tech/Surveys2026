/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Stream Events)
 *  ---------------------------------------------------------------------
 *  File: ChatStreamEvent.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

/**
 * Streaming events for validation/model output.
 *
 * Design:
 * - Streaming remains raw text oriented.
 * - Structured final state is delivered separately via ValidationOutcome / ChatMessage.
 * - Phase tagging allows the UI to distinguish step 1 from step 2 while streaming.
 */
sealed interface ChatStreamEvent {

    /**
     * The active streaming phase.
     */
    enum class Phase {
        STEP1_EVAL,
        STEP2_FOLLOW_UP,
    }

    data class Begin(
        val sessionId: Long,
        val phase: Phase,
    ) : ChatStreamEvent

    data class Delta(
        val sessionId: Long,
        val phase: Phase,
        val text: String,
    ) : ChatStreamEvent

    data class End(
        val sessionId: Long,
        val phase: Phase,
    ) : ChatStreamEvent

    data class Error(
        val sessionId: Long,
        val phase: Phase,
        val token: String,
        val code: String? = null,
    ) : ChatStreamEvent

    /**
     * Stable machine-readable tokens used across bridge / VM / repository.
     */
    object Codes {
        const val CANCELLED: String = "cancelled"
        const val REPLACED: String = "replaced"
        const val TIMEOUT: String = "timeout"
        const val ERROR: String = "error"
    }
}