/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat + Validation VM)
 *  ---------------------------------------------------------------------
 *  File: ChatQuestionViewModel.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that drives a chat-style Q/A with validation + multi follow-up support,
 * and survives navigation by persisting/restoring state via [ChatDraftStore].
 *
 * Notes:
 * - Some UIs allow "Skip" externally (e.g., Next without completionPayload). This VM still
 *   maintains completionPayload for the "accepted answer" path and draft restoration.
 *
 * Editing after completion:
 * - If the user revisits a completed question and submits a new message, the VM re-opens the question,
 *   clears completion payload, and re-validates as a new attempt (when allowResubmitAfterDone=true).
 *
 * Streaming UX policy (implemented here):
 * - While streaming: show ONE MODEL bubble (expanded).
 * - When stream ends: remove MODEL bubble and embed output into the next ASSISTANT outcome bubble
 *   as collapsible "Model output" details (collapsed by default).
 */
class ChatQuestionViewModel(
    private val questionId: String,
    private val prompt: String,
    private val validator: AnswerValidator,
    private val streamBridge: ChatStreamBridge,
    private val draftStore: ChatDraftStore,
    private val draftKey: DraftKey,
    private val maxFollowUps: Int = 5,
    private val allowResubmitAfterDone: Boolean = true,
    private val maxModelCharsInUi: Int = 8_000
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /**
     * One-shot completion event (optional).
     *
     * Notes:
     * - Keep for legacy flows that still react to events.
     * - UI should NOT rely on this for enabling Next; use completionPayload state instead.
     */
    private val _completion = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val completion: SharedFlow<String> = _completion.asSharedFlow()

    /**
     * Sticky completion payload for enabling Next even after returning via Back.
     *
     * Notes:
     * - This is state, not an event.
     * - UI should read this for enabling Next.
     */
    private val _completionPayload = MutableStateFlow<String?>(null)
    val completionPayload: StateFlow<String?> = _completionPayload.asStateFlow()

    private var stage: ChatStage = ChatStage.AWAIT_MAIN
    private var mainAnswer: String = ""
    private var currentFollowUpQuestion: String = ""
    private val followUps: MutableList<FollowUpTurn> = mutableListOf()

    /** Job for in-flight validation work so we can cancel on Back/Next/dispose. */
    private var validationJob: Job? = null

    /** Track active MODEL stream bubble so cancelValidation() can remove it immediately. */
    @Volatile private var activeStreamMsgId: String? = null
    @Volatile private var activeStreamSessionId: Long = 0L

    /** Remember the last cancelled stream session so we can ignore late deltas. */
    @Volatile private var lastCancelledStreamSessionId: Long = 0L

    /** Suppress "cancelled" error rendering when cancellation is intentional (Back/Next/dispose). */
    private val suppressNextCancelledError = AtomicBoolean(false)

    // ---------------------------------------------------------------------
    // Embedding pipeline (MODEL -> ASSISTANT)
    // ---------------------------------------------------------------------

    /**
     * Pending model output that should be embedded into the next assistant outcome bubble.
     *
     * Why pending?
     * - Stream End can happen before the validator returns (outcome not appended yet).
     * - Or stream End can happen after outcome was appended (we attach retroactively).
     */
    @Volatile private var pendingModelOutput: String? = null
    @Volatile private var pendingModelState: ChatStreamState = ChatStreamState.NONE
    @Volatile private var pendingModelSessionId: Long = 0L

    /** The last assistant outcome bubble appended during the most recent validation attempt. */
    @Volatile private var lastOutcomeAssistantMsgId: String? = null

    init {
        restoreOrSeed()
        startStreamCollector()
        Log.d(TAG, "VM init questionId=$questionId draftKey=$draftKey stage=$stage")
    }

    fun setInput(text: String) {
        _input.value = text
        // NOTE:
        // - Input is ephemeral. Avoid persisting on every keystroke for performance.
    }

    /**
     * Backward-compatible alias for older UI code.
     */
    fun send() = submit()

    /**
     * Submits the current user input into the chat.
     *
     * Rules:
     * - If stage==DONE and allowResubmitAfterDone==true, re-open the question for editing.
     * - No-op if busy.
     */
    fun submit() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        if (_isBusy.value) return

        // If completed, allow the user to re-answer by re-opening.
        if (stage == ChatStage.DONE && allowResubmitAfterDone) {
            reopenForEdit()
        } else if (stage == ChatStage.DONE) {
            // Hard stop if editing is disabled.
            return
        }

        _input.value = ""
        appendUser(text)

        // Cancel any previous validation job defensively.
        validationJob?.cancel()
        validationJob = null

        validationJob = viewModelScope.launch {
            _isBusy.value = true
            try {
                when (stage) {
                    ChatStage.AWAIT_MAIN -> handleMain(text)
                    ChatStage.AWAIT_FOLLOW_UP -> handleFollowUp(text)
                    ChatStage.DONE -> Unit
                }
            } finally {
                _isBusy.value = false
                validationJob = null
                persistDraft()
            }
        }
    }

    /**
     * Cancels any in-flight validation and stops streaming UI.
     *
     * Notes:
     * - Used on Back/Next/dispose to prevent stale streaming from continuing.
     * - We remove the transient streaming bubble immediately to avoid confusion.
     */
    fun cancelValidation(reason: String) {
        Log.d(TAG, "cancelValidation: qid=$questionId reason=$reason")

        // Mark that the next "cancelled" error event should be suppressed in the stream collector.
        suppressNextCancelledError.set(true)

        // Remember which session we are cancelling to ignore late deltas.
        val cancelledSession = activeStreamSessionId
        if (cancelledSession > 0L) {
            lastCancelledStreamSessionId = cancelledSession
        }

        validationJob?.cancel()
        validationJob = null
        _isBusy.value = false

        // Remove active MODEL stream bubble immediately (if any).
        val msgId = activeStreamMsgId
        if (msgId != null) {
            removeMessage(msgId)
        }

        // Clear active stream markers.
        activeStreamMsgId = null
        activeStreamSessionId = 0L

        // Clear pending embed state (do not attach cancelled output).
        clearPendingModelEmbed("cancelValidation")

        persistDraft()
    }

    private suspend fun handleMain(answer: String) {
        mainAnswer = answer
        logAnswerDigest("main", answer)

        val out = validator.validateMain(questionId, answer)

        val followUp = if (out.status == ValidationStatus.NEED_FOLLOW_UP) {
            out.followUpQuestion.orEmpty().ifBlank { DEFAULT_FOLLOW_UP_MAIN }
        } else {
            null
        }

        // Render a structured assistant bubble:
        // - assistantMessage + followUpQuestion (if any)
        // - embed pending model output (if available)
        appendAssistantOutcome(
            assistantMessage = out.assistantMessage,
            followUpQuestion = followUp
        )

        if (out.status == ValidationStatus.NEED_FOLLOW_UP) {
            currentFollowUpQuestion = followUp.orEmpty()
            stage = ChatStage.AWAIT_FOLLOW_UP
            persistDraft()
        } else {
            stage = ChatStage.DONE
            val payload = buildCombined(mainAnswer, followUps)
            _completionPayload.value = payload
            _completion.tryEmit(payload)
            persistDraft()
        }
    }

    private suspend fun handleFollowUp(userAnswer: String) {
        if (followUps.size >= maxFollowUps) {
            appendAssistantText("I have enough details. Let's move on.")
            stage = ChatStage.DONE
            val payload = buildCombined(mainAnswer, followUps)
            _completionPayload.value = payload
            _completion.tryEmit(payload)
            persistDraft()
            return
        }

        // Save turn (assistant Q + user A).
        val q = currentFollowUpQuestion.ifEmpty { "(follow-up)" }
        followUps += FollowUpTurn(question = q, answer = userAnswer)
        logAnswerDigest("followup#${followUps.size}", userAnswer)

        val followUpContext = buildFollowUpContext(followUps)

        val out = validator.validateFollowUp(
            questionId = questionId,
            mainAnswer = mainAnswer,
            followUpAnswer = followUpContext
        )

        val followUp = if (out.status == ValidationStatus.NEED_FOLLOW_UP) {
            out.followUpQuestion.orEmpty().ifBlank { DEFAULT_FOLLOW_UP_MORE }
        } else {
            null
        }

        appendAssistantOutcome(
            assistantMessage = out.assistantMessage,
            followUpQuestion = followUp
        )

        if (out.status == ValidationStatus.NEED_FOLLOW_UP) {
            currentFollowUpQuestion = followUp.orEmpty()
            stage = ChatStage.AWAIT_FOLLOW_UP
            persistDraft()
        } else {
            stage = ChatStage.DONE
            val payload = buildCombined(mainAnswer, followUps)
            _completionPayload.value = payload
            _completion.tryEmit(payload)
            persistDraft()
        }
    }

    /**
     * Re-opens a completed question for editing.
     *
     * Strategy:
     * - Keep the prior transcript (auditability).
     * - Clear completion payload so Next becomes disabled until re-accepted (if UI uses it).
     * - Clear follow-up state so we start fresh from main answer.
     */
    private fun reopenForEdit() {
        appendAssistantText("Re-opening this question for editing. Please submit your revised answer.")
        stage = ChatStage.AWAIT_MAIN
        mainAnswer = ""
        currentFollowUpQuestion = ""
        followUps.clear()
        _completionPayload.value = null
        persistDraft()
    }

    /**
     * Builds a compact, model-friendly follow-up history string.
     *
     * Notes:
     * - Deterministic and compact.
     * - Avoid re-printing MAIN_ANSWER here; validator already has it separately.
     */
    private fun buildFollowUpContext(turns: List<FollowUpTurn>): String {
        if (turns.isEmpty()) return "(none)"
        return buildString {
            turns.forEachIndexed { idx, t ->
                append("FOLLOW_UP_${idx + 1}_Q: ").append(t.question.trim()).append('\n')
                append("FOLLOW_UP_${idx + 1}_A: ").append(t.answer.trim()).append('\n')
            }
        }.trim()
    }

    /**
     * Combine the final payload returned to the navigation layer.
     *
     * TODO:
     * - Replace with a structured model (e.g., AnswerBundle(main, followUps, scores)).
     */
    private fun buildCombined(main: String, turns: List<FollowUpTurn>): String {
        return buildString {
            append(main.trim())
            if (turns.isNotEmpty()) {
                append("\n\n[FollowUps]\n")
                turns.forEachIndexed { idx, t ->
                    append("#").append(idx + 1).append(" Q: ").append(t.question.trim()).append('\n')
                    append("#").append(idx + 1).append(" A: ").append(t.answer.trim()).append('\n')
                }
            }
        }.trim()
    }

    private fun appendUser(text: String) {
        val msg = ChatMessage.user(
            id = UUID.randomUUID().toString(),
            text = text
        )
        _messages.value = _messages.value + msg
        persistDraft()
    }

    /**
     * Append a plain assistant message (non-structured).
     */
    private fun appendAssistantText(text: String) {
        val msg = ChatMessage.assistant(
            id = UUID.randomUUID().toString(),
            assistantMessage = text,
            followUpQuestion = null,
            textFallback = text
        )
        _messages.value = _messages.value + msg
        persistDraft()
    }

    /**
     * Append a structured assistant outcome bubble.
     *
     * Embedding rule:
     * - If there is pending model output (stored by stream End/Error), embed it into this assistant bubble
     *   as streamText + streamState + streamCollapsed=true.
     */
    private fun appendAssistantOutcome(
        assistantMessage: String,
        followUpQuestion: String?
    ) {
        val a = assistantMessage.trim()
        val q = followUpQuestion?.trim().orEmpty()

        // Text fallback for older UIs that only render msg.text.
        val composite = buildString {
            if (a.isNotEmpty()) append(a)
            if (q.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(q)
            }
        }.trim()

        val id = UUID.randomUUID().toString()

        val pending = pendingModelOutput?.takeIf { it.isNotBlank() }
        val msg = if (pending != null && pendingModelState != ChatStreamState.NONE) {
            ChatMessage.assistantWithModelOutput(
                id = id,
                assistantMessage = assistantMessage,
                followUpQuestion = followUpQuestion,
                modelOutput = pending,
                modelState = pendingModelState,
                streamCollapsed = true,
                textFallback = composite
            )
        } else {
            ChatMessage.assistant(
                id = id,
                assistantMessage = assistantMessage,
                followUpQuestion = followUpQuestion,
                textFallback = composite
            )
        }

        _messages.value = _messages.value + msg
        lastOutcomeAssistantMsgId = id

        // If we embedded pending output now, clear it.
        if (pending != null) {
            clearPendingModelEmbed("appendAssistantOutcome(embed)")
        }

        persistDraft()
    }

    private fun removeMessage(id: String) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { m ->
            if (m.id == id) transform(m) else m
        }
    }

    private fun containsMessage(id: String): Boolean {
        return _messages.value.any { it.id == id }
    }

    /**
     * Attach model output to the most recent assistant outcome bubble, if available.
     *
     * If no assistant outcome exists yet, store it as pending so it will be embedded
     * into the next appended assistant outcome.
     */
    private fun attachOrPendModelOutput(
        sessionId: Long,
        output: String,
        state: ChatStreamState
    ) {
        val raw = output.trimEnd()
        if (raw.isBlank()) return

        val targetId = lastOutcomeAssistantMsgId
        val canAttachNow = targetId != null && containsMessage(targetId)

        if (canAttachNow) {
            updateMessage(targetId!!) { m ->
                // Keep assistant fields intact and add stream details.
                m.copy(
                    streamText = raw,
                    streamState = state,
                    streamCollapsed = true
                )
            }
            clearPendingModelEmbed("attachOrPendModelOutput(attached)")
            return
        }

        // Otherwise, pend for the next assistant outcome bubble.
        pendingModelSessionId = sessionId
        pendingModelOutput = raw
        pendingModelState = state
    }

    private fun clearPendingModelEmbed(reason: String) {
        pendingModelSessionId = 0L
        pendingModelOutput = null
        pendingModelState = ChatStreamState.NONE
        Log.d(TAG, "Pending model embed cleared. reason=$reason")
    }

    /**
     * Stream bridge collector.
     *
     * Goals:
     * - Show model deltas as a single MODEL bubble while validation is running (expanded).
     * - When stream ends, remove MODEL bubble and embed output into the next ASSISTANT outcome bubble
     *   (collapsed by default).
     *
     * Robustness:
     * - Handles missing Begin by treating the first Delta as an implicit Begin (DROP_OLDEST protection).
     * - Ignores late deltas for a session that was cancelled via cancelValidation().
     * - Throttles UI updates to reduce recomposition pressure.
     *
     * Implementation notes:
     * - Cap visible buffer to [maxModelCharsInUi] using in-place delete to avoid allocations.
     * - Avoid persisting on every delta (heavy write pattern).
     * - Suppress "cancelled" error rendering when cancellation was intentional.
     */
    private fun startStreamCollector() {
        viewModelScope.launch {
            var localActiveSession = 0L
            var localStreamMsgId: String? = null
            val buf = StringBuilder()

            var lastUiUpdateNs = 0L
            var lastRenderedLen = 0

            fun resetLocalStreamState() {
                localActiveSession = 0L
                localStreamMsgId = null
                buf.setLength(0)
                lastUiUpdateNs = 0L
                lastRenderedLen = 0
            }

            fun beginSession(sessionId: Long) {
                if (localActiveSession == sessionId && localStreamMsgId != null) return

                localActiveSession = sessionId
                buf.setLength(0)
                lastUiUpdateNs = 0L
                lastRenderedLen = 0

                // Reset per-attempt attachment markers.
                lastOutcomeAssistantMsgId = null
                clearPendingModelEmbed("beginSession")

                val id = UUID.randomUUID().toString()
                localStreamMsgId = id

                activeStreamSessionId = sessionId
                activeStreamMsgId = id

                // MODEL streaming bubble (expanded).
                val bubble = ChatMessage.modelStreaming(id).copy(text = "Validating…")
                _messages.value = _messages.value + bubble

                // Persist once at begin (not per-delta).
                persistDraft()
            }

            fun maybeUpdateUi(force: Boolean = false) {
                val id = localStreamMsgId ?: return
                val now = System.nanoTime()
                val deltaChars = buf.length - lastRenderedLen

                val shouldUpdate =
                    force ||
                            deltaChars >= STREAM_MIN_UPDATE_CHARS ||
                            (now - lastUiUpdateNs) >= STREAM_UI_UPDATE_INTERVAL_NS

                if (!shouldUpdate) return

                val rendered = buf.toString()

                updateMessage(id) { m ->
                    // Keep msg.text updated during streaming for legacy UI,
                    // and keep streamText for the details section embedding later.
                    m.copy(
                        role = ChatRole.MODEL,
                        text = rendered.ifBlank { "Validating…" },
                        streamText = rendered,
                        streamState = ChatStreamState.STREAMING,
                        streamCollapsed = false
                    )
                }

                lastUiUpdateNs = now
                lastRenderedLen = buf.length
            }

            fun finalizeAndRemoveModelBubble(state: ChatStreamState, errorMessage: String? = null) {
                val id = localStreamMsgId ?: return

                val raw = buf.toString().ifBlank {
                    errorMessage.orEmpty()
                }

                // Remove the MODEL bubble from the transcript.
                removeMessage(id)

                // Attach to last outcome assistant if possible; otherwise pend.
                attachOrPendModelOutput(
                    sessionId = localActiveSession,
                    output = raw,
                    state = state
                )
            }

            try {
                streamBridge.events.collect { ev ->
                    when (ev) {
                        is ChatStreamBridge.Event.Begin -> {
                            // New session overrides any stale local state.
                            beginSession(ev.sessionId)
                        }

                        is ChatStreamBridge.Event.Delta -> {
                            if (ev.chunk.isEmpty()) return@collect

                            // Ignore late deltas for a cancelled session.
                            val cancelledId = lastCancelledStreamSessionId
                            if (cancelledId > 0L && ev.sessionId == cancelledId && activeStreamSessionId == 0L) {
                                return@collect
                            }

                            // If local state still points to a cancelled session, reset it so a new session can recover.
                            if (cancelledId > 0L && localActiveSession == cancelledId && activeStreamSessionId == 0L) {
                                resetLocalStreamState()
                            }

                            // Recover if Begin was dropped.
                            val sessionMismatch = ev.sessionId != localActiveSession || localStreamMsgId == null
                            if (sessionMismatch) {
                                if (localActiveSession == 0L && localStreamMsgId == null) {
                                    beginSession(ev.sessionId)
                                } else {
                                    return@collect
                                }
                            }

                            buf.append(ev.chunk)

                            if (buf.length > maxModelCharsInUi) {
                                val drop = buf.length - maxModelCharsInUi
                                buf.delete(0, drop)
                                lastRenderedLen = (lastRenderedLen - drop).coerceAtLeast(0)
                            }

                            maybeUpdateUi(force = false)
                        }

                        is ChatStreamBridge.Event.End -> {
                            if (ev.sessionId != localActiveSession) return@collect

                            maybeUpdateUi(force = true)
                            finalizeAndRemoveModelBubble(state = ChatStreamState.ENDED)

                            resetLocalStreamState()
                            activeStreamSessionId = 0L
                            activeStreamMsgId = null
                            persistDraft()
                        }

                        is ChatStreamBridge.Event.Error -> {
                            if (ev.sessionId != localActiveSession) return@collect
                            val id = localStreamMsgId ?: return@collect

                            val msg = ev.message.trim().lowercase()
                            val suppressed =
                                (msg == "cancelled" || msg == "canceled") &&
                                        suppressNextCancelledError.getAndSet(false)

                            if (suppressed) {
                                // Remove the MODEL bubble, do not attach anything.
                                removeMessage(id)
                                clearPendingModelEmbed("stream_error_suppressed")
                            } else {
                                maybeUpdateUi(force = true)
                                finalizeAndRemoveModelBubble(
                                    state = ChatStreamState.ERROR,
                                    errorMessage = ev.message
                                )
                            }

                            resetLocalStreamState()
                            activeStreamSessionId = 0L
                            activeStreamMsgId = null
                            persistDraft()
                        }
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "Stream collector crashed (non-fatal). ${t.javaClass.simpleName}", t)
            }
        }
    }

    /**
     * Restore draft if present, otherwise seed initial assistant message.
     */
    private fun restoreOrSeed() {
        val draft = draftStore.load(draftKey)
        if (draft != null) {
            stage = draft.stage
            _messages.value = draft.messages
            mainAnswer = draft.mainAnswer
            followUps.clear()
            followUps.addAll(draft.followUps)
            currentFollowUpQuestion = draft.currentFollowUpQuestion
            _completionPayload.value = draft.completionPayload
            _isBusy.value = false

            // Clear ephemeral attachment state on restore.
            activeStreamMsgId = null
            activeStreamSessionId = 0L
            lastOutcomeAssistantMsgId = null
            clearPendingModelEmbed("restoreOrSeed")

            Log.d(TAG, "Draft restored. qid=$questionId stage=$stage msgs=${draft.messages.size}")
            return
        }

        // Seed initial assistant message for a new question.
        val seeded = ChatMessage.assistant(
            id = UUID.randomUUID().toString(),
            assistantMessage = "Question: $questionId",
            followUpQuestion = prompt,
            textFallback = "Question: $questionId\n$prompt"
        )

        _messages.value = listOf(seeded)
        stage = ChatStage.AWAIT_MAIN
        mainAnswer = ""
        currentFollowUpQuestion = ""
        followUps.clear()
        _completionPayload.value = null
        _isBusy.value = false

        clearPendingModelEmbed("seed")
        persistDraft()
    }

    /**
     * Persist current VM state to draft store.
     *
     * Notes:
     * - This writes user content. Keep store in-memory unless you handle encryption/consent.
     * - Avoid calling this on every streaming delta.
     */
    private fun persistDraft() {
        val draft = ChatDraft(
            stage = stage,
            messages = _messages.value,
            mainAnswer = mainAnswer,
            followUps = followUps.toList(),
            currentFollowUpQuestion = currentFollowUpQuestion,
            completionPayload = _completionPayload.value
        )
        draftStore.save(draftKey, draft)
    }

    /**
     * Logs non-PII debug info: length and short SHA-256 prefix.
     */
    private fun logAnswerDigest(label: String, text: String) {
        val t = text.trim()
        val sha8 = sha256Hex(t).take(8)
        Log.d(TAG, "answer[$label] q=$questionId len=${t.length} sha8=$sha8")
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))

        val hex = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt()
            sb.append(hex[(v ushr 4) and 0xF])
            sb.append(hex[v and 0xF])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "ChatQVM"

        // UI throttling knobs for streaming deltas.
        private const val STREAM_UI_UPDATE_INTERVAL_NS = 50_000_000L // 50ms
        private const val STREAM_MIN_UPDATE_CHARS = 16

        private const val DEFAULT_FOLLOW_UP_MAIN = "Could you add one concrete detail or example?"
        private const val DEFAULT_FOLLOW_UP_MORE = "Could you add one more concrete detail?"
    }
}
