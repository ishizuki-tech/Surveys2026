/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Stream Bridge)
 *  ---------------------------------------------------------------------
 *  File: ChatStreamBridge.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thread-safe bridge for streaming raw validation/model output to the UI.
 *
 * Notes:
 * - This bridge is intentionally lightweight and event-based.
 * - The repository/validator may emit multiple phase-tagged sessions over one logical attempt.
 * - Final structured evaluation/follow-up state should not be inferred from these events alone.
 */
class ChatStreamBridge(
    private val logger: ((String) -> Unit)? = null,
) {
    private val sessionSeed = AtomicLong(0L)

    private val activePhaseBySession = ConcurrentHashMap<Long, ChatStreamEvent.Phase>()
    private val lastActiveSessionId = AtomicLong(0L)

    private val _events =
        MutableSharedFlow<ChatStreamEvent>(
            replay = 0,
            extraBufferCapacity = 128,
        )
    val events: SharedFlow<ChatStreamEvent> = _events.asSharedFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    data class StreamStats(
        val activeSessionId: Long = 0L,
        val droppedEvents: Long = 0L,
        val ignoredDelta: Long = 0L,
        val ignoredEnd: Long = 0L,
        val ignoredError: Long = 0L,
        val ignoredCancel: Long = 0L,
    )

    /**
     * Starts a new streaming session for the given phase.
     */
    fun begin(phase: ChatStreamEvent.Phase): Long {
        val sessionId = sessionSeed.incrementAndGet()
        activePhaseBySession[sessionId] = phase
        lastActiveSessionId.set(sessionId)

        emitEvent(ChatStreamEvent.Begin(sessionId = sessionId, phase = phase))
        updateStats { it.copy(activeSessionId = sessionId) }

        logger?.invoke("begin sid=$sessionId phase=$phase")
        return sessionId
    }

    /**
     * Emits a raw chunk for an active session.
     */
    fun emitChunk(
        sessionId: Long,
        text: String,
    ) {
        if (text.isEmpty()) return

        val phase = activePhaseBySession[sessionId]
        if (phase == null) {
            updateStats { it.copy(ignoredDelta = it.ignoredDelta + 1L) }
            logger?.invoke("emitChunk ignored sid=$sessionId reason=no_session len=${text.length}")
            return
        }

        emitEvent(
            ChatStreamEvent.Delta(
                sessionId = sessionId,
                phase = phase,
                text = text,
            ),
        )
    }

    /**
     * Ends an active session normally.
     */
    fun end(sessionId: Long) {
        val phase = activePhaseBySession.remove(sessionId)
        if (phase == null) {
            updateStats { it.copy(ignoredEnd = it.ignoredEnd + 1L) }
            logger?.invoke("end ignored sid=$sessionId reason=no_session")
            return
        }

        emitEvent(ChatStreamEvent.End(sessionId = sessionId, phase = phase))
        recomputeActiveSessionAfterTerminal()

        logger?.invoke("end sid=$sessionId phase=$phase")
    }

    /**
     * Ends an active session with an error token.
     */
    fun error(
        sessionId: Long,
        token: String,
        code: String? = null,
    ) {
        val phase = activePhaseBySession.remove(sessionId)
        if (phase == null) {
            if (code == ChatStreamEvent.Codes.CANCELLED || token == ChatStreamEvent.Codes.CANCELLED) {
                updateStats { it.copy(ignoredCancel = it.ignoredCancel + 1L) }
            } else {
                updateStats { it.copy(ignoredError = it.ignoredError + 1L) }
            }
            logger?.invoke("error ignored sid=$sessionId reason=no_session token=$token code=$code")
            return
        }

        emitEvent(
            ChatStreamEvent.Error(
                sessionId = sessionId,
                phase = phase,
                token = token,
                code = code,
            ),
        )
        recomputeActiveSessionAfterTerminal()

        logger?.invoke("error sid=$sessionId phase=$phase token=$token code=$code")
    }

    /**
     * Cancels the most recent active session and returns its id, if any.
     */
    fun cancelActive(code: String = ChatStreamEvent.Codes.CANCELLED): Long {
        val sessionId = lastActiveSessionId.get()
        if (sessionId <= 0L) return 0L

        val phase = activePhaseBySession.remove(sessionId) ?: run {
            recomputeActiveSessionAfterTerminal()
            return 0L
        }

        emitEvent(
            ChatStreamEvent.Error(
                sessionId = sessionId,
                phase = phase,
                token = ChatStreamEvent.Codes.CANCELLED,
                code = code,
            ),
        )
        recomputeActiveSessionAfterTerminal()

        logger?.invoke("cancelActive sid=$sessionId phase=$phase code=$code")
        return sessionId
    }

    /**
     * Cancels a specific active session.
     */
    fun cancel(
        sessionId: Long,
        code: String = ChatStreamEvent.Codes.CANCELLED,
    ) {
        val phase = activePhaseBySession.remove(sessionId)
        if (phase == null) {
            updateStats { it.copy(ignoredCancel = it.ignoredCancel + 1L) }
            logger?.invoke("cancel ignored sid=$sessionId reason=no_session code=$code")
            return
        }

        emitEvent(
            ChatStreamEvent.Error(
                sessionId = sessionId,
                phase = phase,
                token = ChatStreamEvent.Codes.CANCELLED,
                code = code,
            ),
        )
        recomputeActiveSessionAfterTerminal()

        logger?.invoke("cancel sid=$sessionId phase=$phase code=$code")
    }

    /**
     * Snapshot current stats.
     */
    fun statsSnapshot(): StreamStats = _stats.value

    private fun emitEvent(event: ChatStreamEvent) {
        val emitted = _events.tryEmit(event)
        if (!emitted) {
            updateStats { it.copy(droppedEvents = it.droppedEvents + 1L) }
            logger?.invoke("event dropped type=${event.javaClass.simpleName}")
        }
    }

    /**
     * Recomputes the current active session from the remaining active session map.
     *
     * Notes:
     * - Session ids are monotonic increasing.
     * - The maximum remaining id is the newest still-active session.
     * - This keeps stats and cancelActive() aligned with reality even if an older or
     *   non-current session terminates out of order.
     */
    private fun recomputeActiveSessionAfterTerminal() {
        val nextActive = activePhaseBySession.keys.maxOrNull() ?: 0L
        lastActiveSessionId.set(nextActive)
        updateStats { it.copy(activeSessionId = nextActive) }
    }

    private fun updateStats(transform: (StreamStats) -> StreamStats) {
        _stats.update(transform)
    }
}