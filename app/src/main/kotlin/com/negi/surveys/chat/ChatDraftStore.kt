/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Draft Store)
 *  ---------------------------------------------------------------------
 *  File: ChatDraftStore.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * Stable key for per-question chat draft state.
 *
 * Notes:
 * - promptHash is included so that changing prompt text can invalidate old drafts safely.
 * - Use the same hash you use for ViewModel keys.
 */
data class DraftKey(
    val questionId: String,
    val promptHash: Int
)

/**
 * Persisted chat state for "Next -> Back -> Re-answer" support.
 *
 * Privacy:
 * - This draft contains user answers and assistant messages.
 * - Keep it in-memory or encrypt if you persist to disk.
 */
data class ChatDraft(
    val stage: ChatStage,
    val messages: List<ChatMessage>,
    val mainAnswer: String,
    val followUps: List<FollowUpTurn>,
    val currentFollowUpQuestion: String,
    val completionPayload: String?
)

/** Chat state machine stage exposed for draft persistence. */
enum class ChatStage {
    AWAIT_MAIN,
    AWAIT_FOLLOW_UP,
    DONE
}

/**
 * Represents one follow-up cycle: assistant question -> user answer.
 *
 * Notes:
 * - Stored to support multiple follow-up turns per survey question.
 */
data class FollowUpTurn(
    val question: String,
    val answer: String
)

/**
 * Minimal draft store abstraction.
 *
 * SmallStep:
 * - Start with in-memory store.
 * - Later swap to disk/persistence if needed.
 */
interface ChatDraftStore {
    fun load(key: DraftKey): ChatDraft?
    fun save(key: DraftKey, draft: ChatDraft)
    fun clear(key: DraftKey)
}

/**
 * Simple in-memory implementation.
 *
 * Notes:
 * - Lives as long as the process lives.
 * - Must be owned by a long-lived parent (AppRoot/Navigation VM) to be useful.
 */
class InMemoryChatDraftStore : ChatDraftStore {
    private val map = ConcurrentHashMap<DraftKey, ChatDraft>()

    override fun load(key: DraftKey): ChatDraft? = map[key]

    override fun save(key: DraftKey, draft: ChatDraft) {
        map[key] = draft
    }

    override fun clear(key: DraftKey) {
        map.remove(key)
    }
}
