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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A thin, UI-agnostic streaming bridge for model output.
 *
 * Why:
 * - Lower layers (repository/validator) may stream tokens/chunks as Flow<String>.
 * - ViewModel can render a single updating "stream bubble" without coupling to model internals.
 *
 * Session model:
 * - begin() starts a new session and emits Begin(sessionId).
 * - emitChunk(sessionId, ...) emits deltas only if sessionId is still active.
 * - end(sessionId) / error(sessionId, ...) closes the session and resets active id.
 *
 * Concurrency notes:
 * - Session ids are unique (monotonic) across the process lifetime.
 * - end/error uses CAS to "close" first, then emits End/Error.
 *   This reduces noise from races where a new session begins while an older session tries to close.
 *
 * Backpressure:
 * - Uses tryEmit with a bounded buffer and DROP_OLDEST to avoid suspend in low layers.
 * - Consumers should still be resilient to dropped Delta events (best-effort streaming UI).
 */
class ChatStreamBridge {

    sealed interface Event {
        /** Emitted when a new stream session starts. */
        data class Begin(val sessionId: Long) : Event

        /** Emitted for each streamed chunk (token/substring). */
        data class Delta(val sessionId: Long, val chunk: String) : Event

        /** Emitted when the stream ends normally. */
        data class End(val sessionId: Long) : Event

        /** Emitted when the stream ends with an error. */
        data class Error(val sessionId: Long, val message: String) : Event
    }

    /** Monotonic id generator (never reset). */
    private val nextId = AtomicLong(0L)

    /** Currently active session id (0 = none). */
    private val activeSessionId = AtomicLong(0L)

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Stream of Begin/Delta/End/Error events. */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Begin a new streaming session.
     *
     * @return sessionId for this stream. Pass it back to emitChunk/end/error.
     */
    fun begin(): Long {
        val id = nextId.incrementAndGet()
        activeSessionId.set(id)
        _events.tryEmit(Event.Begin(id))
        return id
    }

    /**
     * Emit a chunk for the specified session.
     *
     * Behavior:
     * - Ignores chunks if sessionId is not the current active session.
     * - This prevents stale streams from corrupting the current UI bubble.
     */
    fun emitChunk(sessionId: Long, chunk: String) {
        if (sessionId <= 0L) return
        if (chunk.isEmpty()) return
        if (activeSessionId.get() != sessionId) return
        _events.tryEmit(Event.Delta(sessionId, chunk))
    }

    /**
     * End the specified session normally.
     *
     * Important:
     * - Uses CAS to close the session first, then emits End.
     * - If the session is already stale, no event is emitted.
     */
    fun end(sessionId: Long) {
        if (sessionId <= 0L) return
        if (!activeSessionId.compareAndSet(sessionId, 0L)) return
        _events.tryEmit(Event.End(sessionId))
    }

    /**
     * End the specified session with an error.
     *
     * Important:
     * - Uses CAS to close the session first, then emits Error.
     * - If the session is already stale, no event is emitted.
     */
    fun error(sessionId: Long, message: String) {
        if (sessionId <= 0L) return
        if (!activeSessionId.compareAndSet(sessionId, 0L)) return
        _events.tryEmit(Event.Error(sessionId, message))
    }

    /**
     * Debug helper: read-only access to the current active session id.
     *
     * Note:
     * - Useful for logging and sanity checks during integration.
     */
    fun currentSessionId(): Long = activeSessionId.get()

    // ------------------------------------------------------------------
    // Optional convenience API (kept for integration ergonomics)
    // ------------------------------------------------------------------

    /** Emit a chunk to the current active session (no-op if none active). */
    fun emitChunk(chunk: String) {
        val id = activeSessionId.get()
        if (id <= 0L) return
        emitChunk(id, chunk)
    }

    /** End the current active session (no-op if none active). */
    fun end() {
        val id = activeSessionId.get()
        if (id <= 0L) return
        end(id)
    }

    /** Error the current active session (no-op if none active). */
    fun error(message: String) {
        val id = activeSessionId.get()
        if (id <= 0L) return
        error(id, message)
    }
}
