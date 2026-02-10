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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel that drives a chat-style Q/A with validation + multi follow-up support,
 * and survives navigation by persisting/restoring state via [ChatDraftStore].
 *
 * Streaming UX policy:
 * - While streaming: show ONE MODEL bubble (expanded).
 * - When stream ends: remove MODEL bubble and embed output into the next ASSISTANT outcome bubble
 *   as collapsible "Model output" details (collapsed by default).
 *
 * Concurrency note:
 * - Chat transcript is updated from both validation coroutine and stream collector coroutine.
 * - Always use StateFlow.update { ... } for atomic list mutations to avoid lost updates.
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
     * One-shot completion event (legacy).
     *
     * NOTE:
     * - UI should prefer [completionPayload] (state) for enabling Next across navigation.
     */
    private val _completion = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val completion: SharedFlow<String> = _completion.asSharedFlow()

    /**
     * Sticky completion payload.
     *
     * NOTE:
     * - This is "state", not an event.
     * - UI should read this to enable Next even after returning via Back.
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

    /** Remember the last cancelled stream session so we can ignore late deltas/ends. */
    @Volatile private var lastCancelledStreamSessionId: Long = 0L

    /** Suppress "cancelled" error rendering when cancellation is intentional (Back/Next/dispose). */
    private val suppressNextCancelledError = AtomicBoolean(false)

    // ---------------------------------------------------------------------
    // Embedding pipeline (MODEL -> ASSISTANT)
    // ---------------------------------------------------------------------

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

    override fun onCleared() {
        runCatching { cancelValidation("onCleared") }
        super.onCleared()
    }

    fun setInput(text: String) {
        _input.value = text
    }

    fun send() = submit()

    fun submit() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        if (_isBusy.value) return

        if (stage == ChatStage.DONE && allowResubmitAfterDone) {
            reopenForEdit()
        } else if (stage == ChatStage.DONE) {
            return
        }

        _input.value = ""
        appendUser(text)

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
                safePersistDraft("submit.finally")
            }
        }
    }

    /**
     * Cancels any in-flight validation and stops streaming UI.
     *
     * Root fix:
     * - Always cancel the active stream session in [ChatStreamBridge], even if the VM has not yet
     *   observed Begin/Delta (activeStreamSessionId may still be 0).
     */
    fun cancelValidation(reason: String) {
        Log.d(TAG, "cancelValidation: qid=$questionId reason=$reason")

        suppressNextCancelledError.set(true)

        // Root fix:
        // - Cancel active stream without requiring the VM to know the session id yet.
        val cancelledId = streamBridge.cancelActive(CANCELLED_MESSAGE)
        if (cancelledId > 0L) {
            lastCancelledStreamSessionId = cancelledId
        } else {
            val sessionId = activeStreamSessionId
            if (sessionId > 0L) {
                lastCancelledStreamSessionId = sessionId
                streamBridge.cancel(sessionId, CANCELLED_MESSAGE)
            }
        }

        validationJob?.cancel()
        validationJob = null
        _isBusy.value = false

        val msgId = activeStreamMsgId
        if (msgId != null) {
            removeMessage(msgId)
        }

        activeStreamMsgId = null
        activeStreamSessionId = 0L

        // Prevent late attachments.
        lastOutcomeAssistantMsgId = null
        clearPendingModelEmbed("cancelValidation")
        safePersistDraft("cancelValidation")
    }

    // ---------------------------------------------------------------------
    // Validation handlers
    // ---------------------------------------------------------------------

    private suspend fun handleMain(answer: String) {
        mainAnswer = answer
        logAnswerDigest("main", answer)

        val out = validator.validateMain(questionId, answer)

        val followUp = if (out.status == ValidationStatus.NEED_FOLLOW_UP) {
            out.followUpQuestion.orEmpty().ifBlank { DEFAULT_FOLLOW_UP_MAIN }
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
            safePersistDraft("handleMain.need_follow_up")
        } else {
            stage = ChatStage.DONE
            val payload = buildCombined(mainAnswer, followUps)
            _completionPayload.value = payload
            _completion.tryEmit(payload)
            safePersistDraft("handleMain.done")
        }
    }

    private suspend fun handleFollowUp(userAnswer: String) {
        if (followUps.size >= maxFollowUps) {
            appendAssistantText("I have enough details. Let's move on.")
            stage = ChatStage.DONE
            val payload = buildCombined(mainAnswer, followUps)
            _completionPayload.value = payload
            _completion.tryEmit(payload)
            safePersistDraft("handleFollowUp.max_followups")
            return
        }

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
            safePersistDraft("handleFollowUp.need_follow_up")
        } else {
            stage = ChatStage.DONE
            val payload = buildCombined(mainAnswer, followUps)
            _completionPayload.value = payload
            _completion.tryEmit(payload)
            safePersistDraft("handleFollowUp.done")
        }
    }

    private fun reopenForEdit() {
        appendAssistantText("Re-opening this question for editing. Please submit your revised answer.")
        stage = ChatStage.AWAIT_MAIN
        mainAnswer = ""
        currentFollowUpQuestion = ""
        followUps.clear()
        _completionPayload.value = null
        safePersistDraft("reopenForEdit")
    }

    // ---------------------------------------------------------------------
    // Follow-up context and payload
    // ---------------------------------------------------------------------

    /**
     * Builds a follow-up context string intended to be consumed by repository-backed validators.
     *
     * Important:
     * - Wrap with FOLLOW_UP_HISTORY_BEGIN/END so FakeSlmRepository can reliably extract and parse
     *   the follow-up history from the overall prompt text.
     */
    private fun buildFollowUpContext(turns: List<FollowUpTurn>): String {
        if (turns.isEmpty()) return "(none)"
        return buildString {
            append("FOLLOW_UP_HISTORY_BEGIN\n")
            turns.forEachIndexed { idx, t ->
                append("FOLLOW_UP_${idx + 1}_Q: ").append(t.question.trim()).append('\n')
                append("FOLLOW_UP_${idx + 1}_A: ").append(t.answer.trim()).append('\n')
            }
            append("FOLLOW_UP_HISTORY_END")
        }.trim()
    }

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

    // ---------------------------------------------------------------------
    // Transcript mutations (must be atomic)
    // ---------------------------------------------------------------------

    private fun appendUser(text: String) {
        val msg = ChatMessage.user(
            id = UUID.randomUUID().toString(),
            text = text
        )
        _messages.update { it + msg }
        safePersistDraft("appendUser")
    }

    private fun appendAssistantText(text: String) {
        val msg = ChatMessage.assistant(
            id = UUID.randomUUID().toString(),
            assistantMessage = text,
            followUpQuestion = null,
            textFallback = text
        )
        _messages.update { it + msg }
        safePersistDraft("appendAssistantText")
    }

    private fun appendAssistantOutcome(
        assistantMessage: String,
        followUpQuestion: String?
    ) {
        val a = assistantMessage.trim()
        val q = followUpQuestion?.trim().orEmpty()

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

        _messages.update { it + msg }
        lastOutcomeAssistantMsgId = id

        if (pending != null) {
            clearPendingModelEmbed("appendAssistantOutcome(embed)")
        }

        safePersistDraft("appendAssistantOutcome")
    }

    private fun removeMessage(id: String) {
        _messages.update { it.filterNot { m -> m.id == id } }
    }

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list ->
            list.map { m -> if (m.id == id) transform(m) else m }
        }
    }

    private fun containsMessage(id: String): Boolean {
        return _messages.value.any { it.id == id }
    }

    // ---------------------------------------------------------------------
    // Model output embedding
    // ---------------------------------------------------------------------

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
                m.copy(
                    streamText = raw,
                    streamState = state,
                    streamCollapsed = true
                )
            }
            clearPendingModelEmbed("attachOrPendModelOutput(attached)")
            return
        }

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

    // ---------------------------------------------------------------------
    // Stream collector (MODEL bubble -> embed into ASSISTANT outcome)
    // ---------------------------------------------------------------------

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

                lastOutcomeAssistantMsgId = null
                clearPendingModelEmbed("beginSession")

                val id = UUID.randomUUID().toString()
                localStreamMsgId = id

                activeStreamSessionId = sessionId
                activeStreamMsgId = id

                val bubble = ChatMessage.modelStreaming(id).copy(text = "Validating…")
                _messages.update { it + bubble }

                safePersistDraft("stream.begin")
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
                val raw = buf.toString().ifBlank { errorMessage.orEmpty() }

                removeMessage(id)

                attachOrPendModelOutput(
                    sessionId = localActiveSession,
                    output = raw,
                    state = state
                )
            }

            try {
                streamBridge.events.collect { ev ->
                    when (ev) {
                        is ChatStreamEvent.Begin -> {
                            beginSession(ev.sessionId)
                        }

                        is ChatStreamEvent.Delta -> {
                            if (ev.text.isEmpty()) return@collect

                            val cancelledId = lastCancelledStreamSessionId
                            if (cancelledId > 0L && ev.sessionId == cancelledId) {
                                return@collect
                            }

                            val sessionMismatch = ev.sessionId != localActiveSession || localStreamMsgId == null
                            if (sessionMismatch) {
                                if (localActiveSession == 0L && localStreamMsgId == null) {
                                    beginSession(ev.sessionId)
                                } else {
                                    return@collect
                                }
                            }

                            buf.append(ev.text)

                            if (buf.length > maxModelCharsInUi) {
                                val drop = buf.length - maxModelCharsInUi
                                buf.delete(0, drop)
                                lastRenderedLen = (lastRenderedLen - drop).coerceAtLeast(0)
                            }

                            maybeUpdateUi(force = false)
                        }

                        is ChatStreamEvent.End -> {
                            if (ev.sessionId != localActiveSession) return@collect

                            val cancelledId = lastCancelledStreamSessionId
                            if (cancelledId > 0L && ev.sessionId == cancelledId) {
                                localStreamMsgId?.let { removeMessage(it) }
                                clearPendingModelEmbed("stream.end_ignored_cancelled")
                                resetLocalStreamState()
                                activeStreamSessionId = 0L
                                activeStreamMsgId = null
                                safePersistDraft("stream.end_ignored_cancelled")
                                return@collect
                            }

                            maybeUpdateUi(force = true)
                            finalizeAndRemoveModelBubble(state = ChatStreamState.ENDED)

                            resetLocalStreamState()
                            activeStreamSessionId = 0L
                            activeStreamMsgId = null
                            safePersistDraft("stream.end")
                        }

                        is ChatStreamEvent.Error -> {
                            if (ev.sessionId != localActiveSession) return@collect

                            val msg = ev.reason.trim().lowercase()
                            val suppressed =
                                (msg == "cancelled" || msg == "canceled") &&
                                        suppressNextCancelledError.getAndSet(false)

                            if (suppressed) {
                                localStreamMsgId?.let { removeMessage(it) }
                                clearPendingModelEmbed("stream.error_suppressed")
                            } else {
                                maybeUpdateUi(force = true)
                                finalizeAndRemoveModelBubble(
                                    state = ChatStreamState.ERROR,
                                    errorMessage = ev.reason
                                )
                            }

                            resetLocalStreamState()
                            activeStreamSessionId = 0L
                            activeStreamMsgId = null
                            safePersistDraft("stream.error")
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

    // ---------------------------------------------------------------------
    // Draft restore/seed + persistence
    // ---------------------------------------------------------------------

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

            activeStreamMsgId = null
            activeStreamSessionId = 0L
            lastOutcomeAssistantMsgId = null
            clearPendingModelEmbed("restoreOrSeed")

            Log.d(TAG, "Draft restored. qid=$questionId stage=$stage msgs=${draft.messages.size}")
            return
        }

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
        safePersistDraft("seed")
    }

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

    private fun safePersistDraft(reason: String) {
        runCatching { persistDraft() }
            .onFailure { t ->
                Log.w(TAG, "persistDraft failed (non-fatal). reason=$reason err=${t.javaClass.simpleName}", t)
            }
    }

    // ---------------------------------------------------------------------
    // Debug helpers (non-PII)
    // ---------------------------------------------------------------------

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

        /** UI throttling knobs for streaming deltas. */
        private const val STREAM_UI_UPDATE_INTERVAL_NS = 50_000_000L // 50ms
        private const val STREAM_MIN_UPDATE_CHARS = 16

        private const val DEFAULT_FOLLOW_UP_MAIN = "Could you add one concrete detail or example?"
        private const val DEFAULT_FOLLOW_UP_MORE = "Could you add one more concrete detail?"

        private const val CANCELLED_MESSAGE = "cancelled"
    }
}
