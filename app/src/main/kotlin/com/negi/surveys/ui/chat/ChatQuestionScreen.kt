/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Question UI)
 *  ---------------------------------------------------------------------
 *  File: ChatQuestionScreen.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.negi.surveys.chat.AnswerValidator
import com.negi.surveys.chat.AnswerValidatorI
import com.negi.surveys.chat.ChatDraftStore
import com.negi.surveys.chat.ChatMessage
import com.negi.surveys.chat.ChatQuestionViewModel
import com.negi.surveys.chat.ChatRole
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.chat.ChatStreamState
import com.negi.surveys.chat.DraftKey
import com.negi.surveys.chat.RepositoryI
import com.negi.surveys.logging.AppLog
import com.negi.surveys.ui.LocalChatStreamBridge
import com.negi.surveys.ui.ReviewQuestionLog
import java.security.MessageDigest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * Chat question screen.
 *
 * Assumptions:
 * - [ReviewQuestionLog] is used for local review and not uploaded to analytics as-is.
 * - [RepositoryI] is provided via [LocalRepositoryI] by the app root (no hidden fallback).
 *
 * Privacy:
 * - We never log raw user input, prompt, follow-up question, or model output.
 */
@OptIn(FlowPreview::class)
@Composable
fun ChatQuestionScreen(
    questionId: String,
    prompt: String = "Question prompt for $questionId (placeholder)",
    onNext: (log: ReviewQuestionLog) -> Unit,
    onBack: () -> Unit,
    repository: RepositoryI = LocalRepositoryI.current,
) {
    val onNextLatest by rememberUpdatedState(onNext)
    val onBackLatest by rememberUpdatedState(onBack)

    val streamBridge: ChatStreamBridge = LocalChatStreamBridge.current
    val draftStore: ChatDraftStore = remember { ChatDraftStoreHolder.store }

    // Use a stable hash to reduce collisions while keeping DraftKey(promptHash:Int) API intact.
    val promptHash = remember(prompt) { stablePromptHash(prompt) }

    val draftKey = remember(questionId, promptHash) {
        DraftKey(questionId = questionId, promptHash = promptHash)
    }

    val validator: AnswerValidatorI = remember(questionId, promptHash, repository, streamBridge) {
        AnswerValidator(
            repository = repository,
            streamBridge = streamBridge,
            logger = { msg ->
                // Never log raw validator messages (they may contain user/model text).
                AppLog.d("AnswerValidator", safeLogSummary(msg))
            }
        )
    }

    val vmKey = remember(questionId, promptHash) {
        "ChatQuestionViewModel:$questionId:$promptHash"
    }

    val vm: ChatQuestionViewModel = viewModel(
        key = vmKey,
        factory = remember(questionId, prompt, validator, streamBridge, draftStore, draftKey) {
            ChatQuestionViewModelFactory(
                questionId = questionId,
                prompt = prompt,
                validator = validator,
                streamBridge = streamBridge,
                draftStore = draftStore,
                draftKey = draftKey
            )
        }
    )

    val messages by vm.messages.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val isBusy by vm.isBusy.collectAsStateWithLifecycle()
    val completionPayload by vm.completionPayload.collectAsStateWithLifecycle()

    val canSubmit = input.trim().isNotEmpty() && !isBusy
    var nextInFlight by remember(vmKey) { mutableStateOf(false) }

    val listState = rememberLazyListState()

    var bottomBarPx by remember(vmKey) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomBarDp = remember(bottomBarPx, density) { with(density) { bottomBarPx.toDp() } }

    val detailsExpanded = remember(vmKey) { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(vmKey) {
        nextInFlight = false
        AppLog.d(TAG, "Next latch reset. qid=$questionId vmKey=$vmKey")
    }

    LaunchedEffect(vmKey) {
        AppLog.d(TAG, "Using shared streamBridge identity=${System.identityHashCode(streamBridge)} qid=$questionId")
    }

    // Prevent state map from growing forever across long sessions.
    LaunchedEffect(vmKey, messages.size) {
        val keep = messages.asSequence().map { it.id }.toHashSet()
        val keys = detailsExpanded.keys.toList()
        for (k in keys) {
            if (!keep.contains(k)) detailsExpanded.remove(k)
        }
    }

    // Auto-scroll: only when user is near bottom and not actively scrolling.
    LaunchedEffect(vmKey) {
        snapshotFlow {
            if (messages.isEmpty()) return@snapshotFlow null

            val last = messages.last()
            val streamLen = last.streamText?.length ?: 0
            val textLen = last.text.length
            val sig = (last.id.hashCode() * 31) + (textLen * 7) + streamLen

            Triple(bottomBarPx, messages.size, sig)
        }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce(80)
            .collect {
                if (messages.isEmpty()) return@collect

                val lastIndex = messages.size - 1
                val lastMsg = messages[lastIndex]
                val streaming = lastMsg.role == ChatRole.MODEL &&
                        lastMsg.streamState == ChatStreamState.STREAMING

                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                val isNearBottom = lastVisible == null || (lastIndex - lastVisible) <= 2

                if (!isNearBottom) return@collect
                if (listState.isScrollInProgress) return@collect

                runCatching {
                    if (streaming) {
                        listState.scrollToItem(lastIndex)
                    } else {
                        listState.animateScrollToItem(lastIndex)
                    }
                }
            }
    }

    DisposableEffect(vm) {
        onDispose { vm.cancelValidation("screen_dispose") }
    }

    val statusLabel = when {
        isBusy -> "VALIDATING"
        completionPayload != null -> "READY"
        else -> "DRAFT"
    }

    val allowFollowUpUi = true
    val hasCompletion = completionPayload != null

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            ),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 0.dp)
        ) {
            HeaderCard(
                questionId = questionId,
                statusLabel = statusLabel,
                isBusy = isBusy,
                hasCompletion = hasCompletion
            )

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(10.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = bottomBarDp + 12.dp)
                ) {
                    items(items = messages, key = { it.id }) { msg: ChatMessage ->
                        ChatBubbleStructured(
                            msg = msg,
                            allowFollowUp = allowFollowUpUi,
                            detailsExpanded = detailsExpanded[msg.id] ?: false,
                            onToggleDetailsExpand = { expand ->
                                detailsExpanded[msg.id] = expand
                                // Avoid logging message contents.
                                AppLog.d(
                                    TAG,
                                    "details toggle. id=${msg.id} expand=$expand role=${msg.role} state=${msg.streamState}"
                                )
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            BottomComposerCard(
                input = input,
                isBusy = isBusy,
                hasCompletion = hasCompletion,
                canSubmit = canSubmit,
                nextInFlight = nextInFlight,
                onInputChange = { vm.setInput(it) },
                onBack = {
                    AppLog.d(TAG, "Back clicked. qid=$questionId busy=$isBusy")
                    vm.cancelValidation("back_click")
                    onBackLatest()
                },
                onSubmit = {
                    // Avoid logging raw input.
                    AppLog.d(TAG, "Submit clicked. qid=$questionId inputLen=${input.length}")
                    vm.submit()
                },
                onNext = {
                    if (nextInFlight) {
                        AppLog.w(TAG, "Next clicked while inFlight=true (ignored). qid=$questionId")
                        return@BottomComposerCard
                    }
                    if (isBusy) {
                        AppLog.w(TAG, "Next clicked while busy=true (ignored). qid=$questionId")
                        return@BottomComposerCard
                    }

                    nextInFlight = true
                    try {
                        val payloadNow = vm.completionPayload.value
                        val messagesNow = vm.messages.value

                        vm.cancelValidation("next_click")

                        val skipped = (payloadNow == null)
                        val payload = payloadNow ?: buildSkippedPayload(questionId, prompt)

                        val log = buildReviewQuestionLog(
                            questionId = questionId,
                            prompt = prompt,
                            isSkipped = skipped,
                            completionPayload = payload,
                            messagesSnapshot = messagesNow
                        )

                        AppLog.d(
                            TAG,
                            "Next clicked. qid=$questionId skipped=$skipped payloadLen=${payload.length} lines=${log.lines.size}"
                        )
                        onNextLatest(log)
                    } finally {
                        nextInFlight = false
                    }
                },
                onMeasuredHeightPx = { bottomBarPx = it }
            )
        }
    }
}

private const val TAG = "ChatQuestionScreen"

/**
 * Produce a safe summary for logs. Never return raw text.
 */
private fun safeLogSummary(raw: String?): String {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return "(empty)"
    val len = t.length
    val sha12 = sha256HexPrefix(t, 12)
    return "len=$len sha256=$sha12"
}

private fun sha256HexPrefix(text: String, hexChars: Int): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(hexChars)
    val neededBytes = (hexChars + 1) / 2
    for (i in 0 until neededBytes.coerceAtMost(bytes.size)) {
        val b = bytes[i].toInt() and 0xFF
        sb.append("0123456789abcdef"[b ushr 4])
        sb.append("0123456789abcdef"[b and 0x0F])
    }
    return if (sb.length > hexChars) sb.substring(0, hexChars) else sb.toString()
}