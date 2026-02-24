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

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Stable key for per-question chat draft state.
 *
 * Notes:
 * - [promptHash] is included so that changing prompt text can invalidate old drafts safely.
 * - Normalize [questionId] to avoid accidental key mismatches (e.g., "Q1" vs " q1 ").
 */
data class DraftKey(
    val questionId: String,
    val promptHash: Int
) {
    /** Returns a normalized copy safe for map keys. */
    fun normalized(): DraftKey = copy(questionId = normalizeQuestionId(questionId))

    private fun normalizeQuestionId(id: String): String {
        // NOTE:
        // - Align with validator-style normalization: trim + uppercase.
        // - If you intentionally use case-sensitive IDs, remove uppercase.
        return id.trim().uppercase(Locale.US)
    }
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
     *
     * Notes:
     * - This is a shallow snapshot: element objects are not deep-copied.
     * - Keep ChatMessage immutable to preserve safety guarantees.
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
 * - Also caps the number of keys to avoid unbounded draft accumulation across prompt changes.
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
        /** Max number of draft keys to keep in memory. Oldest keys are evicted best-effort. */
        val maxKeys: Int = 64,

        val maxMessages: Int = 250,
        val maxFollowUps: Int = 24,

        val maxMainAnswerChars: Int = 32_000,
        val maxFollowUpQuestionChars: Int = 8_000,
        val maxCompletionPayloadChars: Int = 96_000,
        val maxInputDraftChars: Int = 16_000
    )

    private val map = ConcurrentHashMap<DraftKey, ChatDraft>()

    /**
     * Insertion-order queue used for best-effort eviction.
     *
     * Notes:
     * - We may push duplicate keys; eviction checks current map membership.
     * - This keeps implementation simple and thread-friendly.
     */
    private val keyQueue = ConcurrentLinkedQueue<DraftKey>()

    override fun load(key: DraftKey): ChatDraft? {
        val k = key.normalized()
        val v = map[k] ?: return null
        // Return a fresh snapshot to avoid exposing internal list references.
        return v.freeze()
    }

    override fun save(key: DraftKey, draft: ChatDraft) {
        val cfg = normalizedConfig(config)

        val k = key.normalized()
        val capped = draft
            .freeze()
            .applyCaps(cfg)

        map[k] = capped
        keyQueue.add(k)

        evictIfNeeded(cfg)
    }

    override fun clear(key: DraftKey) {
        val k = key.normalized()
        map.remove(k)
        // keyQueue cleanup is best-effort; stale entries will be ignored during eviction.
    }

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private fun normalizedConfig(cfg: Config): Config {
        return cfg.copy(
            maxKeys = cfg.maxKeys.coerceAtLeast(0),
            maxMessages = cfg.maxMessages.coerceAtLeast(0),
            maxFollowUps = cfg.maxFollowUps.coerceAtLeast(0),
            maxMainAnswerChars = cfg.maxMainAnswerChars.coerceAtLeast(0),
            maxFollowUpQuestionChars = cfg.maxFollowUpQuestionChars.coerceAtLeast(0),
            maxCompletionPayloadChars = cfg.maxCompletionPayloadChars.coerceAtLeast(0),
            maxInputDraftChars = cfg.maxInputDraftChars.coerceAtLeast(0)
        )
    }

    /**
     * Apply size caps to reduce the risk of unbounded memory growth.
     *
     * Important:
     * - This does NOT attempt to redact sensitive data; it only bounds size.
     * - If you need redaction, do it at the ViewModel layer based on UI policy.
     */
    private fun ChatDraft.applyCaps(cfg: Config): ChatDraft {
        val trimmedMessages = if (cfg.maxMessages == 0) emptyList() else messages.takeLast(cfg.maxMessages)
        val trimmedFollowUps = if (cfg.maxFollowUps == 0) emptyList() else followUps.takeLast(cfg.maxFollowUps)

        return copy(
            messages = trimmedMessages.toList(),
            followUps = trimmedFollowUps.toList(),
            mainAnswer = mainAnswer.takeSafeChars(cfg.maxMainAnswerChars),
            currentFollowUpQuestion = currentFollowUpQuestion.takeSafeChars(cfg.maxFollowUpQuestionChars),
            completionPayload = completionPayload?.takeSafeChars(cfg.maxCompletionPayloadChars),
            inputDraft = inputDraft.takeSafeChars(cfg.maxInputDraftChars)
        )
    }

    /**
     * Best-effort eviction to cap the number of draft keys.
     *
     * Strategy:
     * - Maintain a queue of keys we have saved.
     * - When map grows beyond maxKeys, poll keys and remove them if still present.
     *
     * Notes:
     * - This is not a perfect LRU, but it is deterministic and cheap.
     */
    private fun evictIfNeeded(cfg: Config) {
        val maxKeys = cfg.maxKeys
        if (maxKeys <= 0) {
            map.clear()
            // Keep queue bounded loosely.
            while (keyQueue.poll() != null) { /* drain */ }
            return
        }

        while (map.size > maxKeys) {
            val victim = keyQueue.poll() ?: break
            // Remove only if it still exists (stale queue entries are harmless).
            map.remove(victim)
        }

        // Optional hygiene: prevent the queue from growing without bound if callers save frequently.
        val hardQueueCap = maxKeys * 6
        while (keyQueue.size > hardQueueCap) {
            keyQueue.poll() ?: break
        }
    }

    /**
     * Safe substring helper that tolerates non-positive limits and avoids surrogate pair splits.
     */
    private fun String.takeSafeChars(limit: Int): String {
        val n = limit.coerceAtLeast(0)
        if (n == 0) return ""
        if (length <= n) return this

        var end = n
        if (end > 0 && end < length) {
            val last = this[end - 1]
            val next = this[end]
            if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
                end -= 1
            }
        }

        if (end <= 0) return ""
        return substring(0, end)
    }
}