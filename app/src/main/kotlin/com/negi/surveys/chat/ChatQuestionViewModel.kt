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

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.surveys.chat.ChatModels.ChatMessage
import com.negi.surveys.chat.ChatModels.ChatRole
import com.negi.surveys.chat.ChatModels.ChatStreamState
import com.negi.surveys.logging.AppLog
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel that drives a chat-style Q/A with validation + multi follow-up support,
 * and survives navigation by persisting/restoring state via [ChatDrafts.ChatDraftStore].
 *
 * Streaming policy:
 * - While validation is running, show one ephemeral MODEL bubble at a time.
 * - The raw streaming bubble is phase-tagged:
 *   - STEP1_EVAL
 *   - STEP2_FOLLOW_UP
 * - When a phase ends, keep the rendered MODEL bubble visible until the next submit,
 *   explicit cancellation, or terminal cleanup.
 * - Final assistant output is appended separately using structured fields from
 *   [ChatModels.ValidationOutcome].
 *
 * Persistence policy:
 * - Live UI may contain ephemeral MODEL bubbles.
 * - Persisted drafts must exclude ephemeral MODEL bubbles and keep only stable
 *   assistant-side step details.
 * - Legacy assistant streamText is migrated into step2Raw when possible.
 *
 * Robustness policy:
 * - Show TTFT by creating a placeholder MODEL bubble immediately on submit.
 * - Keep the "stream window" open for the whole validation attempt.
 * - Ignore late results using [activeAttemptId].
 * - Snapshot draft persistence on Main to avoid ConcurrentModificationException.
 * - Roll back transcript/state mutations when validation throws before producing
 *   a stable assistant outcome.
 */
class ChatQuestionViewModel(
    private val questionId: String,
    private val prompt: String,
    private val validator: ChatValidation.AnswerValidatorI,
    private val streamBridge: ChatStreamBridge,
    private val draftStore: ChatDrafts.ChatDraftStore,
    private val draftKey: ChatDrafts.DraftKey,
    private val maxFollowUps: Int = 5,
    private val allowResubmitAfterDone: Boolean = true,
    private val maxModelCharsInUi: Int = 8_000,
) : ViewModel() {

    private val qid: String = questionId.trim()

    // ---------------------------------------------------------------------
    // Message storage
    // ---------------------------------------------------------------------

    private val messagesBacking: ArrayList<ChatMessage> = ArrayList(128)
    private val messageIndexById: HashMap<String, Int> = HashMap(128)
    private var messagesVersion: Long = 0L

    private class VersionedChatMessageList(
        private val version: Long,
        private val delegate: List<ChatMessage>,
    ) : List<ChatMessage> by delegate {
        override fun equals(other: Any?): Boolean {
            return other is VersionedChatMessageList && version == other.version
        }

        override fun hashCode(): Int = version.hashCode()
    }

    private data class SubmitRollbackSnapshot(
        val stage: ChatDrafts.ChatStage,
        val mainAnswer: String,
        val currentFollowUpQuestion: String,
        val followUps: List<ChatDrafts.FollowUpTurn>,
        val completionPayload: String?,
    )

    private fun rebuildMessageIndex() {
        messageIndexById.clear()
        for (i in messagesBacking.indices) {
            messageIndexById[messagesBacking[i].id] = i
        }
    }

    private fun emitMessages(reason: String) {
        if (ENABLE_VERBOSE_EMIT_LOGS) {
            AppLog.d(
                TAG,
                "emitMessages reason=$reason size=${messagesBacking.size} ver=${messagesVersion + 1}",
            )
        }

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

    private fun updateMessageInternal(
        id: String,
        transform: (ChatMessage) -> ChatMessage,
        reason: String,
    ) {
        if (messagesBacking.isEmpty()) return

        val tailIndex = messagesBacking.lastIndex
        val idx = if (messagesBacking[tailIndex].id == id) {
            tailIndex
        } else {
            messageIndexById[id] ?: -1
        }
        if (idx < 0) return

        val old = messagesBacking[idx]
        val updated = transform(old)

        /**
         * Guard: message id must remain stable for index consistency.
         */
        if (updated.id != old.id) {
            AppLog.w(
                TAG,
                "updateMessageInternal refused: id changed old=${old.id.take(8)} new=${updated.id.take(8)}",
            )
            return
        }

        if (updated == old) return

        messagesBacking[idx] = updated
        emitMessages(reason)
    }

    private fun containsMessageInternal(id: String): Boolean {
        return messageIndexById.containsKey(id)
    }

    private val _messages =
        MutableStateFlow<List<ChatMessage>>(
            VersionedChatMessageList(version = 0L, delegate = emptyList()),
        )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /**
     * One-shot completion event.
     *
     * Notes:
     * - UI should prefer [completionPayload] for sticky state.
     */
    private val _completion = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val completion: SharedFlow<String> = _completion.asSharedFlow()

    /**
     * Sticky completion payload.
     */
    private val _completionPayload = MutableStateFlow<String?>(null)
    val completionPayload: StateFlow<String?> = _completionPayload.asStateFlow()

    // ---------------------------------------------------------------------
    // Mutable state guarded for draft persistence
    // ---------------------------------------------------------------------

    private val stateLock = Any()

    private var stage: ChatDrafts.ChatStage = ChatDrafts.ChatStage.AWAIT_MAIN
    private var mainAnswer: String = ""
    private var currentFollowUpQuestion: String = ""
    private val followUps: MutableList<ChatDrafts.FollowUpTurn> = mutableListOf()

    private var validationJob: Job? = null
    private var inputPersistJob: Job? = null

    /**
     * Active or most recently retained MODEL bubble.
     */
    @Volatile
    private var activeStreamMsgId: String? = null

    /**
     * Active bridge session currently mirrored by this VM.
     */
    @Volatile
    private var activeStreamSessionId: Long = 0L

    /**
     * Last explicitly cancelled session id.
     */
    @Volatile
    private var lastCancelledStreamSessionId: Long = 0L

    /**
     * The stream window is open while a validation attempt is alive.
     */
    @Volatile
    private var streamWindowOpen: Boolean = false

    /**
     * Suppress a cancelled error bubble when cancellation is intentional.
     */
    private val suppressNextCancelledError = AtomicBoolean(false)

    /**
     * Attempt gate used to ignore late validator returns.
     */
    private val attemptCounter = AtomicLong(0L)

    @Volatile
    private var activeAttemptId: Long = 0L

    private val streamCollector = StreamCollector()

    init {
        restoreOrSeed()
        streamCollector.start()
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

        val stageBeforeSubmit = withStateLock { stage }
        if (stageBeforeSubmit == ChatDrafts.ChatStage.DONE && allowResubmitAfterDone) {
            reopenForEdit()
        } else if (stageBeforeSubmit == ChatDrafts.ChatStage.DONE) {
            return
        }

        val rollbackSnapshot = captureRollbackSnapshot()

        val attemptId = attemptCounter.incrementAndGet()
        activeAttemptId = attemptId

        _input.value = ""
        schedulePersistDraftForInput("submit.clearInput")

        val submittedUserMessageId = appendUser(text)

        validationJob?.cancel()
        validationJob = null

        openStreamWindow("submit")
        ensureStreamingPlaceholder("submit")

        validationJob = viewModelScope.launch {
            _isBusy.value = true
            try {
                when (withStateLock { stage }) {
                    ChatDrafts.ChatStage.AWAIT_MAIN -> handleMain(text, attemptId)
                    ChatDrafts.ChatStage.AWAIT_FOLLOW_UP -> handleFollowUp(text, attemptId)
                    ChatDrafts.ChatStage.DONE -> Unit
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                handleValidationFailure(
                    submittedText = text,
                    submittedUserMessageId = submittedUserMessageId,
                    rollbackSnapshot = rollbackSnapshot,
                    attemptId = attemptId,
                    error = t,
                )
            } finally {
                _isBusy.value = false
                validationJob = null
                tryCloseStreamWindowIfIdle("submit.finally")
                safePersistDraft("submit.finally")
            }
        }
    }

    /**
     * Cancel any in-flight validation and stop ephemeral streaming UI.
     */
    fun cancelValidation(reason: String) {
        AppLog.d(TAG, "cancelValidation qid=$qid reason=$reason")

        activeAttemptId = attemptCounter.incrementAndGet()

        val didRequestCancel = requestStreamCancel()
        suppressNextCancelledError.set(didRequestCancel)

        validationJob?.cancel()
        validationJob = null
        _isBusy.value = false

        closeStreamWindow("cancelValidation")

        val cleanup: () -> Unit = {
            clearActiveStreamUi("cancelValidation")
            safePersistDraft("cancelValidation")
        }

        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            cleanup()
        } else {
            viewModelScope.launch(Dispatchers.Main.immediate) { cleanup() }
        }
    }

    private suspend fun handleValidationFailure(
        submittedText: String,
        submittedUserMessageId: String,
        rollbackSnapshot: SubmitRollbackSnapshot,
        attemptId: Long,
        error: Throwable,
    ) {
        if (activeAttemptId != attemptId) {
            AppLog.d(
                TAG,
                "validation failure ignored: stale attempt q=$qid attempt=$attemptId err=${error.javaClass.simpleName}",
            )
            return
        }

        AppLog.w(
            TAG,
            "validation failed q=$qid stage=${rollbackSnapshot.stage} attempt=$attemptId err=${error.javaClass.simpleName}",
        )

        val didRequestCancel = requestStreamCancel()
        suppressNextCancelledError.set(didRequestCancel)
        closeStreamWindow("validationFailure")

        withContext(Dispatchers.Main.immediate) {
            if (activeAttemptId != attemptId) return@withContext

            restoreRollbackSnapshot(rollbackSnapshot)
            clearActiveStreamUi("validationFailure")

            if (containsMessage(submittedUserMessageId)) {
                removeMessage(submittedUserMessageId)
            }

            _input.value = submittedText
            appendAssistantText(buildValidationFailureMessage(error, rollbackSnapshot.stage))
            safePersistDraft("validationFailure")
        }
    }

    private fun requestStreamCancel(): Boolean {
        var didRequestCancel = false

        val cancelledId = streamBridge.cancelActive(ChatStreamEvent.Codes.CANCELLED)
        if (cancelledId > 0L) {
            didRequestCancel = true
            lastCancelledStreamSessionId = cancelledId
            return didRequestCancel
        }

        val sessionId = activeStreamSessionId
        if (sessionId > 0L) {
            didRequestCancel = true
            lastCancelledStreamSessionId = sessionId
            streamBridge.cancel(sessionId, ChatStreamEvent.Codes.CANCELLED)
        }

        return didRequestCancel
    }

    private fun clearActiveStreamUi(reason: String) {
        val msgId = activeStreamMsgId
        if (msgId != null) {
            removeMessage(msgId)
        }

        activeStreamMsgId = null
        activeStreamSessionId = 0L

        AppLog.d(TAG, "clearActiveStreamUi reason=$reason")
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

        val followUp = if (out.status == ChatModels.ValidationStatus.NEED_FOLLOW_UP) {
            out.followUpQuestion.orEmpty().ifBlank { DEFAULT_FOLLOW_UP_MAIN }
        } else {
            null
        }

        appendAssistantOutcome(
            assistantMessage = out.assistantMessage,
            followUpQuestion = followUp,
            evalStatus = out.evalStatus,
            evalScore = out.evalScore,
            evalReason = out.evalReason,
            step1Raw = out.step1Raw,
            step2Raw = out.step2Raw,
        )

        if (!isAttemptActive(attemptId)) return

        if (out.status == ChatModels.ValidationStatus.NEED_FOLLOW_UP) {
            withStateLock {
                currentFollowUpQuestion = followUp.orEmpty()
                stage = ChatDrafts.ChatStage.AWAIT_FOLLOW_UP
            }
            safePersistDraft("handleMain.need_follow_up")
        } else {
            completeDoneAndPersist("handleMain.done")
        }

        tryCloseStreamWindowIfIdle("handleMain.after")
    }

    private suspend fun handleFollowUp(userAnswer: String, attemptId: Long) {
        if (!isAttemptActive(attemptId)) return

        val localFollowUpsSize = withStateLock { followUps.size }
        if (localFollowUpsSize >= maxFollowUps) {
            appendAssistantText("I have enough details. Let's move on.")
            completeDoneAndPersist("handleFollowUp.max_followups")
            tryCloseStreamWindowIfIdle("handleFollowUp.max_followups.after")
            return
        }

        val q = withStateLock { currentFollowUpQuestion }.ifEmpty { "(follow-up)" }

        withStateLock {
            followUps += ChatDrafts.FollowUpTurn(question = q, answer = userAnswer)
        }
        logAnswerDigest("followup#${withStateLock { followUps.size }}", userAnswer)

        val followUpPayloadForValidator = withStateLock {
            buildFollowUpContextForValidator(
                currentQuestion = q,
                currentAnswer = userAnswer,
                turns = followUps,
            )
        }
        logFollowUpPayloadDigest(
            turns = withStateLock { followUps.size },
            payload = followUpPayloadForValidator,
        )

        val out = validator.validateFollowUp(
            questionId = qid,
            mainAnswer = withStateLock { mainAnswer }.trim(),
            followUpAnswer = followUpPayloadForValidator,
        )
        if (!isAttemptActive(attemptId)) return

        val followUp = if (out.status == ChatModels.ValidationStatus.NEED_FOLLOW_UP) {
            out.followUpQuestion.orEmpty().ifBlank { DEFAULT_FOLLOW_UP_MORE }
        } else {
            null
        }

        appendAssistantOutcome(
            assistantMessage = out.assistantMessage,
            followUpQuestion = followUp,
            evalStatus = out.evalStatus,
            evalScore = out.evalScore,
            evalReason = out.evalReason,
            step1Raw = out.step1Raw,
            step2Raw = out.step2Raw,
        )

        if (!isAttemptActive(attemptId)) return

        if (out.status == ChatModels.ValidationStatus.NEED_FOLLOW_UP) {
            withStateLock {
                currentFollowUpQuestion = followUp.orEmpty()
                stage = ChatDrafts.ChatStage.AWAIT_FOLLOW_UP
            }
            safePersistDraft("handleFollowUp.need_follow_up")
        } else {
            completeDoneAndPersist("handleFollowUp.done")
        }

        tryCloseStreamWindowIfIdle("handleFollowUp.after")
    }

    private fun completeDoneAndPersist(reason: String) {
        val payload = withStateLock {
            stage = ChatDrafts.ChatStage.DONE
            buildCombined(mainAnswer, followUps)
        }
        _completionPayload.value = payload
        _completion.tryEmit(payload)
        safePersistDraft(reason)
    }

    private fun reopenForEdit() {
        appendAssistantText("Re-opening this question for editing. Please submit your revised answer.")
        withStateLock {
            stage = ChatDrafts.ChatStage.AWAIT_MAIN
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
    // Stream window
    // ---------------------------------------------------------------------

    private fun openStreamWindow(reason: String) {
        streamWindowOpen = true
        AppLog.d(TAG, "stream window OPEN reason=$reason")
    }

    private fun closeStreamWindow(reason: String) {
        streamWindowOpen = false
        AppLog.d(TAG, "stream window CLOSED reason=$reason")
    }

    private fun tryCloseStreamWindowIfIdle(reason: String) {
        val bridgeActive = streamBridge.statsSnapshot().activeSessionId
        val vmActive = activeStreamSessionId

        if (bridgeActive != 0L || vmActive != 0L) {
            return
        }

        val id = activeStreamMsgId
        if (id != null) {
            updateMessage(id) { m ->
                val retained = m.streamText?.takeIf { it.isNotBlank() } ?: m.text
                m.copy(
                    role = ChatRole.MODEL,
                    text = retained,
                    streamText = retained,
                    streamState = if (retained.isNotBlank()) ChatStreamState.ENDED else ChatStreamState.NONE,
                    streamCollapsed = false,
                    streamSessionId = null,
                )
            }
        }

        activeStreamSessionId = 0L
        closeStreamWindow(reason)
        safePersistDraft("stream.window.close:$reason")
    }

    private fun ensureStreamingPlaceholder(reason: String) {
        activeStreamMsgId?.let { oldId ->
            removeMessage(oldId)
        }

        val id = UUID.randomUUID().toString()
        activeStreamMsgId = id
        activeStreamSessionId = 0L

        val bubble = ChatMessage.modelStreaming(
            id = id,
            text = phasePlaceholderText(ChatStreamEvent.Phase.STEP1_EVAL),
            streamSessionId = null,
        )

        appendMessageInternal(bubble, "stream.placeholder:$reason")
        safePersistDraft("stream.placeholder:$reason")
    }

    private fun phasePlaceholderText(phase: ChatStreamEvent.Phase): String {
        return when (phase) {
            ChatStreamEvent.Phase.STEP1_EVAL -> "[Step 1] Validating…"
            ChatStreamEvent.Phase.STEP2_FOLLOW_UP -> "[Step 2] Generating follow-up…"
        }
    }

    private fun phasePrefix(phase: ChatStreamEvent.Phase): String {
        return when (phase) {
            ChatStreamEvent.Phase.STEP1_EVAL -> "[Step 1]\n"
            ChatStreamEvent.Phase.STEP2_FOLLOW_UP -> "[Step 2]\n"
        }
    }

    private fun renderPhaseText(
        phase: ChatStreamEvent.Phase?,
        body: String,
    ): String {
        val raw = body
        return when {
            phase != null && raw.isNotEmpty() -> phasePrefix(phase) + raw
            phase != null -> phasePlaceholderText(phase)
            raw.isNotEmpty() -> raw
            else -> "Validating…"
        }
    }

    // ---------------------------------------------------------------------
    // Follow-up context
    // ---------------------------------------------------------------------

    private fun buildFollowUpContext(turns: List<ChatDrafts.FollowUpTurn>): String {
        if (turns.isEmpty()) return ""
        return buildString {
            turns.forEachIndexed { idx, t ->
                append("FOLLOW_UP_${idx + 1}_Q: ").append(t.question.trim()).append('\n')
                append("FOLLOW_UP_${idx + 1}_A: ").append(t.answer.trim()).append('\n')
            }
        }.trim()
    }

    private fun buildFollowUpContextForValidator(
        currentQuestion: String,
        currentAnswer: String,
        turns: List<ChatDrafts.FollowUpTurn>,
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

    private fun buildCombined(main: String, turns: List<ChatDrafts.FollowUpTurn>): String {
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
    // Transcript mutations
    // ---------------------------------------------------------------------

    private fun appendUser(text: String): String {
        val id = UUID.randomUUID().toString()
        val msg = ChatMessage.user(
            id = id,
            text = text,
        )
        appendMessageInternal(msg, "appendUser")
        safePersistDraft("appendUser")
        return id
    }

    private fun appendAssistantText(text: String) {
        val msg = ChatMessage.assistant(
            id = UUID.randomUUID().toString(),
            assistantMessage = text,
            followUpQuestion = null,
            textFallback = text,
        )
        appendMessageInternal(msg, "appendAssistantText")
        safePersistDraft("appendAssistantText")
    }

    private fun appendAssistantOutcome(
        assistantMessage: String,
        followUpQuestion: String?,
        evalStatus: ChatModels.ValidationStatus?,
        evalScore: Int?,
        evalReason: String?,
        step1Raw: String?,
        step2Raw: String?,
    ) {
        val a = assistantMessage.trim()
        val q = followUpQuestion.normalizedOptionalText()
        val cleanedStep1Raw = step1Raw.normalizedOptionalText()
        val cleanedStep2Raw = step2Raw.normalizedOptionalText()

        val composite = buildString {
            if (a.isNotEmpty()) append(a)
            if (!q.isNullOrEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(q)
            }
        }.trim()

        val id = UUID.randomUUID().toString()

        val msg = ChatMessage.assistant(
            id = id,
            assistantMessage = a,
            followUpQuestion = q,
            textFallback = composite,
            evalStatus = evalStatus,
            evalScore = evalScore,
            evalReason = evalReason?.trim()?.takeIf { it.isNotEmpty() },
            step1Raw = cleanedStep1Raw,
            step2Raw = cleanedStep2Raw,
        )

        appendMessageInternal(msg, "appendAssistantOutcome")
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
    // Stream collector
    // ---------------------------------------------------------------------

    private inner class StreamCollector {

        private var localActiveSession = 0L
        private var localPhase: ChatStreamEvent.Phase? = null
        private var localStreamMsgId: String? = null
        private val buf = StringBuilder()

        private var lastUiUpdateNs = 0L
        private var lastRenderedLen = 0

        fun start() {
            viewModelScope.launch(context = Dispatchers.Default) {
                try {
                    streamBridge.events.collect { ev ->
                        when (ev) {
                            is ChatStreamEvent.Begin -> onBegin(ev.sessionId, ev.phase)
                            is ChatStreamEvent.Delta -> onDelta(ev.sessionId, ev.phase, ev.text)
                            is ChatStreamEvent.End -> onEnd(ev.sessionId, ev.phase)
                            is ChatStreamEvent.Error -> onError(ev.sessionId, ev.phase, ev.token, ev.code)
                        }
                    }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    AppLog.w(TAG, "stream collector crashed (non-fatal) err=${t.javaClass.simpleName}")
                }
            }
        }

        private suspend fun onBegin(
            sessionId: Long,
            phase: ChatStreamEvent.Phase,
        ) {
            if (!streamWindowOpen && localActiveSession == 0L) return
            if (isCancelledSession(sessionId)) return

            if (localActiveSession != 0L && sessionId != localActiveSession) {
                AppLog.d(TAG, "stream Begin ignored (overlap) local=$localActiveSession new=$sessionId")
                return
            }

            adoptOrCreateModelBubble(sessionId, phase)
        }

        private suspend fun onDelta(
            sessionId: Long,
            phase: ChatStreamEvent.Phase,
            text: String,
        ) {
            if (text.isEmpty()) return
            if (!streamWindowOpen && localActiveSession == 0L) return
            if (isCancelledSession(sessionId)) return

            val sessionMismatch =
                sessionId != localActiveSession ||
                        localStreamMsgId == null ||
                        localPhase != phase

            if (sessionMismatch) {
                if (localActiveSession == 0L && localStreamMsgId == null) {
                    adoptOrCreateModelBubble(sessionId, phase)
                } else {
                    return
                }
            }

            buf.append(text)
            enforceMaxChars()

            maybeUpdateUi(force = false)
        }

        private suspend fun onEnd(
            sessionId: Long,
            phase: ChatStreamEvent.Phase,
        ) {
            if (sessionId != localActiveSession) return
            if (phase != localPhase) return

            if (isCancelledSession(sessionId)) {
                withContext(Dispatchers.Main.immediate) {
                    suppressNextCancelledError.set(false)

                    localStreamMsgId?.let { removeMessage(it) }

                    resetLocalState()
                    clearVmStreamState("stream.end_ignored_cancelled")

                    lastCancelledStreamSessionId = 0L
                    safePersistDraft("stream.end_ignored_cancelled")
                }
                return
            }

            val rendered = renderPhaseText(phase = phase, body = buf.toString())

            withContext(Dispatchers.Main.immediate) {
                val id = localStreamMsgId
                if (id != null) {
                    updateMessage(id) { m ->
                        m.copy(
                            role = ChatRole.MODEL,
                            text = rendered,
                            streamText = rendered,
                            streamState = ChatStreamState.ENDED,
                            streamCollapsed = false,
                            streamSessionId = null,
                        )
                    }
                }

                resetLocalState()
                clearVmStreamSessionAfterTerminal("stream.end")
                safePersistDraft("stream.end")
            }
        }

        private suspend fun onError(
            sessionId: Long,
            phase: ChatStreamEvent.Phase,
            token: String,
            code: String?,
        ) {
            if (sessionId != localActiveSession) return
            if (phase != localPhase) return

            val tokenNorm = token.trim().lowercase(Locale.US)
            val codeNorm = code?.trim()?.lowercase(Locale.US).orEmpty()

            val isReplaced =
                tokenNorm == ChatStreamEvent.Codes.REPLACED ||
                        codeNorm == ChatStreamEvent.Codes.REPLACED

            val suppressedCancel =
                (tokenNorm == ChatStreamEvent.Codes.CANCELLED ||
                        codeNorm == ChatStreamEvent.Codes.CANCELLED) &&
                        suppressNextCancelledError.getAndSet(false)

            val cancelledSession = isCancelledSession(sessionId)

            withContext(Dispatchers.Main.immediate) {
                if (suppressedCancel || cancelledSession || isReplaced) {
                    localStreamMsgId?.let { removeMessage(it) }

                    if (cancelledSession) {
                        lastCancelledStreamSessionId = 0L
                    }
                    suppressNextCancelledError.set(false)
                } else {
                    localStreamMsgId?.let { removeMessage(it) }
                }

                resetLocalState()
                clearVmStreamState("stream.error")
                safePersistDraft("stream.error")
            }
        }

        private fun isCancelledSession(sessionId: Long): Boolean {
            val cancelledId = lastCancelledStreamSessionId
            return cancelledId > 0L && sessionId == cancelledId
        }

        private suspend fun adoptOrCreateModelBubble(
            sessionId: Long,
            phase: ChatStreamEvent.Phase,
        ) {
            if (localActiveSession == sessionId && localStreamMsgId != null && localPhase == phase) {
                return
            }

            localActiveSession = sessionId
            localPhase = phase
            buf.setLength(0)
            lastUiUpdateNs = 0L
            lastRenderedLen = 0

            withContext(Dispatchers.Main.immediate) {
                val existingId = activeStreamMsgId?.takeIf { containsMessage(it) }
                val id = existingId ?: UUID.randomUUID().toString()
                localStreamMsgId = id

                activeStreamSessionId = sessionId
                activeStreamMsgId = id

                val label = phasePlaceholderText(phase)

                if (existingId != null) {
                    updateMessage(existingId) { m ->
                        m.copy(
                            role = ChatRole.MODEL,
                            text = label,
                            streamText = label,
                            streamState = ChatStreamState.STREAMING,
                            streamCollapsed = false,
                            streamSessionId = sessionId,
                        )
                    }
                } else {
                    appendMessageInternal(
                        ChatMessage.modelStreaming(
                            id = id,
                            text = label,
                            streamSessionId = sessionId,
                        ),
                        "stream.begin",
                    )
                }

                safePersistDraft("stream.begin")
            }
        }

        private suspend fun maybeUpdateUi(force: Boolean) {
            val id = localStreamMsgId ?: return

            val now = System.nanoTime()
            val deltaChars = buf.length - lastRenderedLen

            val shouldUpdate =
                force ||
                        deltaChars >= STREAM_MIN_UPDATE_CHARS ||
                        (now - lastUiUpdateNs) >= STREAM_UI_UPDATE_INTERVAL_NS

            if (!shouldUpdate) return

            val rendered = renderPhaseText(
                phase = localPhase,
                body = buf.toString(),
            )

            withContext(Dispatchers.Main.immediate) {
                updateMessage(id) { m ->
                    m.copy(
                        role = ChatRole.MODEL,
                        text = rendered,
                        streamText = rendered,
                        streamState = ChatStreamState.STREAMING,
                        streamCollapsed = false,
                        streamSessionId = localActiveSession,
                    )
                }
            }

            lastUiUpdateNs = now
            lastRenderedLen = buf.length
        }

        private fun resetLocalState() {
            localActiveSession = 0L
            localPhase = null
            localStreamMsgId = null
            buf.setLength(0)
            lastUiUpdateNs = 0L
            lastRenderedLen = 0
        }

        private fun enforceMaxChars() {
            if (buf.length <= maxModelCharsInUi) return

            val dropWanted = buf.length - maxModelCharsInUi
            val drop = adjustDropForSurrogates(buf, dropWanted)
            if (drop > 0) {
                buf.delete(0, drop)
                lastRenderedLen = (lastRenderedLen - drop).coerceAtLeast(0)
            }
        }
    }

    private fun clearVmStreamSessionAfterTerminal(reason: String) {
        activeStreamSessionId = 0L
        AppLog.d(TAG, "vm stream session cleared reason=$reason")
    }

    private fun clearVmStreamState(reason: String) {
        activeStreamSessionId = 0L
        activeStreamMsgId = null
        AppLog.d(TAG, "vm stream cleared reason=$reason")
    }

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
    // Draft restore / seed
    // ---------------------------------------------------------------------

    private fun restoreOrSeed() {
        val draft = draftStore.load(draftKey)
        if (draft != null) {
            val sanitized = normalizePersistedMessages(draft.messages)

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
            closeStreamWindow("restoreOrSeed")

            AppLog.i(TAG, "draft restored qid=$qid stage=${withStateLock { stage }} msgs=${sanitized.size}")

            if (sanitized.size != draft.messages.size || sanitized != draft.messages) {
                safePersistDraft("restore.sanitized")
            }
            return
        }

        val seeded = ChatMessage.assistant(
            id = UUID.randomUUID().toString(),
            assistantMessage = "Question: $qid",
            followUpQuestion = prompt,
            textFallback = "Question: $qid\n$prompt",
        )

        setMessagesInternal(listOf(seeded), "seed")

        withStateLock {
            stage = ChatDrafts.ChatStage.AWAIT_MAIN
            mainAnswer = ""
            currentFollowUpQuestion = ""
            followUps.clear()
        }

        _completionPayload.value = null
        _input.value = ""
        _isBusy.value = false

        closeStreamWindow("seed")
        safePersistDraft("seed")
    }

    /**
     * Build a persisted transcript snapshot from the live in-memory transcript.
     *
     * Rules:
     * - Drop MODEL bubbles because they are ephemeral UI-only artifacts.
     * - Clear transient stream fields from persisted messages.
     * - Migrate legacy assistant streamText into step2Raw when step details are absent.
     * - Normalize optional assistant-side detail strings.
     */
    private fun buildPersistableMessagesSnapshot(): List<ChatMessage> {
        return normalizePersistedMessages(messagesBacking.toList())
    }

    /**
     * Normalize persisted/restored messages for a stable non-streaming transcript.
     */
    private fun normalizePersistedMessages(raw: List<ChatMessage>): List<ChatMessage> {
        if (raw.isEmpty()) return raw

        var changed = false
        val out = ArrayList<ChatMessage>(raw.size)

        for (m in raw) {
            val normalized = normalizePersistedMessageOrNull(m)
            if (normalized == null) {
                changed = true
                continue
            }
            if (normalized != m) {
                changed = true
            }
            out += normalized
        }

        return if (changed) out else raw
    }

    /**
     * Convert one live message into a stable persisted/restored message.
     *
     * Returns null when the message must not be persisted.
     */
    private fun normalizePersistedMessageOrNull(message: ChatMessage): ChatMessage? {
        if (message.role == ChatRole.MODEL) {
            return null
        }

        var cur = message

        val normalizedAssistantMessage = cur.assistantMessage.normalizedOptionalText()
        val normalizedFollowUpQuestion = cur.followUpQuestion.normalizedOptionalText()
        val normalizedEvalReason = cur.evalReason.normalizedOptionalText()

        var normalizedStep1Raw = cur.step1Raw.normalizedOptionalText()
        var normalizedStep2Raw = cur.step2Raw.normalizedOptionalText()

        val legacyStream = cur.streamText.normalizedOptionalText()

        if (
            cur.role == ChatRole.ASSISTANT &&
            legacyStream != null &&
            normalizedStep1Raw == null &&
            normalizedStep2Raw == null
        ) {
            normalizedStep2Raw = legacyStream
        }

        cur = cur.copy(
            assistantMessage = normalizedAssistantMessage,
            followUpQuestion = normalizedFollowUpQuestion,
            evalReason = normalizedEvalReason,
            step1Raw = normalizedStep1Raw,
            step2Raw = normalizedStep2Raw,
        )

        val targetCollapsed = when (cur.role) {
            ChatRole.ASSISTANT -> true
            ChatRole.USER -> cur.streamCollapsed
            ChatRole.MODEL -> cur.streamCollapsed
        }

        if (
            cur.streamText != null ||
            cur.streamState != ChatStreamState.NONE ||
            cur.streamSessionId != null ||
            cur.streamCollapsed != targetCollapsed
        ) {
            cur = cur.copy(
                streamText = null,
                streamState = ChatStreamState.NONE,
                streamCollapsed = targetCollapsed,
                streamSessionId = null,
            )
        }

        return cur
    }

    private fun String?.normalizedOptionalText(): String? {
        return this?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------------
    // Draft persistence
    // ---------------------------------------------------------------------

    private fun persistDraftOnMain() {
        val messagesSnapshot = buildPersistableMessagesSnapshot()
        val snapshot = synchronized(stateLock) {
            ChatDrafts.ChatDraft(
                stage = stage,
                messages = messagesSnapshot,
                mainAnswer = mainAnswer,
                followUps = followUps.toList(),
                currentFollowUpQuestion = currentFollowUpQuestion,
                completionPayload = _completionPayload.value,
                inputDraft = _input.value,
            )
        }
        draftStore.save(draftKey, snapshot)
    }

    private fun safePersistDraft(reason: String) {
        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            runCatching { persistDraftOnMain() }
                .onFailure { t ->
                    AppLog.w(TAG, "persistDraft failed (non-fatal) reason=$reason err=${t.javaClass.simpleName}")
                }
            return
        }

        viewModelScope.launch(Dispatchers.Main.immediate) {
            runCatching { persistDraftOnMain() }
                .onFailure { t ->
                    AppLog.w(TAG, "persistDraft failed (non-fatal) reason=$reason err=${t.javaClass.simpleName}")
                }
        }
    }

    private fun schedulePersistDraftForInput(reason: String) {
        inputPersistJob?.cancel()
        inputPersistJob = viewModelScope.launch {
            delay(INPUT_PERSIST_DEBOUNCE_MS)
            safePersistDraft("inputDebounce:$reason")
        }
    }

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
            val v = b.toInt() and 0xFF
            sb.append(hex[(v ushr 4) and 0xF])
            sb.append(hex[v and 0xF])
        }
        return sb.toString()
    }

    private inline fun <T> withStateLock(block: () -> T): T {
        return synchronized(stateLock) { block() }
    }

    private fun captureRollbackSnapshot(): SubmitRollbackSnapshot {
        return withStateLock {
            SubmitRollbackSnapshot(
                stage = stage,
                mainAnswer = mainAnswer,
                currentFollowUpQuestion = currentFollowUpQuestion,
                followUps = followUps.toList(),
                completionPayload = _completionPayload.value,
            )
        }
    }

    private fun restoreRollbackSnapshot(snapshot: SubmitRollbackSnapshot) {
        withStateLock {
            stage = snapshot.stage
            mainAnswer = snapshot.mainAnswer
            currentFollowUpQuestion = snapshot.currentFollowUpQuestion
            followUps.clear()
            followUps.addAll(snapshot.followUps)
        }
        _completionPayload.value = snapshot.completionPayload
    }

    private fun buildValidationFailureMessage(
        error: Throwable,
        failedStage: ChatDrafts.ChatStage,
    ): String {
        val stageLabel = when (failedStage) {
            ChatDrafts.ChatStage.AWAIT_MAIN -> "The answer could not be validated."
            ChatDrafts.ChatStage.AWAIT_FOLLOW_UP -> "The follow-up answer could not be validated."
            ChatDrafts.ChatStage.DONE -> "The answer could not be re-validated."
        }

        val detail = when (error.javaClass.simpleName) {
            "ModelNotReadyException" -> "The AI model is not ready yet. Please retry in a moment."
            else -> "Your input was restored. Please retry."
        }

        return "$stageLabel $detail"
    }

    companion object {
        private const val TAG = "ChatQVM"

        private const val STREAM_UI_UPDATE_INTERVAL_NS = 50_000_000L
        private const val STREAM_MIN_UPDATE_CHARS = 16

        private const val DEFAULT_FOLLOW_UP_MAIN = "Could you add one concrete detail or example?"
        private const val DEFAULT_FOLLOW_UP_MORE = "Could you add one more concrete detail?"

        private const val INPUT_PERSIST_DEBOUNCE_MS: Long = 250L

        /**
         * Debug-only noisy logs. Keep false in production.
         */
        private const val ENABLE_VERBOSE_EMIT_LOGS = false
    }
}
