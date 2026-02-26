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

import java.util.Locale
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
            logger?.invoke("ChatStreamBridge: event dropped kind=$kind session=$sessionId")
        }
    }

    // ---------------------------------------------------------------------
    // Producer API
    // ---------------------------------------------------------------------

    fun begin(): Long = synchronized(lock) {
        val prev = activeSessionId.get()
        if (prev > 0L) {
            activeSessionId.set(0L)

            lastCancelSessionId = prev
            lastCancelMessage = REPLACED_MESSAGE

            emittedError.incrementAndGet()
            emitEvent(
                ChatStreamEvent.Error(prev, REPLACED_MESSAGE),
                kind = "Error(replaced)",
                sessionId = prev
            )

            logger?.invoke("ChatStreamBridge: replaced prev session=$prev")
        }

        val id = nextSessionId.incrementAndGet()
        activeSessionId.set(id)

        emittedBegin.incrementAndGet()
        emitEvent(ChatStreamEvent.Begin(id), kind = "Begin", sessionId = id)

        logger?.invoke("ChatStreamBridge: begin session=$id (prev=$prev)")
        publishStats(force = true)
        id
    }

    /**
     * Emit a delta chunk for an active session.
     *
     * IMPORTANT:
     * - This method is synchronized to guarantee ordering against end()/error()/cancel().
     * - Without this, "Delta after End/Error" can happen under race conditions.
     */
    fun emitChunk(sessionId: Long, chunk: String) {
        if (chunk.isEmpty()) return
        if (sessionId <= 0L) return

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
            emitEvent(ChatStreamEvent.Delta(sessionId, chunk), kind = "Delta", sessionId = sessionId)
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
        emitEvent(ChatStreamEvent.End(sessionId), kind = "End", sessionId = sessionId)

        logger?.invoke("ChatStreamBridge: end session=$sessionId")
        publishStats(force = true)
    }

    fun error(sessionId: Long, message: String): Unit = synchronized(lock) {
        if (sessionId <= 0L) return

        val active = activeSessionId.get()
        if (active != sessionId) {
            ignoredError.incrementAndGet()
            publishStats(force = true)
            return
        }

        activeSessionId.set(0L)

        val msg = message.trim().take(256)
        emittedError.incrementAndGet()
        emitEvent(ChatStreamEvent.Error(sessionId, msg), kind = "Error", sessionId = sessionId)

        logger?.invoke("ChatStreamBridge: error session=$sessionId msg=${msg.take(32)}")
        publishStats(force = true)
    }

    fun cancel(sessionId: Long, message: String = CANCELLED_MESSAGE): Long = synchronized(lock) {
        if (sessionId <= 0L) return 0L

        val active = activeSessionId.get()
        if (active != sessionId) {
            ignoredCancel.incrementAndGet()
            publishStats(force = true)
            return 0L
        }

        activeSessionId.set(0L)

        val msg = message.trim().ifBlank { CANCELLED_MESSAGE }
        lastCancelSessionId = sessionId
        lastCancelMessage = msg

        emittedError.incrementAndGet()
        emitEvent(
            ChatStreamEvent.Error(sessionId, msg),
            kind = "Error(cancel)",
            sessionId = sessionId
        )

        logger?.invoke("ChatStreamBridge: cancel session=$sessionId msg=${msg.lowercase(Locale.US)}")
        publishStats(force = true)
        sessionId
    }

    fun cancelActive(message: String = CANCELLED_MESSAGE): Long = synchronized(lock) {
        val id = activeSessionId.get()
        if (id <= 0L) {
            ignoredCancel.incrementAndGet()
            publishStats(force = true)
            return 0L
        }

        activeSessionId.set(0L)

        val msg = message.trim().ifBlank { CANCELLED_MESSAGE }
        lastCancelSessionId = id
        lastCancelMessage = msg

        emittedError.incrementAndGet()
        emitEvent(
            ChatStreamEvent.Error(id, msg),
            kind = "Error(cancelActive)",
            sessionId = id
        )

        logger?.invoke("ChatStreamBridge: cancelActive session=$id msg=${msg.lowercase(Locale.US)}")
        publishStats(force = true)
        id
    }

    private companion object {
        private const val CANCELLED_MESSAGE = "cancelled"
        private const val REPLACED_MESSAGE = "replaced"
    }
}