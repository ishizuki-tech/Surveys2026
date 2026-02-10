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
 * - [promptHash] is included so that changing prompt text can invalidate old drafts safely.
 * - Normalize [questionId] to avoid accidental key mismatches (e.g., "Q1" vs " Q1 ").
 */
data class DraftKey(
    val questionId: String,
    val promptHash: Int
) {
    /** Returns a normalized copy safe for map keys. */
    fun normalized(): DraftKey = copy(questionId = questionId.trim())
}

/**
 * Persisted chat state for "Next -> Back -> Re-answer" support.
 *
 * Privacy:
 * - This draft contains user answers and assistant/model messages.
 * - Keep it in-memory or encrypt if you persist to disk.
 *
 * Backward-compatibility note:
 * - New fields must be appended with defaults to keep call sites compiling.
 */
data class ChatDraft(
    val stage: ChatStage,
    val messages: List<ChatMessage>,
    val mainAnswer: String,
    val followUps: List<FollowUpTurn>,
    val currentFollowUpQuestion: String,
    val completionPayload: String?,
    val inputDraft: String = "",
    val version: Int = 1
) {
    /**
     * Returns a defensively-copied version so external mutable lists cannot mutate stored drafts.
     */
    fun freeze(): ChatDraft {
        return copy(
            messages = messages.toList(),
            followUps = followUps.toList()
        )
    }
}

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
 * - Applies optional caps to prevent unbounded growth (messages, follow-ups, large strings).
 */
class InMemoryChatDraftStore(
    private val config: Config = Config()
) : ChatDraftStore {

    /**
     * Memory safety knobs.
     *
     * Notes:
     * - Defaults are intentionally conservative.
     * - Tweak as needed for your ReviewScreen policy.
     */
    data class Config(
        val maxMessages: Int = 250,
        val maxFollowUps: Int = 24,
        val maxMainAnswerChars: Int = 32_000,
        val maxFollowUpQuestionChars: Int = 8_000,
        val maxCompletionPayloadChars: Int = 96_000,
        val maxInputDraftChars: Int = 16_000
    )

    private val map = ConcurrentHashMap<DraftKey, ChatDraft>()

    override fun load(key: DraftKey): ChatDraft? {
        return map[key.normalized()]
    }

    override fun save(key: DraftKey, draft: ChatDraft) {
        val k = key.normalized()
        map[k] = draft
            .freeze()
            .applyCaps(config)
    }

    override fun clear(key: DraftKey) {
        map.remove(key.normalized())
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    /**
     * Apply size caps to reduce the risk of unbounded memory growth.
     *
     * Important:
     * - This does NOT attempt to redact sensitive data; it only bounds size.
     * - If you need redaction, do it at the ViewModel layer based on UI policy.
     */
    private fun ChatDraft.applyCaps(cfg: Config): ChatDraft {
        val msgCap = cfg.maxMessages.coerceAtLeast(0)
        val fuCap = cfg.maxFollowUps.coerceAtLeast(0)

        val trimmedMessages = if (msgCap == 0) emptyList() else messages.takeLast(msgCap)
        val trimmedFollowUps = if (fuCap == 0) emptyList() else followUps.takeLast(fuCap)

        return copy(
            messages = trimmedMessages.toList(),
            followUps = trimmedFollowUps.toList(),
            mainAnswer = mainAnswer.takeSafe(cfg.maxMainAnswerChars),
            currentFollowUpQuestion = currentFollowUpQuestion.takeSafe(cfg.maxFollowUpQuestionChars),
            completionPayload = completionPayload?.takeSafe(cfg.maxCompletionPayloadChars),
            inputDraft = inputDraft.takeSafe(cfg.maxInputDraftChars)
        )
    }

    /** Safe substring helper that tolerates non-positive limits. */
    private fun String.takeSafe(limit: Int): String {
        val n = limit.coerceAtLeast(0)
        if (n == 0) return ""
        return if (length <= n) this else substring(0, n)
    }
}
