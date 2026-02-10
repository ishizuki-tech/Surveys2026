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
 * - The repository/validator layer may stream tokens (Flow<String>).
 * - The ViewModel should be able to update a single "streaming" bubble
 *   without tightly coupling model code to ViewModel internals.
 *
 * Design:
 * - Producer calls begin() to obtain a session id, then emitChunk(sessionId,...),
 *   and finally end(sessionId) or error(sessionId,...).
 * - Consumer (ViewModel) collects [events] and ignores stale sessions.
 *
 * Threading:
 * - Producer can emit from any dispatcher/thread; SharedFlow is thread-safe.
 * - Consumer typically collects on Main to update UI state.
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

    private val activeSession = AtomicLong(0L)

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Stream of Begin/Delta/End/Error events. */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Begin a new streaming session.
     *
     * Returns:
     * - A unique session id. Consumers can use it to ignore stale events.
     */
    fun begin(): Long {
        val id = activeSession.incrementAndGet()
        _events.tryEmit(Event.Begin(id))
        return id
    }

    /**
     * Emit a chunk for the given session.
     *
     * Notes:
     * - If [sessionId] does not match the currently active session, the event is ignored.
     * - This prevents "late" chunks from a previous request corrupting the current UI.
     */
    fun emitChunk(sessionId: Long, chunk: String) {
        if (chunk.isEmpty()) return
        if (sessionId <= 0L) return
        if (activeSession.get() != sessionId) return
        _events.tryEmit(Event.Delta(sessionId, chunk))
    }

    /**
     * End the given session normally.
     */
    fun end(sessionId: Long) {
        if (sessionId <= 0L) return
        if (activeSession.get() != sessionId) return
        _events.tryEmit(Event.End(sessionId))
        activeSession.set(0L)
    }

    /**
     * End the given session with an error.
     */
    fun error(sessionId: Long, message: String) {
        if (sessionId <= 0L) return
        if (activeSession.get() != sessionId) return
        _events.tryEmit(Event.Error(sessionId, message))
        activeSession.set(0L)
    }
}
