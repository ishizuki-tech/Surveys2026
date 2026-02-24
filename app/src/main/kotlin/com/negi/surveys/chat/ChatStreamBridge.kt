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
 * Why:
 * - The repository/validator layer may stream tokens (Flow<String>).
 * - The ViewModel updates a single "streaming" bubble without coupling model code to VM internals.
 *
 * Design:
 * - Producer calls begin() -> session id, then emitChunk(sessionId,...),
 *   and finally end(sessionId) or error(sessionId,...).
 * - Consumer (ViewModel) collects [events] and ignores stale sessions.
 *
 * Important:
 * - Session ids MUST be monotonically increasing and never reused.
 *   Otherwise "cancelled session id" tracking in the VM can break future sessions.
 *
 * Replacement policy:
 * - Only one active session is supported.
 * - If begin() is called while another session is active:
 *   (1) activeSessionId is cleared first (so late deltas are ignored)
 *   (2) End(prev) is emitted
 *   (3) Begin(new) is emitted
 *
 * Observability:
 * - This bridge maintains lightweight counters and:
 *   - snapshot API ([statsSnapshot])
 *   - reactive StateFlow ([stats])
 *   so the app can debug event drops and stale-event ignores without logging user content.
 *
 * Threading:
 * - Producers may emit from any thread/dispatcher; SharedFlow is thread-safe.
 */
class ChatStreamBridge(
    /**
     * Optional metadata logger.
     *
     * Notes:
     * - Do NOT log user content.
     * - Use this only for session ids / counters / stop reasons.
     */
    private val logger: ((String) -> Unit)? = null
) {

    private val lock = Any()

    /** Monotonic id generator. Never reset. */
    private val nextSessionId = AtomicLong(0L)

    /** Currently active session id (0 => none). */
    private val activeSessionId = AtomicLong(0L)

    private val _events = MutableSharedFlow<ChatStreamEvent>(
        replay = 0,
        // Keep a reasonably large buffer so Begin/End/Error are unlikely to be dropped.
        // DROP_OLDEST means control events still get through even under heavy Delta load.
        extraBufferCapacity = 2048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Stream of Begin/Delta/End/Error events. */
    val events: SharedFlow<ChatStreamEvent> = _events.asSharedFlow()

    // ---------------------------------------------------------------------
    // Stats (PII-safe)
    // ---------------------------------------------------------------------

    private val emittedBegin = AtomicLong(0L)
    private val emittedDelta = AtomicLong(0L)
    private val emittedEnd = AtomicLong(0L)
    private val emittedError = AtomicLong(0L)

    /**
     * NOTE:
     * - With DROP_OLDEST overflow policy, tryEmit(...) is typically "successful" by dropping old items.
     * - Therefore droppedEvents counts only hard failures (rare), not overwritten items.
     */
    private val droppedEvents = AtomicLong(0L)

    private val ignoredDelta = AtomicLong(0L)
    private val ignoredEnd = AtomicLong(0L)
    private val ignoredError = AtomicLong(0L)
    private val ignoredCancel = AtomicLong(0L)

    /**
     * Throttle tick for very frequent paths.
     *
     * Why:
     * - Previously throttle used emittedDelta only, which failed to update UI when only ignoredDelta grew.
     * - We increment this for BOTH emittedDelta and ignoredDelta paths so debug UI remains informative.
     */
    private val deltaTick = AtomicLong(0L)

    @Volatile private var lastEventNs: Long = 0L
    @Volatile private var lastEventKind: String? = null
    @Volatile private var lastEventSessionId: Long = 0L

    @Volatile private var lastCancelSessionId: Long = 0L
    @Volatile private var lastCancelMessage: String? = null

    /**
     * Immutable snapshot of bridge-level stats.
     *
     * Notes:
     * - PII-safe by design.
     * - Useful to render in DebugPanel or to dump to logs.
     */
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
    /** Reactive stats stream for debug UI (PII-safe). */
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    /**
     * Returns a PII-safe snapshot of the current bridge stats.
     */
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

    /**
     * Publishes stats to [stats].
     *
     * Notes:
     * - Delta/ignoredDelta can be very frequent; we throttle those updates.
     * - Control events (Begin/End/Error/Cancel) publish immediately.
     */
    private fun publishStats(force: Boolean) {
        if (force) {
            _stats.value = statsSnapshot()
            return
        }

        // Throttle: publish every 64 delta-path operations (emitted OR ignored).
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

    /**
     * Begin a new streaming session.
     *
     * Returns:
     * - A unique session id.
     *
     * Replacement:
     * - If a previous session is still active, it is ended first (End event) to avoid UI leaks.
     * - Critical: activeSessionId is cleared BEFORE emitting End(prev) so late deltas for prev are ignored.
     */
    fun begin(): Long = synchronized(lock) {
        val prev = activeSessionId.get()
        if (prev > 0L) {
            // Critical ordering:
            // - Clear active first so any concurrent emitChunk(prev, ...) becomes ignored.
            activeSessionId.set(0L)

            emittedEnd.incrementAndGet()
            emitEvent(ChatStreamEvent.End(prev), kind = "End(replaced)", sessionId = prev)
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
     * Emit a chunk for the given session.
     *
     * Notes:
     * - If [sessionId] does not match the currently active session, the event is ignored.
     * - This prevents late chunks from a previous request corrupting the current UI.
     */
    fun emitChunk(sessionId: Long, chunk: String) {
        if (chunk.isEmpty()) return
        if (sessionId <= 0L) return

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

    /**
     * End the given session normally.
     */
    fun end(sessionId: Long): Unit = synchronized(lock) {
        if (sessionId <= 0L) return

        val active = activeSessionId.get()
        if (active != sessionId) {
            ignoredEnd.incrementAndGet()
            publishStats(force = true)
            return
        }

        // Clear active first so late emitChunk/end/error are ignored.
        activeSessionId.set(0L)

        emittedEnd.incrementAndGet()
        emitEvent(ChatStreamEvent.End(sessionId), kind = "End", sessionId = sessionId)

        logger?.invoke("ChatStreamBridge: end session=$sessionId")
        publishStats(force = true)
    }

    /**
     * End the given session with an error.
     */
    fun error(sessionId: Long, message: String): Unit = synchronized(lock) {
        if (sessionId <= 0L) return

        val active = activeSessionId.get()
        if (active != sessionId) {
            ignoredError.incrementAndGet()
            publishStats(force = true)
            return
        }

        // Clear active first so late emitChunk/end/error are ignored.
        activeSessionId.set(0L)

        val msg = message.trim().take(256)
        emittedError.incrementAndGet()
        emitEvent(ChatStreamEvent.Error(sessionId, msg), kind = "Error", sessionId = sessionId)

        logger?.invoke("ChatStreamBridge: error session=$sessionId msg=${msg.take(32)}")
        publishStats(force = true)
    }

    /**
     * Cancel the given session.
     *
     * NOTE:
     * - Convenience wrapper used by the UI/VM layer.
     * - Message is intentionally "cancelled" to match suppression logic in the VM collector.
     *
     * Returns:
     * - The cancelled session id (or 0 if nothing was cancelled).
     */
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

    /**
     * Cancel the currently active session without requiring the caller to know the id.
     *
     * Why:
     * - The ViewModel may need to cancel immediately (Back/Next) before it even receives Begin/Delta.
     * - This prevents late End/Delta from attaching stale model output to the next assistant bubble.
     *
     * Returns:
     * - The cancelled session id (or 0 if nothing was cancelled).
     */
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
    }
}