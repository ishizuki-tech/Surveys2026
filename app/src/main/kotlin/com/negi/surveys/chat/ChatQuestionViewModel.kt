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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.negi.surveys.logging.AppLog

/**
 * ViewModel that drives a chat-style Q/A with validation + multi follow-up support,
 * and survives navigation by persisting/restoring state via [ChatDraftStore].
 *
 * Streaming UX policy:
 * - While streaming: show ONE MODEL bubble (expanded).
 * - When stream ends: remove MODEL bubble and embed output into the next ASSISTANT outcome bubble
 *   as collapsible "Model output" details (collapsed by default).
 *
 * Robustness policy:
 * - Show TTFT by creating a placeholder MODEL bubble immediately on submit.
 * - Adopt the placeholder once a real stream session id appears (Begin/Delta).
 * - Close the "stream window" after validation completes to ignore late Begin/Delta.
 * - Gate late validator returns via attempt id to prevent transcript corruption after cancel.
 * - Synchronize mutable state snapshots for draft persistence (avoid ConcurrentModificationException).
 */
class ChatQuestionViewModel(
    private val questionId: String,
    private val prompt: String,
    private val validator: AnswerValidatorI,
    private val streamBridge: ChatStreamBridge,
    private val draftStore: ChatDraftStore,
    private val draftKey: DraftKey,
    private val maxFollowUps: Int = 5,
    private val allowResubmitAfterDone: Boolean = true,
    private val maxModelCharsInUi: Int = 8_000
) : ViewModel() {

    private val qid: String = questionId.trim()

    // ---------------------------------------------------------------------
    // Message storage (avoid O(N) list rebuild on streaming deltas)
    // ---------------------------------------------------------------------

    /**
     * Backing store for chat messages.
     *
     * Why:
     * - Streaming emits frequent tail updates.
     * - Rebuilding a new List on every delta is O(N) and gets expensive as history grows.
     *
     * Threading:
     * - All mutations are performed on Main (see startStreamCollector()).
     * - The emitted list is a lightweight wrapper that changes identity via a version bump.
     */
    private val messagesBacking: ArrayList<ChatMessage> = ArrayList(128)
    private val messageIndexById: HashMap<String, Int> = HashMap(128)
    private var messagesVersion: Long = 0L

    private class VersionedChatMessageList(
        private val version: Long,
        private val delegate: List<ChatMessage>
    ) : List<ChatMessage> by delegate {
        override fun equals(other: Any?): Boolean {
            return other is VersionedChatMessageList && version == other.version
        }

        override fun hashCode(): Int = version.hashCode()
    }

    private fun rebuildMessageIndex() {
        messageIndexById.clear()
        for (i in messagesBacking.indices) {
            messageIndexById[messagesBacking[i].id] = i
        }
    }

    private fun emitMessages(reason: String) {
        // Avoid noisy logs here; this runs frequently during streaming.
        messagesVersion += 1
        val view: List<ChatMessage> = Collections.unmodifiableList(messagesBacking)
        _messages.value = VersionedChatMessageList(version = messagesVersion, delegate = view)
    }

    private fun setMessagesInternal(list: List<ChatMessage>, reason: String) {
        messagesBacking.clear()
        messagesBacking.addAll(list)
        rebuildMessageIndex()
        emitMessages(reason)
    }

    private fun appendMessageInternal(msg: ChatMessage, reason: String) {
        messageIndexById[msg.id] = messagesBacking.size
        messagesBacking.add(msg)
        emitMessages(reason)
    }

    private fun removeMessageInternal(id: String, reason: String) {
        val idx = messageIndexById[id] ?: -1
        if (idx < 0) return

        if (idx == messagesBacking.lastIndex) {
            messagesBacking.removeAt(idx)
            messageIndexById.remove(id)
            emitMessages(reason)
            return
        }

        messagesBacking.removeAt(idx)
        rebuildMessageIndex()
        emitMessages(reason)
    }

    private fun updateMessageInternal(id: String, transform: (ChatMessage) -> ChatMessage, reason: String) {
        if (messagesBacking.isEmpty()) return

        // Fast path: streaming updates almost always target the tail message.
        val tailIndex = messagesBacking.lastIndex
        val idx = if (messagesBacking[tailIndex].id == id) {
            tailIndex
        } else {
            messageIndexById[id] ?: -1
        }
        if (idx < 0) return

        val old = messagesBacking[idx]
        val updated = transform(old)
        if (updated == old) return

        messagesBacking[idx] = updated
        emitMessages(reason)
    }

    private fun containsMessageInternal(id: String): Boolean {
        return messageIndexById.containsKey(id)
    }

    private val _messages =
        MutableStateFlow<List<ChatMessage>>(VersionedChatMessageList(version = 0L, delegate = emptyList()))
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

    // ---------------------------------------------------------------------
    // Mutable state that must be snapshotted safely for draft persistence
    // ---------------------------------------------------------------------

    private val stateLock = Any()

    private var stage: ChatStage = ChatStage.AWAIT_MAIN
    private var mainAnswer: String = ""
    private var currentFollowUpQuestion: String = ""
    private val followUps: MutableList<FollowUpTurn> = mutableListOf()

    /** Job for in-flight validation work so we can cancel on Back/Next/dispose. */
    private var validationJob: Job? = null

    /** Debounced draft persistence for inputDraft (avoid spamming store on every keystroke). */
    private var inputPersistJob: Job? = null

    /** Track active MODEL stream bubble so cancelValidation() can remove it immediately. */
    @Volatile private var activeStreamMsgId: String? = null
    @Volatile private var activeStreamSessionId: Long = 0L

    /** Remember the last cancelled stream session so we can ignore late deltas/ends. */
    @Volatile private var lastCancelledStreamSessionId: Long = 0L

    /** Stream window: when closed, ignore late Begin/Delta for new sessions. */
    @Volatile private var streamWindowOpen: Boolean = false

    /** Suppress "cancelled" error rendering when cancellation is intentional (Back/Next/dispose). */
    private val suppressNextCancelledError = AtomicBoolean(false)

    /** Attempt id gate to prevent late validator results from mutating state after cancel/navigation. */
    private val attemptCounter = AtomicLong(0L)
    @Volatile private var activeAttemptId: Long = 0L

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
        AppLog.d(TAG, "init qid=$qid draftKey=$draftKey stage=${withStateLock { stage }}")
    }

    override fun onCleared() {
        runCatching { cancelValidation("onCleared") }
        inputPersistJob?.cancel()
        super.onCleared()
    }

    fun setInput(text: String) {
        _input.value = text
        schedulePersistDraftForInput("setInput")
    }

    fun send() = submit()

    fun submit() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        if (_isBusy.value) return

        val localStage = withStateLock { stage }
        if (localStage == ChatStage.DONE && allowResubmitAfterDone) {
            reopenForEdit()
        } else if (localStage == ChatStage.DONE) {
            return
        }

        val attemptId = attemptCounter.incrementAndGet()
        activeAttemptId = attemptId

        _input.value = ""
        schedulePersistDraftForInput("submit.clearInput")
        appendUser(text)

        validationJob?.cancel()
        validationJob = null

        // TTFT: show immediately.
        openStreamWindow("submit")
        ensureStreamingPlaceholder("submit")

        validationJob = viewModelScope.launch {
            _isBusy.value = true
            try {
                when (withStateLock { stage }) {
                    ChatStage.AWAIT_MAIN -> handleMain(text, attemptId)
                    ChatStage.AWAIT_FOLLOW_UP -> handleFollowUp(text, attemptId)
                    ChatStage.DONE -> Unit
                }
            } finally {
                _isBusy.value = false
                validationJob = null

                // If no stream session ever started, close the window and remove placeholder.
                tryCloseStreamWindowIfIdle("submit.finally")
                safePersistDraft("submit.finally")
            }
        }
    }

    /**
     * Cancels any in-flight validation and stops streaming UI.
     */
    fun cancelValidation(reason: String) {
        AppLog.d(TAG, "cancelValidation qid=$qid reason=$reason")

        // Invalidate current attempt so late validator returns can't mutate state.
        activeAttemptId = attemptCounter.incrementAndGet()

        var didRequestCancel = false

        // Cancel active stream without requiring the VM to know the session id yet.
        val cancelledId = streamBridge.cancelActive(CANCELLED_MESSAGE)
        if (cancelledId > 0L) {
            didRequestCancel = true
            lastCancelledStreamSessionId = cancelledId
        } else {
            val sessionId = activeStreamSessionId
            if (sessionId > 0L) {
                didRequestCancel = true
                lastCancelledStreamSessionId = sessionId
                streamBridge.cancel(sessionId, CANCELLED_MESSAGE)
            }
        }

        suppressNextCancelledError.set(didRequestCancel)

        validationJob?.cancel()
        validationJob = null
        _isBusy.value = false

        // Close the stream window immediately to ignore late Begin/Delta.
        closeStreamWindow("cancelValidation")

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

    private suspend fun handleMain(answer: String, attemptId: Long) {
        if (!isAttemptActive(attemptId)) return

        withStateLock {
            mainAnswer = answer
        }
        logAnswerDigest("main", answer)

        val out = validator.validateMain(qid, answer)

        if (!isAttemptActive(attemptId)) return

        val followUp = if (out.status == ValidationStatus.NEED_FOLLOW_UP) {
            out.followUpQuestion.orEmpty().ifBlank { DEFAULT_FOLLOW_UP_MAIN }
        } else {
            null
        }

        appendAssistantOutcome(
            assistantMessage = out.assistantMessage,
            followUpQuestion = followUp
        )

        if (!isAttemptActive(attemptId)) return

        if (out.status == ValidationStatus.NEED_FOLLOW_UP) {
            withStateLock {
                currentFollowUpQuestion = followUp.orEmpty()
                stage = ChatStage.AWAIT_FOLLOW_UP
            }
            safePersistDraft("handleMain.need_follow_up")
        } else {
            val payload = withStateLock {
                stage = ChatStage.DONE
                buildCombined(mainAnswer, followUps)
            }
            _completionPayload.value = payload
            _completion.tryEmit(payload)
            safePersistDraft("handleMain.done")
        }

        // Close stream window if no session is active (defensive against late Begin/Delta).
        tryCloseStreamWindowIfIdle("handleMain.after")
    }

    private suspend fun handleFollowUp(userAnswer: String, attemptId: Long) {
        if (!isAttemptActive(attemptId)) return

        val localFollowUpsSize = withStateLock { followUps.size }
        if (localFollowUpsSize >= maxFollowUps) {
            appendAssistantText("I have enough details. Let's move on.")
            val payload = withStateLock {
                stage = ChatStage.DONE
                buildCombined(mainAnswer, followUps)
            }
            _completionPayload.value = payload
            _completion.tryEmit(payload)
            safePersistDraft("handleFollowUp.max_followups")
            tryCloseStreamWindowIfIdle("handleFollowUp.max_followups.after")
            return
        }

        val q = withStateLock { currentFollowUpQuestion }.ifEmpty { "(follow-up)" }

        withStateLock {
            followUps += FollowUpTurn(question = q, answer = userAnswer)
        }
        logAnswerDigest("followup#${withStateLock { followUps.size }}", userAnswer)

        // IMPORTANT:
        // Always pass a follow-up payload that explicitly contains the current Q/A,
        // in addition to any historical context. This prevents prompt templates that
        // expect "the answer" from missing the latest user input.
        val followUpPayloadForValidator = withStateLock {
            buildFollowUpContextForValidator(
                currentQuestion = q,
                currentAnswer = userAnswer,
                turns = followUps
            )
        }
        logFollowUpPayloadDigest(turns = withStateLock { followUps.size }, payload = followUpPayloadForValidator)

        val out = validator.validateFollowUp(
            questionId = qid,
            mainAnswer = withStateLock { mainAnswer }.trim(),
            followUpAnswer = followUpPayloadForValidator
        )

        if (!isAttemptActive(attemptId)) return

        val followUp = if (out.status == ValidationStatus.NEED_FOLLOW_UP) {
            out.followUpQuestion.orEmpty().ifBlank { DEFAULT_FOLLOW_UP_MORE }
        } else {
            null
        }

        appendAssistantOutcome(
            assistantMessage = out.assistantMessage,
            followUpQuestion = followUp
        )

        if (!isAttemptActive(attemptId)) return

        if (out.status == ValidationStatus.NEED_FOLLOW_UP) {
            withStateLock {
                currentFollowUpQuestion = followUp.orEmpty()
                stage = ChatStage.AWAIT_FOLLOW_UP
            }
            safePersistDraft("handleFollowUp.need_follow_up")
        } else {
            val payload = withStateLock {
                stage = ChatStage.DONE
                buildCombined(mainAnswer, followUps)
            }
            _completionPayload.value = payload
            _completion.tryEmit(payload)
            safePersistDraft("handleFollowUp.done")
        }

        tryCloseStreamWindowIfIdle("handleFollowUp.after")
    }

    private fun reopenForEdit() {
        appendAssistantText("Re-opening this question for editing. Please submit your revised answer.")
        withStateLock {
            stage = ChatStage.AWAIT_MAIN
            mainAnswer = ""
            currentFollowUpQuestion = ""
            followUps.clear()
        }
        _completionPayload.value = null
        safePersistDraft("reopenForEdit")
    }

    private suspend fun isAttemptActive(attemptId: Long): Boolean {
        if (activeAttemptId != attemptId) return false
        return currentCoroutineContext().isActive
    }

    // ---------------------------------------------------------------------
    // Stream window + placeholder
    // ---------------------------------------------------------------------

    private fun openStreamWindow(reason: String) {
        streamWindowOpen = true
        AppLog.d(TAG, "stream window OPEN reason=$reason")
    }

    private fun closeStreamWindow(reason: String) {
        streamWindowOpen = false
        AppLog.d(TAG, "stream window CLOSED reason=$reason")
    }

    /**
     * Close the stream window and remove placeholder only if the bridge has no active session.
     *
     * This prevents late Begin/Delta from creating new UI after validation completes.
     */
    private fun tryCloseStreamWindowIfIdle(reason: String) {
        val bridgeActive = streamBridge.statsSnapshot().activeSessionId
        val vmActive = activeStreamSessionId

        if (bridgeActive != 0L || vmActive != 0L) {
            return
        }

        val id = activeStreamMsgId
        if (id != null) {
            removeMessage(id)
        }

        activeStreamMsgId = null
        clearPendingModelEmbed("tryCloseStreamWindowIfIdle:$reason")
        closeStreamWindow(reason)
        safePersistDraft("stream.window.close:$reason")
    }

    /**
     * Ensures a TTFT placeholder MODEL bubble exists immediately after submit.
     */
    private fun ensureStreamingPlaceholder(reason: String) {
        activeStreamMsgId?.let { oldId ->
            removeMessage(oldId)
        }

        val id = UUID.randomUUID().toString()
        activeStreamMsgId = id
        activeStreamSessionId = 0L

        lastOutcomeAssistantMsgId = null
        clearPendingModelEmbed("ensureStreamingPlaceholder:$reason")

        val bubble = ChatMessage.modelStreaming(id, streamSessionId = null).copy(
            text = "Validating…"
        )

        appendMessageInternal(bubble, "stream.placeholder:$reason")
        safePersistDraft("stream.placeholder:$reason")
    }

    // ---------------------------------------------------------------------
    // Follow-up context and payload
    // ---------------------------------------------------------------------

    private fun buildFollowUpContext(turns: List<FollowUpTurn>): String {
        if (turns.isEmpty()) return ""
        return buildString {
            turns.forEachIndexed { idx, t ->
                append("FOLLOW_UP_${idx + 1}_Q: ").append(t.question.trim()).append('\n')
                append("FOLLOW_UP_${idx + 1}_A: ").append(t.answer.trim()).append('\n')
            }
        }.trim()
    }

    /**
     * Build a validator-friendly follow-up payload.
     *
     * Design:
     * - Always includes CURRENT_FOLLOW_UP_Q/A at the top.
     * - Also includes the full follow-up turn history using the legacy keys (FOLLOW_UP_1_Q/A...),
     *   so older prompt templates can continue to work.
     */
    private fun buildFollowUpContextForValidator(
        currentQuestion: String,
        currentAnswer: String,
        turns: List<FollowUpTurn>
    ): String {
        val q = currentQuestion.trim()
        val a = currentAnswer.trim()
        val history = buildFollowUpContext(turns)

        return buildString {
            append("CURRENT_FOLLOW_UP_Q: ").append(q).append('\n')
            append("CURRENT_FOLLOW_UP_A: ").append(a).append('\n')
            if (history.isNotBlank()) {
                append('\n')
                append("FOLLOW_UP_TURNS:\n")
                append(history)
            }
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
        appendMessageInternal(msg, "appendUser")
        safePersistDraft("appendUser")
    }

    private fun appendAssistantText(text: String) {
        val msg = ChatMessage.assistant(
            id = UUID.randomUUID().toString(),
            assistantMessage = text,
            followUpQuestion = null,
            textFallback = text
        )
        appendMessageInternal(msg, "appendAssistantText")
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
        val pendingSid = pendingModelSessionId.takeIf { it > 0L }

        val msg = if (pending != null && pendingModelState != ChatStreamState.NONE) {
            ChatMessage.assistantWithModelOutput(
                id = id,
                assistantMessage = assistantMessage,
                followUpQuestion = followUpQuestion,
                modelOutput = pending,
                modelState = pendingModelState,
                streamCollapsed = true,
                textFallback = composite,
                streamSessionId = pendingSid
            )
        } else {
            ChatMessage.assistant(
                id = id,
                assistantMessage = assistantMessage,
                followUpQuestion = followUpQuestion,
                textFallback = composite
            )
        }

        appendMessageInternal(msg, "appendAssistantOutcome")
        lastOutcomeAssistantMsgId = id

        if (pending != null) {
            clearPendingModelEmbed("appendAssistantOutcome(embed)")
        }

        safePersistDraft("appendAssistantOutcome")
    }

    private fun removeMessage(id: String) {
        removeMessageInternal(id, "removeMessage")
    }

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        updateMessageInternal(id, transform, "updateMessage")
    }

    private fun containsMessage(id: String): Boolean {
        return containsMessageInternal(id)
    }

    // ---------------------------------------------------------------------
    // Model output embedding
    // ---------------------------------------------------------------------

    private fun attachOrPendModelOutput(
        sessionId: Long,
        output: String,
        state: ChatStreamState
    ) {
        val raw = output.trimEnd().takeSafeCharsPreserveSurrogates(maxModelCharsInUi)
        if (raw.isBlank()) return

        val targetId = lastOutcomeAssistantMsgId
        val canAttachNow = targetId != null && containsMessage(targetId)

        if (canAttachNow) {
            updateMessage(targetId) { m ->
                m.copy(
                    streamText = raw,
                    streamState = state,
                    streamCollapsed = true,
                    streamSessionId = sessionId
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
        AppLog.d(TAG, "pending model embed cleared reason=$reason")
    }

    // ---------------------------------------------------------------------
    // Stream collector (MODEL bubble -> embed into ASSISTANT outcome)
    // ---------------------------------------------------------------------

    private fun startStreamCollector() {
        viewModelScope.launch(context = Dispatchers.Default) {
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

            suspend fun adoptOrCreateModelBubble(sessionId: Long) {
                if (localActiveSession == sessionId && localStreamMsgId != null) return

                localActiveSession = sessionId
                buf.setLength(0)
                lastUiUpdateNs = 0L
                lastRenderedLen = 0

                withContext(Dispatchers.Main.immediate) {
                    lastOutcomeAssistantMsgId = null
                    clearPendingModelEmbed("beginSession")

                    val existingId = activeStreamMsgId?.takeIf { containsMessage(it) }
                    val id = existingId ?: UUID.randomUUID().toString()
                    localStreamMsgId = id

                    activeStreamSessionId = sessionId
                    activeStreamMsgId = id

                    if (existingId != null) {
                        updateMessage(existingId) { m ->
                            m.copy(
                                role = ChatRole.MODEL,
                                text = if (m.text.isBlank()) "Validating…" else m.text,
                                streamText = m.streamText ?: "",
                                streamState = ChatStreamState.STREAMING,
                                streamCollapsed = false,
                                streamSessionId = sessionId
                            )
                        }
                    } else {
                        val bubble = ChatMessage.modelStreaming(id, streamSessionId = sessionId).copy(
                            text = "Validating…"
                        )
                        appendMessageInternal(bubble, "stream.begin")
                    }

                    safePersistDraft("stream.begin")
                }
            }

            suspend fun maybeUpdateUi(force: Boolean = false) {
                val id = localStreamMsgId ?: return
                val now = System.nanoTime()
                val deltaChars = buf.length - lastRenderedLen

                val shouldUpdate =
                    force ||
                            deltaChars >= STREAM_MIN_UPDATE_CHARS ||
                            (now - lastUiUpdateNs) >= STREAM_UI_UPDATE_INTERVAL_NS

                if (!shouldUpdate) return

                val rendered = buf.toString()

                withContext(Dispatchers.Main.immediate) {
                    updateMessage(id) { m ->
                        m.copy(
                            role = ChatRole.MODEL,
                            text = rendered.ifBlank { "Validating…" },
                            streamText = rendered,
                            streamState = ChatStreamState.STREAMING,
                            streamCollapsed = false,
                            streamSessionId = localActiveSession
                        )
                    }
                }

                lastUiUpdateNs = now
                lastRenderedLen = buf.length
            }

            suspend fun finalizeAndRemoveModelBubble(state: ChatStreamState, errorMessage: String? = null) {
                val id = localStreamMsgId ?: return
                val raw = buf.toString().ifBlank { errorMessage.orEmpty() }

                withContext(Dispatchers.Main.immediate) {
                    removeMessage(id)

                    attachOrPendModelOutput(
                        sessionId = localActiveSession,
                        output = raw,
                        state = state
                    )
                }
            }

            try {
                streamBridge.events.collect { ev ->
                    when (ev) {
                        is ChatStreamEvent.Begin -> {
                            if (!streamWindowOpen && localActiveSession == 0L) return@collect

                            val cancelledId = lastCancelledStreamSessionId
                            if (cancelledId > 0L && ev.sessionId == cancelledId) return@collect

                            if (localActiveSession != 0L && ev.sessionId != localActiveSession) {
                                AppLog.d(TAG, "stream Begin ignored (overlap) local=$localActiveSession new=${ev.sessionId}")
                                return@collect
                            }

                            adoptOrCreateModelBubble(ev.sessionId)
                        }

                        is ChatStreamEvent.Delta -> {
                            if (ev.text.isEmpty()) return@collect
                            if (!streamWindowOpen && localActiveSession == 0L) return@collect

                            val cancelledId = lastCancelledStreamSessionId
                            if (cancelledId > 0L && ev.sessionId == cancelledId) return@collect

                            val sessionMismatch = ev.sessionId != localActiveSession || localStreamMsgId == null
                            if (sessionMismatch) {
                                if (localActiveSession == 0L && localStreamMsgId == null) {
                                    adoptOrCreateModelBubble(ev.sessionId)
                                } else {
                                    return@collect
                                }
                            }

                            buf.append(ev.text)

                            if (buf.length > maxModelCharsInUi) {
                                val dropWanted = buf.length - maxModelCharsInUi
                                val drop = adjustDropForSurrogates(buf, dropWanted)
                                if (drop > 0) {
                                    buf.delete(0, drop)
                                    lastRenderedLen = (lastRenderedLen - drop).coerceAtLeast(0)
                                }
                            }

                            maybeUpdateUi(force = false)
                        }

                        is ChatStreamEvent.End -> {
                            if (ev.sessionId != localActiveSession) return@collect

                            val cancelledId = lastCancelledStreamSessionId
                            if (cancelledId > 0L && ev.sessionId == cancelledId) {
                                withContext(Dispatchers.Main.immediate) {
                                    suppressNextCancelledError.set(false)

                                    localStreamMsgId?.let { removeMessage(it) }
                                    clearPendingModelEmbed("stream.end_ignored_cancelled")
                                    resetLocalStreamState()
                                    activeStreamSessionId = 0L
                                    activeStreamMsgId = null
                                    lastCancelledStreamSessionId = 0L
                                    closeStreamWindow("stream.end_ignored_cancelled")
                                    safePersistDraft("stream.end_ignored_cancelled")
                                }
                                return@collect
                            }

                            maybeUpdateUi(force = true)
                            finalizeAndRemoveModelBubble(state = ChatStreamState.ENDED)

                            resetLocalStreamState()
                            withContext(Dispatchers.Main.immediate) {
                                activeStreamSessionId = 0L
                                activeStreamMsgId = null
                                closeStreamWindow("stream.end")
                                safePersistDraft("stream.end")
                            }
                        }

                        is ChatStreamEvent.Error -> {
                            if (ev.sessionId != localActiveSession) return@collect

                            val msg = ev.reason.trim().lowercase(Locale.US)
                            val isReplaced = (msg == REPLACED_MESSAGE)

                            val suppressedCancel =
                                (msg == "cancelled" || msg == "canceled") &&
                                        suppressNextCancelledError.getAndSet(false)

                            val cancelledId = lastCancelledStreamSessionId
                            val isCancelledSession = cancelledId > 0L && ev.sessionId == cancelledId

                            if (suppressedCancel || isCancelledSession || isReplaced) {
                                withContext(Dispatchers.Main.immediate) {
                                    localStreamMsgId?.let { removeMessage(it) }
                                    clearPendingModelEmbed("stream.error_suppressed")

                                    if (isCancelledSession) {
                                        lastCancelledStreamSessionId = 0L
                                    }
                                    suppressNextCancelledError.set(false)
                                }
                            } else {
                                maybeUpdateUi(force = true)
                                finalizeAndRemoveModelBubble(
                                    state = ChatStreamState.ERROR,
                                    errorMessage = ev.reason
                                )
                            }

                            resetLocalStreamState()
                            withContext(Dispatchers.Main.immediate) {
                                activeStreamSessionId = 0L
                                activeStreamMsgId = null
                                closeStreamWindow("stream.error")
                                safePersistDraft("stream.error")
                            }
                        }
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                AppLog.w(TAG, "stream collector crashed (non-fatal) err=${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Adjusts a front-drop count to avoid leaving a low surrogate as the first char.
     */
    private fun adjustDropForSurrogates(buf: StringBuilder, dropWanted: Int): Int {
        var drop = dropWanted.coerceAtLeast(0)
        if (drop <= 0) return 0
        if (drop >= buf.length) return buf.length

        val nextFirst = buf[drop]
        if (Character.isLowSurrogate(nextFirst)) {
            drop = (drop - 1).coerceAtLeast(0)
        }
        return drop
    }

    // ---------------------------------------------------------------------
    // Draft restore/seed + persistence
    // ---------------------------------------------------------------------

    private fun restoreOrSeed() {
        val draft = draftStore.load(draftKey)
        if (draft != null) {
            val sanitized = sanitizeRestoredMessages(draft.messages)

            withStateLock {
                stage = draft.stage
                mainAnswer = draft.mainAnswer
                followUps.clear()
                followUps.addAll(draft.followUps)
                currentFollowUpQuestion = draft.currentFollowUpQuestion
            }

            setMessagesInternal(sanitized, "restoreOrSeed")
            _completionPayload.value = draft.completionPayload
            _input.value = draft.inputDraft
            _isBusy.value = false

            activeStreamMsgId = null
            activeStreamSessionId = 0L
            lastOutcomeAssistantMsgId = null
            clearPendingModelEmbed("restoreOrSeed")
            closeStreamWindow("restoreOrSeed")

            AppLog.i(TAG, "draft restored qid=$qid stage=${withStateLock { stage }} msgs=${sanitized.size}")

            if (sanitized.size != draft.messages.size) {
                safePersistDraft("restore.sanitized")
            }
            return
        }

        val seeded = ChatMessage.assistant(
            id = UUID.randomUUID().toString(),
            assistantMessage = "Question: $qid",
            followUpQuestion = prompt,
            textFallback = "Question: $qid\n$prompt"
        )

        setMessagesInternal(listOf(seeded), "seed")

        withStateLock {
            stage = ChatStage.AWAIT_MAIN
            mainAnswer = ""
            currentFollowUpQuestion = ""
            followUps.clear()
        }

        _completionPayload.value = null
        _input.value = ""
        _isBusy.value = false

        clearPendingModelEmbed("seed")
        closeStreamWindow("seed")
        safePersistDraft("seed")
    }

    /**
     * Sanitizes restored messages to avoid resurrecting in-flight streaming UI.
     */
    private fun sanitizeRestoredMessages(raw: List<ChatMessage>): List<ChatMessage> {
        if (raw.isEmpty()) return raw

        var changed = false
        val out = ArrayList<ChatMessage>(raw.size)

        for (m in raw) {
            if (m.role == ChatRole.MODEL) {
                changed = true
                continue
            }

            if (m.streamState == ChatStreamState.STREAMING) {
                changed = true
                val hasText = !m.streamText.isNullOrBlank()
                out += if (hasText) {
                    m.copy(
                        streamState = ChatStreamState.ENDED,
                        streamCollapsed = true
                    )
                } else {
                    m.copy(
                        streamText = null,
                        streamState = ChatStreamState.NONE,
                        streamCollapsed = true,
                        streamSessionId = null
                    )
                }
                continue
            }

            out += m
        }

        return if (changed) out else raw
    }

    private fun persistDraft() {
        // Persist an immutable snapshot to avoid leaking the live backing list into storage.
        val messagesSnapshot = messagesBacking.toList()
        val snapshot = synchronized(stateLock) {
            ChatDraft(
                stage = stage,
                messages = messagesSnapshot,
                mainAnswer = mainAnswer,
                followUps = followUps.toList(),
                currentFollowUpQuestion = currentFollowUpQuestion,
                completionPayload = _completionPayload.value,
                inputDraft = _input.value
            )
        }
        draftStore.save(draftKey, snapshot)
    }

    private fun safePersistDraft(reason: String) {
        runCatching { persistDraft() }
            .onFailure { t ->
                AppLog.w(TAG, "persistDraft failed (non-fatal) reason=$reason err=${t.javaClass.simpleName}")
            }
    }

    private fun schedulePersistDraftForInput(reason: String) {
        inputPersistJob?.cancel()
        inputPersistJob = viewModelScope.launch {
            delay(INPUT_PERSIST_DEBOUNCE_MS)
            safePersistDraft("inputDebounce:$reason")
        }
    }

    // ---------------------------------------------------------------------
    // Debug helpers (non-PII)
    // ---------------------------------------------------------------------

    private fun logAnswerDigest(label: String, text: String) {
        val t = text.trim()
        val sha8 = sha256Hex(t).take(8)
        AppLog.d(TAG, "answer[$label] q=$qid len=${t.length} sha8=$sha8")
    }

    private fun logFollowUpPayloadDigest(turns: Int, payload: String) {
        val t = payload.trim()
        val sha8 = sha256Hex(t).take(8)
        AppLog.d(TAG, "followUpPayload q=$qid turns=$turns len=${t.length} sha8=$sha8")
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

    private fun String.takeSafeCharsPreserveSurrogates(limit: Int): String {
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

    private inline fun <T> withStateLock(block: () -> T): T {
        return synchronized(stateLock) { block() }
    }

    companion object {
        private const val TAG = "ChatQVM"

        private const val STREAM_UI_UPDATE_INTERVAL_NS = 50_000_000L // 50ms
        private const val STREAM_MIN_UPDATE_CHARS = 16

        private const val DEFAULT_FOLLOW_UP_MAIN = "Could you add one concrete detail or example?"
        private const val DEFAULT_FOLLOW_UP_MORE = "Could you add one more concrete detail?"

        private const val CANCELLED_MESSAGE = "cancelled"
        private const val REPLACED_MESSAGE = "replaced"

        private const val INPUT_PERSIST_DEBOUNCE_MS: Long = 250L
    }
}