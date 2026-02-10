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
 * Cancellation policy:
 * - cancel(sessionId) ends the active session immediately and emits an Error event with "cancelled".
 * - cancelActive() cancels the currently active session without requiring the caller to know the id.
 * - After cancellation, emitChunk/end/error for that session are ignored.
 *
 * Threading:
 * - Producer can emit from any dispatcher/thread; SharedFlow is thread-safe.
 * - Consumer typically collects on Main to update UI state.
 */
class ChatStreamBridge {

    private val activeSession = AtomicLong(0L)

    private val _events = MutableSharedFlow<ChatStreamEvent>(
        replay = 0,
        // NOTE:
        // - Keep a reasonably large buffer to reduce Begin-drop risk.
        // - The VM still has recovery logic for Delta without Begin.
        extraBufferCapacity = 2048,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Stream of Begin/Delta/End/Error events. */
    val events: SharedFlow<ChatStreamEvent> = _events.asSharedFlow()

    /**
     * Begin a new streaming session.
     *
     * Returns:
     * - A unique session id. Consumers can use it to ignore stale events.
     */
    fun begin(): Long {
        val id = activeSession.incrementAndGet()
        _events.tryEmit(ChatStreamEvent.Begin(id))
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
        _events.tryEmit(ChatStreamEvent.Delta(sessionId, chunk))
    }

    /**
     * End the given session normally.
     */
    fun end(sessionId: Long) {
        if (sessionId <= 0L) return
        if (activeSession.get() != sessionId) return
        _events.tryEmit(ChatStreamEvent.End(sessionId))
        activeSession.set(0L)
    }

    /**
     * End the given session with an error.
     */
    fun error(sessionId: Long, message: String) {
        if (sessionId <= 0L) return
        if (activeSession.get() != sessionId) return
        _events.tryEmit(ChatStreamEvent.Error(sessionId, message))
        activeSession.set(0L)
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
    fun cancel(sessionId: Long, message: String = CANCELLED_MESSAGE): Long {
        if (sessionId <= 0L) return 0L
        if (activeSession.get() != sessionId) return 0L
        error(sessionId, message)
        return sessionId
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
    fun cancelActive(message: String = CANCELLED_MESSAGE): Long {
        val id = activeSession.get()
        if (id <= 0L) return 0L
        error(id, message)
        return id
    }

    private companion object {
        private const val CANCELLED_MESSAGE = "cancelled"
    }
}
