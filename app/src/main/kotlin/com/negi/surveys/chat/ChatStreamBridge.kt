/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Streaming Bridge)
 *  ---------------------------------------------------------------------
 *  File: ChatStreamBridge.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A thin, UI-agnostic streaming bridge for model output.
 *
 * Replacement policy:
 * - Only one active session is supported.
 * - If begin() is called while another session is active:
 *   (1) activeSessionId is cleared first (so late deltas are ignored)
 *   (2) the previous session is terminated as Error("replaced") (NOT End)
 *   (3) Begin(new) is emitted
 *
 * Rationale:
 * - "replaced" is not a normal completion. Emitting End(prev) can cause the UI to treat partial
 *   output as a successful stream end and embed it into the next assistant bubble.
 *
 * Overflow note:
 * - BufferOverflow.DROP_OLDEST can overwrite older events without tryEmit() failing.
 * - droppedEvents counts only hard tryEmit failures (rare), not overwritten items.
 *
 * Threading contract:
 * - All producer APIs are safe to call from any thread.
 * - Event ordering is enforced per session: Begin -> Delta* -> End/Error.
 *
 * IMPORTANT (stability):
 * - Error.code MUST be a stable enum-like value (ChatStreamEvent.Codes.*).
 * - Error.token is metadata-only and may be a sanitized message.
 */
class ChatStreamBridge(
    private val logger: ((String) -> Unit)? = null
) {

    private val lock = Any()

    private val nextSessionId = AtomicLong(0L)
    private val activeSessionId = AtomicLong(0L)

    private val _events = MutableSharedFlow<ChatStreamEvent>(
        replay = 0,
        extraBufferCapacity = 2048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ChatStreamEvent> = _events.asSharedFlow()

    // ---------------------------------------------------------------------
    // Stats (PII-safe)
    // ---------------------------------------------------------------------

    private val emittedBegin = AtomicLong(0L)
    private val emittedDelta = AtomicLong(0L)
    private val emittedEnd = AtomicLong(0L)
    private val emittedError = AtomicLong(0L)
    private val droppedEvents = AtomicLong(0L)

    private val ignoredDelta = AtomicLong(0L)
    private val ignoredEnd = AtomicLong(0L)
    private val ignoredError = AtomicLong(0L)
    private val ignoredCancel = AtomicLong(0L)

    private val deltaTick = AtomicLong(0L)

    @Volatile private var lastEventNs: Long = 0L
    @Volatile private var lastEventKind: String? = null
    @Volatile private var lastEventSessionId: Long = 0L

    @Volatile private var lastCancelSessionId: Long = 0L
    @Volatile private var lastCancelMessage: String? = null

    data class StreamStats(
        val activeSessionId: Long,
        val nextSessionId: Long,
        val emittedBegin: Long,
        val emittedDelta: Long,
        val emittedEnd: Long,
        val emittedError: Long,
        val droppedEvents: Long,
        val ignoredDelta: Long,
        val ignoredEnd: Long,
        val ignoredError: Long,
        val ignoredCancel: Long,
        val lastEventKind: String?,
        val lastEventSessionId: Long,
        val lastEventAgeMs: Long,
        val lastCancelSessionId: Long,
        val lastCancelMessage: String?
    )

    private val _stats = MutableStateFlow(statsSnapshot())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    fun statsSnapshot(): StreamStats {
        val now = System.nanoTime()
        val ageMs = if (lastEventNs <= 0L) Long.MAX_VALUE else ((now - lastEventNs) / 1_000_000L)

        return StreamStats(
            activeSessionId = activeSessionId.get(),
            nextSessionId = nextSessionId.get(),
            emittedBegin = emittedBegin.get(),
            emittedDelta = emittedDelta.get(),
            emittedEnd = emittedEnd.get(),
            emittedError = emittedError.get(),
            droppedEvents = droppedEvents.get(),
            ignoredDelta = ignoredDelta.get(),
            ignoredEnd = ignoredEnd.get(),
            ignoredError = ignoredError.get(),
            ignoredCancel = ignoredCancel.get(),
            lastEventKind = lastEventKind,
            lastEventSessionId = lastEventSessionId,
            lastEventAgeMs = ageMs,
            lastCancelSessionId = lastCancelSessionId,
            lastCancelMessage = lastCancelMessage
        )
    }

    private fun publishStats(force: Boolean) {
        if (force) {
            _stats.value = statsSnapshot()
            return
        }
        val t = deltaTick.get()
        if ((t and 0x3FL) == 0L) {
            _stats.value = statsSnapshot()
        }
    }

    private fun emitEvent(ev: ChatStreamEvent, kind: String, sessionId: Long) {
        val ok = _events.tryEmit(ev)

        lastEventNs = System.nanoTime()
        lastEventKind = kind
        lastEventSessionId = sessionId

        if (!ok) {
            droppedEvents.incrementAndGet()
            logger?.invoke("ChatStreamBridge: dropped kind=$kind session=$sessionId")
        }
    }

    // ---------------------------------------------------------------------
    // Producer API
    // ---------------------------------------------------------------------

    fun begin(): Long = synchronized(lock) {
        val prev = activeSessionId.get()
        if (prev > 0L) {
            // NOTE: "replaced" is not a cancel; we do not record it as lastCancel*.
            terminateLocked(
                sessionId = prev,
                token = ChatStreamEvent.Codes.REPLACED,
                code = ChatStreamEvent.Codes.REPLACED,
                kind = "Error(replaced)",
                recordAsCancel = false
            )
            logger?.invoke("ChatStreamBridge: replaced prev session=$prev")
        }

        val id = nextSessionId.incrementAndGet()
        activeSessionId.set(id)

        emittedBegin.incrementAndGet()
        emitEvent(ChatStreamEvent.Begin(sessionId = id), kind = "Begin", sessionId = id)

        logger?.invoke("ChatStreamBridge: begin session=$id (prev=$prev)")
        publishStats(force = true)
        id
    }

    /**
     * Emit a delta chunk for an active session.
     *
     * IMPORTANT:
     * - This method is synchronized to guarantee ordering against end()/error()/cancel()/timeout().
     * - Without this, "Delta after End/Error" can happen under race conditions.
     */
    fun emitChunk(sessionId: Long, chunk: String) {
        if (sessionId <= 0L) return
        if (chunk.isEmpty()) return

        synchronized(lock) {
            val active = activeSessionId.get()
            if (active != sessionId) {
                ignoredDelta.incrementAndGet()
                deltaTick.incrementAndGet()
                publishStats(force = false)
                return
            }

            emittedDelta.incrementAndGet()
            deltaTick.incrementAndGet()
            emitEvent(ChatStreamEvent.Delta(sessionId = sessionId, text = chunk), kind = "Delta", sessionId = sessionId)
            publishStats(force = false)
        }
    }

    fun end(sessionId: Long): Unit = synchronized(lock) {
        if (sessionId <= 0L) return

        val active = activeSessionId.get()
        if (active != sessionId) {
            ignoredEnd.incrementAndGet()
            publishStats(force = true)
            return
        }

        activeSessionId.set(0L)

        emittedEnd.incrementAndGet()
        emitEvent(ChatStreamEvent.End(sessionId = sessionId), kind = "End", sessionId = sessionId)

        logger?.invoke("ChatStreamBridge: end session=$sessionId")
        publishStats(force = true)
    }

    /**
     * Emits an error with a stable ERROR code and a sanitized token.
     *
     * NOTE:
     * - Use [timeout] for stable TIMEOUT classification.
     */
    fun error(sessionId: Long, message: String): Unit = synchronized(lock) {
        if (sessionId <= 0L) return

        val active = activeSessionId.get()
        if (active != sessionId) {
            ignoredError.incrementAndGet()
            publishStats(force = true)
            return
        }

        val token = sanitizeReason(message, fallback = ChatStreamEvent.Codes.ERROR)
        terminateLocked(
            sessionId = sessionId,
            token = token,
            code = ChatStreamEvent.Codes.ERROR,
            kind = "Error",
            recordAsCancel = false
        )

        logger?.invoke("ChatStreamBridge: error session=$sessionId token=${token.take(32)}")
        publishStats(force = true)
    }

    /**
     * Emits a timeout with a stable TIMEOUT code.
     */
    fun timeout(sessionId: Long, message: String = ChatStreamEvent.Codes.TIMEOUT): Long = synchronized(lock) {
        if (sessionId <= 0L) return 0L

        val active = activeSessionId.get()
        if (active != sessionId) {
            ignoredError.incrementAndGet()
            publishStats(force = true)
            return 0L
        }

        val token = sanitizeReason(message, fallback = ChatStreamEvent.Codes.TIMEOUT)
        terminateLocked(
            sessionId = sessionId,
            token = token,
            code = ChatStreamEvent.Codes.TIMEOUT,
            kind = "Error(timeout)",
            recordAsCancel = false
        )

        logger?.invoke("ChatStreamBridge: timeout session=$sessionId token=${token.take(32)}")
        publishStats(force = true)
        sessionId
    }

    /**
     * Emits a timeout for the currently active session, if any.
     */
    fun timeoutActive(message: String = ChatStreamEvent.Codes.TIMEOUT): Long = synchronized(lock) {
        val id = activeSessionId.get()
        if (id <= 0L) {
            ignoredError.incrementAndGet()
            publishStats(force = true)
            return 0L
        }
        return timeout(id, message)
    }

    fun cancel(sessionId: Long, message: String = ChatStreamEvent.Codes.CANCELLED): Long = synchronized(lock) {
        if (sessionId <= 0L) return 0L

        val active = activeSessionId.get()
        if (active != sessionId) {
            ignoredCancel.incrementAndGet()
            publishStats(force = true)
            return 0L
        }

        val token = sanitizeReason(message, fallback = ChatStreamEvent.Codes.CANCELLED)
        terminateLocked(
            sessionId = sessionId,
            token = token,
            code = ChatStreamEvent.Codes.CANCELLED,
            kind = "Error(cancel)",
            recordAsCancel = true
        )

        logger?.invoke("ChatStreamBridge: cancel session=$sessionId token=${token.take(32)}")
        publishStats(force = true)
        sessionId
    }

    fun cancelActive(message: String = ChatStreamEvent.Codes.CANCELLED): Long = synchronized(lock) {
        val id = activeSessionId.get()
        if (id <= 0L) {
            ignoredCancel.incrementAndGet()
            publishStats(force = true)
            return 0L
        }

        val token = sanitizeReason(message, fallback = ChatStreamEvent.Codes.CANCELLED)
        terminateLocked(
            sessionId = id,
            token = token,
            code = ChatStreamEvent.Codes.CANCELLED,
            kind = "Error(cancelActive)",
            recordAsCancel = true
        )

        logger?.invoke("ChatStreamBridge: cancelActive session=$id token=${token.take(32)}")
        publishStats(force = true)
        id
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun terminateLocked(
        sessionId: Long,
        token: String,
        code: String,
        kind: String,
        recordAsCancel: Boolean
    ) {
        // Clear first so late deltas are ignored.
        activeSessionId.set(0L)

        if (recordAsCancel) {
            lastCancelSessionId = sessionId
            lastCancelMessage = token
        }

        emittedError.incrementAndGet()
        emitEvent(
            ChatStreamEvent.Error(
                sessionId = sessionId,
                token = token,
                code = code
            ),
            kind = kind,
            sessionId = sessionId
        )
    }

    /**
     * Sanitizes a reason string into a short, metadata-only token.
     *
     * NOTE:
     * - Avoid passing raw exception messages or user content here.
     */
    private fun sanitizeReason(raw: String, fallback: String, maxLen: Int = 256): String {
        val t = raw.trim()
        val base = if (t.isBlank()) fallback else t

        // Normalize whitespace to avoid UI/log disruption.
        val normalized = buildString(base.length) {
            for (c in base) {
                when (c) {
                    '\n', '\r', '\t' -> append(' ')
                    else -> append(c)
                }
            }
        }

        return normalized.trim().take(maxLen)
    }
}