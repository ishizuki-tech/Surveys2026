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
import kotlin.math.max

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
 * In-memory implementation with bounded growth and PII-safe debug stats.
 *
 * Key properties:
 * - Deterministic eviction with LRU (least-recently-used) semantics.
 * - Thread-safe via a single lock (simple + predictable).
 * - Size caps for lists and large strings to reduce memory risk.
 *
 * NOTE:
 * - This store does NOT redact content; it only bounds size.
 * - If you need redaction, do it at the ViewModel layer based on UI policy.
 */
class InMemoryChatDraftStore(
    config: Config = Config(),
    private val logger: ((String) -> Unit)? = null
) : ChatDraftStore {

    /**
     * Memory safety knobs.
     *
     * Notes:
     * - Defaults are intentionally conservative.
     * - Use maxKeys=0 to disable storage (save becomes a no-op, load always returns null).
     */
    data class Config(
        /** Max number of draft keys to keep in memory. */
        val maxKeys: Int = 64,

        /** Max number of chat messages stored per draft. */
        val maxMessages: Int = 250,

        /** Max number of follow-up turns stored per draft. */
        val maxFollowUps: Int = 24,

        /** Max chars for the stored main answer. */
        val maxMainAnswerChars: Int = 32_000,

        /** Max chars for the stored current follow-up question (draft-level). */
        val maxCurrentFollowUpQuestionChars: Int = 8_000,

        /** Max chars for the stored completion payload. */
        val maxCompletionPayloadChars: Int = 96_000,

        /** Max chars for the stored input draft. */
        val maxInputDraftChars: Int = 16_000,

        // -----------------------------------------------------------------
        // Per-message caps (to avoid huge streamText bloating memory)
        // -----------------------------------------------------------------

        /** Max chars for ChatMessage.text (fallback). */
        val maxMessageTextChars: Int = 8_000,

        /** Max chars for ChatMessage.assistantMessage. */
        val maxMessageAssistantChars: Int = 8_000,

        /** Max chars for ChatMessage.followUpQuestion. */
        val maxMessageFollowUpQuestionChars: Int = 8_000,

        /** Max chars for ChatMessage.streamText (embedded model output / debug). */
        val maxMessageStreamTextChars: Int = 16_000,

        // -----------------------------------------------------------------
        // Per-follow-up caps
        // -----------------------------------------------------------------

        /** Max chars for FollowUpTurn.question. */
        val maxFollowUpTurnQuestionChars: Int = 2_000,

        /** Max chars for FollowUpTurn.answer. */
        val maxFollowUpTurnAnswerChars: Int = 8_000
    ) {
        /** Normalize config to safe operational values. */
        fun normalized(): Config {
            return copy(
                maxKeys = max(0, maxKeys),
                maxMessages = max(0, maxMessages),
                maxFollowUps = max(0, maxFollowUps),
                maxMainAnswerChars = max(0, maxMainAnswerChars),
                maxCurrentFollowUpQuestionChars = max(0, maxCurrentFollowUpQuestionChars),
                maxCompletionPayloadChars = max(0, maxCompletionPayloadChars),
                maxInputDraftChars = max(0, maxInputDraftChars),
                maxMessageTextChars = max(0, maxMessageTextChars),
                maxMessageAssistantChars = max(0, maxMessageAssistantChars),
                maxMessageFollowUpQuestionChars = max(0, maxMessageFollowUpQuestionChars),
                maxMessageStreamTextChars = max(0, maxMessageStreamTextChars),
                maxFollowUpTurnQuestionChars = max(0, maxFollowUpTurnQuestionChars),
                maxFollowUpTurnAnswerChars = max(0, maxFollowUpTurnAnswerChars)
            )
        }
    }

    /**
     * PII-safe stats snapshot for debugging.
     *
     * Important:
     * - This intentionally does not include draft contents.
     */
    data class StoreStats(
        val enabled: Boolean,
        val keys: Int,
        val maxKeys: Int,
        val loads: Long,
        val saves: Long,
        val clears: Long,
        val evictions: Long,
        val lastEvictedKey: DraftKey?
    )

    private val cfg: Config = config.normalized()
    private val lock = Any()

    /**
     * LRU map (accessOrder=true) to evict least-recently-used keys deterministically.
     *
     * Threading:
     * - Guarded by [lock].
     */
    private val lru = LinkedHashMap<DraftKey, ChatDraft>(/* initialCapacity */ 128, /* loadFactor */ 0.75f, /* accessOrder */ true)

    // Stats (guarded by lock)
    private var loads: Long = 0L
    private var saves: Long = 0L
    private var clears: Long = 0L
    private var evictions: Long = 0L
    private var lastEvictedKey: DraftKey? = null

    /** Returns a PII-safe stats snapshot. */
    fun statsSnapshot(): StoreStats = synchronized(lock) {
        StoreStats(
            enabled = cfg.maxKeys > 0,
            keys = lru.size,
            maxKeys = cfg.maxKeys,
            loads = loads,
            saves = saves,
            clears = clears,
            evictions = evictions,
            lastEvictedKey = lastEvictedKey
        )
    }

    override fun load(key: DraftKey): ChatDraft? = synchronized(lock) {
        loads += 1

        if (cfg.maxKeys <= 0) {
            // Storage disabled.
            return@synchronized null
        }

        val k = key.normalized()
        val v = lru[k] ?: return@synchronized null
        // Return a fresh snapshot to avoid exposing internal list references.
        v.freeze()
    }

    override fun save(key: DraftKey, draft: ChatDraft): Unit = synchronized(lock) {
        saves += 1

        if (cfg.maxKeys <= 0) {
            // Storage disabled. Best-effort: keep map empty.
            if (lru.isNotEmpty()) lru.clear()
            return@synchronized
        }

        val k = key.normalized()
        val capped = draft.freeze().applyCaps(cfg)

        lru[k] = capped

        val evicted = evictIfNeededLocked(cfg.maxKeys)
        if (evicted > 0) {
            logger?.invoke("InMemoryChatDraftStore: evicted=$evicted keysNow=${lru.size} maxKeys=${cfg.maxKeys}")
        }
    }

    override fun clear(key: DraftKey): Unit = synchronized(lock) {
        clears += 1
        val k = key.normalized()
        lru.remove(k)
    }

    // ---------------------------------------------------------------------
    // Internal helpers (guarded by lock)
    // ---------------------------------------------------------------------

    private fun evictIfNeededLocked(maxKeys: Int): Int {
        if (maxKeys <= 0) {
            val n = lru.size
            if (n > 0) {
                lru.clear()
                evictions += n.toLong()
            }
            lastEvictedKey = null
            return n
        }

        var removed = 0
        while (lru.size > maxKeys) {
            val it = lru.entries.iterator()
            if (!it.hasNext()) break
            val victim = it.next()
            it.remove()

            removed += 1
            evictions += 1
            lastEvictedKey = victim.key
        }
        return removed
    }

    /**
     * Apply size caps to reduce the risk of unbounded memory growth.
     *
     * Important:
     * - This does NOT redact sensitive data; it only bounds size.
     * - If you need redaction, do it at the ViewModel layer based on UI policy.
     */
    private fun ChatDraft.applyCaps(cfg: Config): ChatDraft {
        val trimmedMessages = when {
            cfg.maxMessages <= 0 -> emptyList()
            messages.size <= cfg.maxMessages -> messages
            else -> messages.takeLast(cfg.maxMessages)
        }.map { it.capStrings(cfg) }

        val trimmedFollowUps = when {
            cfg.maxFollowUps <= 0 -> emptyList()
            followUps.size <= cfg.maxFollowUps -> followUps
            else -> followUps.takeLast(cfg.maxFollowUps)
        }.map { it.capStrings(cfg) }

        return copy(
            messages = trimmedMessages.toList(),
            followUps = trimmedFollowUps.toList(),
            mainAnswer = mainAnswer.takeSafeChars(cfg.maxMainAnswerChars),
            currentFollowUpQuestion = currentFollowUpQuestion.takeSafeChars(cfg.maxCurrentFollowUpQuestionChars),
            completionPayload = completionPayload?.takeSafeChars(cfg.maxCompletionPayloadChars),
            inputDraft = inputDraft.takeSafeChars(cfg.maxInputDraftChars)
        )
    }

    /**
     * Cap message string fields to limit memory usage.
     *
     * Note:
     * - This is content-preserving (truncation only).
     * - IDs are not modified.
     */
    private fun ChatMessage.capStrings(cfg: Config): ChatMessage {
        val cappedText = text.takeSafeChars(cfg.maxMessageTextChars)
        val cappedAssistant = assistantMessage?.takeSafeChars(cfg.maxMessageAssistantChars)
        val cappedFollowUp = followUpQuestion?.takeSafeChars(cfg.maxMessageFollowUpQuestionChars)
        val cappedStream = streamText?.takeSafeChars(cfg.maxMessageStreamTextChars)

        // Keep structure identical; only cap strings.
        return copy(
            text = cappedText,
            assistantMessage = cappedAssistant,
            followUpQuestion = cappedFollowUp,
            streamText = cappedStream
        )
    }

    /**
     * Cap follow-up turn fields.
     */
    private fun FollowUpTurn.capStrings(cfg: Config): FollowUpTurn {
        return copy(
            question = question.takeSafeChars(cfg.maxFollowUpTurnQuestionChars),
            answer = answer.takeSafeChars(cfg.maxFollowUpTurnAnswerChars)
        )
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