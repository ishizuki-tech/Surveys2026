/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Draft Store)
 *  ---------------------------------------------------------------------
 *  File: ChatDrafts.kt
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
 * Chat draft models + store namespace.
 *
 * Goal:
 * - Avoid polluting the package-level namespace with many small types.
 * - Call sites use ChatDrafts.DraftKey, ChatDrafts.ChatDraft, ChatDrafts.InMemoryChatDraftStore, etc.
 */
object ChatDrafts {

    /**
     * Stable key for per-question chat draft state.
     *
     * Notes:
     * - [promptHash] is included so that changing prompt text can invalidate old drafts safely.
     * - Normalize [questionId] to avoid accidental key mismatches (e.g., "Q1" vs " q1 ").
     */
    data class DraftKey(
        val questionId: String,
        val promptHash: Int,
    ) {
        /** Returns a normalized copy safe for map keys. */
        fun normalized(): DraftKey = copy(questionId = normalizeQuestionId(questionId))

        private fun normalizeQuestionId(id: String): String {
            /**
             * NOTE:
             * - Align with validator-style normalization: trim + uppercase.
             * - If you intentionally use case-sensitive IDs, remove uppercase.
             */
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
        val messages: List<ChatModels.ChatMessage>,
        val mainAnswer: String,
        val followUps: List<FollowUpTurn>,
        val currentFollowUpQuestion: String,
        val completionPayload: String?,
        val inputDraft: String = "",
        val version: Int = 1,
    ) {
        /**
         * Returns a defensively-copied version so external mutable lists cannot mutate stored drafts.
         *
         * Notes:
         * - This is a shallow snapshot: element objects are not deep-copied.
         * - Keep ChatMessage immutable to preserve safety guarantees.
         */
        fun freeze(): ChatDraft {
            /**
             * Freeze only the top-level lists (defensive copy).
             * Keep element types immutable for stronger safety.
             */
            return copy(
                messages = messages.toList(),
                followUps = followUps.toList(),
            )
        }
    }

    /** Chat state machine stage exposed for draft persistence. */
    enum class ChatStage {
        AWAIT_MAIN,
        AWAIT_FOLLOW_UP,
        DONE,
    }

    /**
     * Represents one follow-up cycle: assistant question -> user answer.
     *
     * Notes:
     * - Stored to support multiple follow-up turns per survey question.
     */
    data class FollowUpTurn(
        val question: String,
        val answer: String,
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

        /**
         * Clears a draft and returns the removed draft if it existed.
         */
        fun clear(key: DraftKey): ChatDraft?
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
        private val logger: ((String) -> Unit)? = null,
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
            // Per-message caps
            // -----------------------------------------------------------------

            /** Max chars for ChatMessage.text (fallback). */
            val maxMessageTextChars: Int = 8_000,

            /** Max chars for ChatMessage.assistantMessage. */
            val maxMessageAssistantChars: Int = 8_000,

            /** Max chars for ChatMessage.followUpQuestion. */
            val maxMessageFollowUpQuestionChars: Int = 8_000,

            /** Max chars for ChatMessage.streamText (embedded model output / debug). */
            val maxMessageStreamTextChars: Int = 16_000,

            /** Max chars for ChatMessage.step1Raw (stable step-1 detail). */
            val maxMessageStep1RawChars: Int = 16_000,

            /** Max chars for ChatMessage.step2Raw (stable step-2 detail). */
            val maxMessageStep2RawChars: Int = 16_000,

            // -----------------------------------------------------------------
            // Per-follow-up caps
            // -----------------------------------------------------------------

            /** Max chars for FollowUpTurn.question. */
            val maxFollowUpTurnQuestionChars: Int = 2_000,

            /** Max chars for FollowUpTurn.answer. */
            val maxFollowUpTurnAnswerChars: Int = 8_000,
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
                    maxMessageStep1RawChars = max(0, maxMessageStep1RawChars),
                    maxMessageStep2RawChars = max(0, maxMessageStep2RawChars),
                    maxFollowUpTurnQuestionChars = max(0, maxFollowUpTurnQuestionChars),
                    maxFollowUpTurnAnswerChars = max(0, maxFollowUpTurnAnswerChars),
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
            val lastEvictedKey: DraftKey?,
        )

        private val cfg: Config = config.normalized()
        private val lock = Any()

        /**
         * LRU map (accessOrder=true) to evict least-recently-used keys deterministically.
         *
         * Threading:
         * - Guarded by [lock].
         */
        private val lru = LinkedHashMap<DraftKey, ChatDraft>(
            /* initialCapacity */ 128,
            /* loadFactor */ 0.75f,
            /* accessOrder */ true,
        )

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
                lastEvictedKey = lastEvictedKey,
            )
        }

        /**
         * Clears all drafts (non-interface helper).
         *
         * Notes:
         * - Useful for "new session" or "privacy reset" flows.
         */
        fun clearAll() = synchronized(lock) {
            clears += 1
            val n = lru.size
            lru.clear()
            if (n > 0) {
                evictions += n.toLong()
                lastEvictedKey = null
            }
        }

        override fun load(key: DraftKey): ChatDraft? {
            if (cfg.maxKeys <= 0) {
                synchronized(lock) { loads += 1 }
                return null
            }

            val k = key.normalized()

            val raw: ChatDraft = synchronized(lock) {
                loads += 1
                lru[k]
            } ?: return null

            return raw.freeze().normalizeForStorage(cfg)
        }

        override fun save(key: DraftKey, draft: ChatDraft) {
            if (cfg.maxKeys <= 0) {
                synchronized(lock) { saves += 1 }
                return
            }

            val k = key.normalized()
            val normalized = draft.freeze().normalizeForStorage(cfg)

            val (evictedCount, keysNow) = synchronized(lock) {
                saves += 1
                lru[k] = normalized
                val evicted = evictIfNeededLocked(cfg.maxKeys)
                evicted to lru.size
            }

            if (evictedCount > 0) {
                logger?.invoke(
                    "InMemoryChatDraftStore: evicted=$evictedCount keysNow=$keysNow maxKeys=${cfg.maxKeys}",
                )
            }
        }

        override fun clear(key: DraftKey): ChatDraft? {
            if (cfg.maxKeys <= 0) {
                synchronized(lock) { clears += 1 }
                return null
            }

            val removed: ChatDraft? = synchronized(lock) {
                clears += 1
                val k = key.normalized()
                lru.remove(k)
            }

            return removed?.freeze()?.normalizeForStorage(cfg)
        }

        // ---------------------------------------------------------------------
        // Internal helpers (guarded by lock unless explicitly stated)
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
         * Normalizes a draft for storage/return:
         * - applies size caps
         * - repairs stage-dependent fields
         *
         * Why:
         * - Size caps protect memory.
         * - Stage-dependent repair avoids restoring logically impossible states.
         */
        private fun ChatDraft.normalizeForStorage(cfg: Config): ChatDraft {
            val trimmedMessages: List<ChatModels.ChatMessage> = when {
                cfg.maxMessages <= 0 -> emptyList()
                messages.size <= cfg.maxMessages -> messages
                else -> messages.takeLast(cfg.maxMessages)
            }.map { it.capStrings(cfg) }

            val trimmedFollowUps: List<FollowUpTurn> = when {
                cfg.maxFollowUps <= 0 -> emptyList()
                followUps.size <= cfg.maxFollowUps -> followUps
                else -> followUps.takeLast(cfg.maxFollowUps)
            }.map { it.capStrings(cfg) }

            val capped =
                copy(
                    messages = trimmedMessages,
                    followUps = trimmedFollowUps,
                    mainAnswer = mainAnswer.takeSafeChars(cfg.maxMainAnswerChars),
                    currentFollowUpQuestion =
                        currentFollowUpQuestion.takeSafeChars(cfg.maxCurrentFollowUpQuestionChars),
                    completionPayload = completionPayload?.takeSafeChars(cfg.maxCompletionPayloadChars),
                    inputDraft = inputDraft.takeSafeChars(cfg.maxInputDraftChars),
                )

            return capped.repairStageDependentFields()
        }

        /**
         * Repairs stage-dependent fields to keep restored drafts semantically consistent.
         *
         * Rules:
         * - AWAIT_MAIN:
         *   - no outstanding follow-up question
         *   - no completion payload
         * - AWAIT_FOLLOW_UP:
         *   - completion payload must be absent
         * - DONE:
         *   - no outstanding follow-up question
         *
         * Notes:
         * - This intentionally avoids rewriting [messages].
         * - Transcript normalization belongs at the ViewModel layer.
         */
        private fun ChatDraft.repairStageDependentFields(): ChatDraft {
            return when (stage) {
                ChatStage.AWAIT_MAIN -> {
                    copy(
                        currentFollowUpQuestion = "",
                        completionPayload = null,
                    )
                }

                ChatStage.AWAIT_FOLLOW_UP -> {
                    copy(
                        completionPayload = null,
                    )
                }

                ChatStage.DONE -> {
                    copy(
                        currentFollowUpQuestion = "",
                    )
                }
            }
        }

        /**
         * Cap message string fields to limit memory usage.
         *
         * Notes:
         * - This is content-preserving (truncation only).
         * - IDs and enums are not modified.
         */
        private fun ChatModels.ChatMessage.capStrings(cfg: Config): ChatModels.ChatMessage {
            return copy(
                text = text.takeSafeChars(cfg.maxMessageTextChars),
                assistantMessage = assistantMessage?.takeSafeChars(cfg.maxMessageAssistantChars),
                followUpQuestion =
                    followUpQuestion?.takeSafeChars(cfg.maxMessageFollowUpQuestionChars),
                streamText = streamText?.takeSafeChars(cfg.maxMessageStreamTextChars),
                step1Raw = step1Raw?.takeSafeChars(cfg.maxMessageStep1RawChars),
                step2Raw = step2Raw?.takeSafeChars(cfg.maxMessageStep2RawChars),
            )
        }

        /** Cap follow-up turn fields. */
        private fun FollowUpTurn.capStrings(cfg: Config): FollowUpTurn {
            return copy(
                question = question.takeSafeChars(cfg.maxFollowUpTurnQuestionChars),
                answer = answer.takeSafeChars(cfg.maxFollowUpTurnAnswerChars),
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
}