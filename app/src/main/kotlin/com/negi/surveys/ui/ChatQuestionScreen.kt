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

package com.negi.surveys.ui

import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.negi.surveys.chat.AnswerValidator
import com.negi.surveys.chat.ChatDraftStore
import com.negi.surveys.chat.ChatMessage
import com.negi.surveys.chat.ChatQuestionViewModel
import com.negi.surveys.chat.ChatRole
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.chat.ChatStreamState
import com.negi.surveys.chat.DraftKey
import com.negi.surveys.chat.FakeSlmRepository
import com.negi.surveys.chat.InMemoryChatDraftStore
import com.negi.surveys.chat.Repository
import com.negi.surveys.chat.SlmAnswerValidator
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

@OptIn(FlowPreview::class)
@Composable
fun ChatQuestionScreen(
    questionId: String,
    prompt: String = "Question prompt for $questionId (placeholder)",
    repository: Repository? = null,

    /**
     * Called when user taps Next.
     *
     * Contract:
     * - Provides a full snapshot log for Review.
     * - log.completionPayload is always non-empty (skipped payload is generated when needed).
     */
    onNext: (log: ReviewQuestionLog) -> Unit,

    onBack: () -> Unit
) {
    val onNextLatest by rememberUpdatedState(onNext)
    val onBackLatest by rememberUpdatedState(onBack)

    /**
     * IMPORTANT:
     * - Do NOT create ChatStreamBridge per screen.
     * - Use the session-shared instance provided by SurveyAppRoot via CompositionLocalProvider.
     */
    val streamBridge: ChatStreamBridge = LocalChatStreamBridge.current

    /**
     * Shared draft store for the whole process (in-memory prototype).
     *
     * Notes:
     * - This is fine to be a singleton while prototyping.
     * - Replace with persistent store later if needed.
     */
    val draftStore: ChatDraftStore = remember { ChatDraftStoreHolder.store }

    val promptHash = remember(prompt) { prompt.hashCode() }

    val draftKey = remember(questionId, promptHash) {
        DraftKey(questionId = questionId, promptHash = promptHash)
    }

    /**
     * Repository selection:
     * - Keep call order stable by always using remember() exactly once.
     */
    val repo: Repository = remember(repository) {
        repository ?: FakeSlmRepository()
    }

    /**
     * Validator:
     * - Uses the session-shared streamBridge so streaming state does not reset per screen instance.
     */
    val validator: AnswerValidator = remember(questionId, promptHash, repo, streamBridge) {
        SlmAnswerValidator(
            repository = repo,
            streamBridge = streamBridge,
            logger = { Log.d("SlmValidator", it) }
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
        Log.d(TAG, "Next latch reset. qid=$questionId vmKey=$vmKey")
    }

    LaunchedEffect(vmKey) {
        // PII-safe bridge identity trace (helps confirm it is NOT recreated per screen).
        Log.d(TAG, "Using shared streamBridge identity=${System.identityHashCode(streamBridge)} qid=$questionId")
    }

    LaunchedEffect(vmKey, bottomBarPx) {
        snapshotFlow {
            val last = messages.lastOrNull()
            if (last == null) {
                null
            } else {
                val streamLen = last.streamText?.length ?: 0
                val textLen = last.text.length
                val sig = (last.id.hashCode() * 31) + (textLen * 7) + streamLen
                Pair(messages.size, sig)
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce(80)
            .collect {
                if (messages.isEmpty()) return@collect
                val lastIndex = messages.size - 1
                val lastMsg = messages[lastIndex]
                val streaming = lastMsg.role == ChatRole.MODEL && lastMsg.streamState == ChatStreamState.STREAMING

                runCatching {
                    if (streaming) {
                        listState.scrollToItem(lastIndex)
                    } else {
                        listState.animateScrollToItem(lastIndex)
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Auto-scroll failed (non-fatal). size=${messages.size}", e)
                }
            }
    }

    DisposableEffect(vm) {
        onDispose {
            // Respect structured concurrency & ensure any in-flight streaming is stopped.
            vm.cancelValidation("screen_dispose")
        }
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
                    items(items = messages, key = { it.id }) { msg ->
                        ChatBubbleStructured(
                            msg = msg,
                            allowFollowUp = allowFollowUpUi,
                            detailsExpanded = detailsExpanded[msg.id] ?: false,
                            onToggleDetailsExpand = { expand ->
                                detailsExpanded[msg.id] = expand
                                Log.d(
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
                    Log.d(TAG, "Back clicked. qid=$questionId busy=$isBusy")
                    vm.cancelValidation("back_click")
                    onBackLatest()
                },
                onSubmit = {
                    Log.d(TAG, "Submit clicked. qid=$questionId len=${input.length}")
                    vm.submit()
                },
                onNext = {
                    if (nextInFlight) {
                        Log.w(TAG, "Next clicked while inFlight=true (ignored). qid=$questionId")
                        return@BottomComposerCard
                    }

                    // Prevent snapshot/export from missing assistant follow-up lines.
                    // If we navigate away while a validation stream is still running,
                    // the Review log can be captured before the ASSISTANT outcome exists.
                    if (isBusy) {
                        Log.w(TAG, "Next clicked while busy=true (ignored). qid=$questionId")
                        return@BottomComposerCard
                    }

                    nextInFlight = true
                    try {
                        vm.cancelValidation("next_click")

                        // IMPORTANT:
                        // - Do NOT rely on Compose-collected snapshots here.
                        // - The user can tap Next faster than recomposition, so `messages` / `completionPayload`
                        //   may be stale and miss the latest assistant follow-up.
                        // - Read the VM StateFlow values directly for a consistent snapshot.
                        val payloadNow = vm.completionPayload.value
                        val skipped = (payloadNow == null)
                        val payload = payloadNow ?: buildSkippedPayload(questionId, prompt)

                        val messagesNow = vm.messages.value

                        val log = buildReviewQuestionLog(
                            questionId = questionId,
                            prompt = prompt,
                            isSkipped = skipped,
                            completionPayload = payload,
                            messagesSnapshot = messagesNow
                        )

                        Log.d(
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

@Composable
private fun HeaderCard(
    questionId: String,
    statusLabel: String,
    isBusy: Boolean,
    hasCompletion: Boolean
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Question $questionId",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            isBusy -> "Streaming validation output will appear below."
                            hasCompletion -> "Accepted. You can edit and resubmit, or press Next."
                            else -> "Type an answer, or press Next to move on."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                StatusPill(label = statusLabel)
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    val bg = when (label) {
        "READY" -> MaterialTheme.colorScheme.primaryContainer
        "VALIDATING" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when (label) {
        "READY" -> MaterialTheme.colorScheme.onPrimaryContainer
        "VALIDATING" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BottomComposerCard(
    input: String,
    isBusy: Boolean,
    hasCompletion: Boolean,
    canSubmit: Boolean,
    nextInFlight: Boolean,
    onInputChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onMeasuredHeightPx: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .onSizeChanged { onMeasuredHeightPx(it.height) }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                enabled = !isBusy,
                label = {
                    Text(
                        when {
                            isBusy -> "Validating..."
                            hasCompletion -> "Edit answer (will re-validate)"
                            else -> "Your answer (optional)"
                        }
                    )
                },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (canSubmit) onSubmit() }
                )
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    enabled = true
                ) { Text("Back") }

                Button(
                    onClick = onSubmit,
                    modifier = Modifier.weight(1f),
                    enabled = canSubmit
                ) { Text("Submit") }

                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                    enabled = !nextInFlight && !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) { Text("Next") }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = when {
                    isBusy -> "Validation is running. Please wait before navigating."
                    hasCompletion -> "Next will use the accepted answer."
                    else -> "You can press Next to skip for now and answer later."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatBubbleStructured(
    msg: ChatMessage,
    allowFollowUp: Boolean,
    detailsExpanded: Boolean,
    onToggleDetailsExpand: (Boolean) -> Unit
) {
    val isUser = msg.role == ChatRole.USER
    val isModel = msg.role == ChatRole.MODEL
    val isAssistant = msg.role == ChatRole.ASSISTANT

    val hasStreamDetails = !msg.streamText.isNullOrBlank() && msg.streamState != ChatStreamState.NONE
    val isStreamingModelBubble = isModel && (msg.streamState == ChatStreamState.STREAMING)

    val rowAlign = if (isUser) Arrangement.End else Arrangement.Start

    val shape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
    }

    val bubbleColor = when (msg.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primaryContainer
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer
        ChatRole.MODEL -> MaterialTheme.colorScheme.surface
    }

    val textColor = when (msg.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        ChatRole.ASSISTANT -> MaterialTheme.colorScheme.onSecondaryContainer
        ChatRole.MODEL -> MaterialTheme.colorScheme.onSurface
    }

    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    val expanded = when {
        isStreamingModelBubble -> true
        hasStreamDetails -> (detailsExpanded || !msg.streamCollapsed)
        else -> false
    }

    val isCompactPill =
        (hasStreamDetails && !expanded) ||
                (isModel && !isStreamingModelBubble && !expanded && !msg.streamText.isNullOrBlank())

    val maxWidth = when {
        isCompactPill -> 360.dp
        isModel && !isStreamingModelBubble -> 460.dp
        else -> 520.dp
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = rowAlign
    ) {
        Surface(
            shape = shape,
            tonalElevation = 1.dp,
            color = bubbleColor,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .border(1.dp, outline, shape)
                .animateContentSize()
        ) {
            val basePaddingH = if (isCompactPill) 10.dp else 12.dp
            val basePaddingV = if (isCompactPill) 8.dp else 10.dp

            Column(modifier = Modifier.padding(horizontal = basePaddingH, vertical = basePaddingV)) {

                if (!isUser && !isCompactPill) {
                    val header = if (isModel) "MODEL" else "AI"
                    val headerBg = if (isModel) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.0f)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(headerBg)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = header,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                }

                when {
                    isUser -> {
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }

                    isAssistant -> {
                        val a = msg.assistantMessage?.trim().orEmpty()
                        val q = if (allowFollowUp) normalizeFollowUp(msg.followUpQuestion) else null

                        if (a.isNotEmpty()) {
                            Text(
                                text = a,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                        } else if (msg.text.isNotBlank()) {
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = textColor
                            )
                        }

                        if (q != null) {
                            Spacer(Modifier.height(10.dp))

                            val qShape = RoundedCornerShape(14.dp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(qShape)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        shape = qShape
                                    )
                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Question",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = q,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }

                        if (hasStreamDetails) {
                            Spacer(Modifier.height(10.dp))

                            val raw = msg.streamText.orEmpty()
                            val canClick = raw.isNotBlank()
                            val clickMod = if (canClick) {
                                Modifier.clickable { onToggleDetailsExpand(!expanded) }
                            } else {
                                Modifier
                            }

                            if (!expanded) {
                                val pillShape = RoundedCornerShape(12.dp)
                                Row(
                                    modifier = Modifier
                                        .then(clickMod)
                                        .clip(pillShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                        .border(1.dp, outline, pillShape)
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Model output",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "(${raw.length} chars)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = "Expand",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                val blockShape = RoundedCornerShape(14.dp)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(clickMod)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(blockShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .border(1.dp, outline, blockShape)
                                            .padding(horizontal = 10.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = raw,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(Modifier.height(6.dp))

                                    Text(
                                        text = "Tap to collapse",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    isModel -> {
                        val raw = msg.streamText?.takeIf { it.isNotBlank() } ?: msg.text

                        val canClick = !isStreamingModelBubble && raw.isNotBlank()
                        val clickMod = if (canClick) {
                            Modifier.clickable { onToggleDetailsExpand(!expanded) }
                        } else {
                            Modifier
                        }

                        if (isCompactPill && !isStreamingModelBubble) {
                            val pillShape = RoundedCornerShape(12.dp)
                            Row(
                                modifier = Modifier
                                    .then(clickMod)
                                    .clip(pillShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                    .border(1.dp, outline, pillShape)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Model output",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "(${raw.length} chars)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "Expand",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(clickMod)
                            ) {
                                val blockShape = RoundedCornerShape(14.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(blockShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .border(1.dp, outline, blockShape)
                                        .padding(horizontal = 10.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = raw.ifBlank { "Validating…" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(Modifier.height(6.dp))

                                Text(
                                    text = if (isStreamingModelBubble) "Streaming…"
                                    else if (expanded) "Tap to collapse"
                                    else "Tap to expand",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildSkippedPayload(questionId: String, prompt: String): String {
    val promptHash = prompt.hashCode()
    return buildString {
        append("[SKIPPED]\n")
        append("QUESTION_ID: ").append(questionId).append('\n')
        append("PROMPT_HASH: ").append(promptHash).append('\n')
        append("ANSWER: (none)\n")
    }.trim()
}

/**
 * Converts the current chat messages into a Review snapshot.
 *
 * Updated policy:
 * - If skipped: export USER lines, plus assistant lines / follow-up lines that happen after at least one USER line.
 * - Seed prompt message is excluded (prompt is already shown in the card header).
 * - MODEL_RAW is included even when skipped, but only after at least one USER line.
 * - MODEL_RAW duplicates (same as immediately previous MODEL_RAW) are suppressed.
 */
private fun buildReviewQuestionLog(
    questionId: String,
    prompt: String,
    isSkipped: Boolean,
    completionPayload: String,
    messagesSnapshot: List<ChatMessage>
): ReviewQuestionLog {
    val lines = ArrayList<ReviewChatLine>(messagesSnapshot.size * 2)

    val promptTrimmed = prompt.trim()
    var seenUser = false

    fun appendModelRawOnce(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val last = lines.lastOrNull()
        if (last != null && last.kind == ReviewChatKind.MODEL_RAW && last.text == t) return
        lines += ReviewChatLine(kind = ReviewChatKind.MODEL_RAW, text = t)
    }

    for (m in messagesSnapshot) {
        when (m.role) {
            ChatRole.USER -> {
                val t = m.text.trim()
                if (t.isNotEmpty()) {
                    lines += ReviewChatLine(kind = ReviewChatKind.USER, text = t)
                    seenUser = true
                }
            }

            ChatRole.ASSISTANT -> {
                val a = m.assistantMessage?.trim().orEmpty()
                val fallback = m.text.trim()
                val q = normalizeFollowUp(m.followUpQuestion)

                val isSeedPrompt =
                    !seenUser &&
                            a == "Question: $questionId" &&
                            (q?.trim().orEmpty() == promptTrimmed)

                if (isSeedPrompt) continue

                if (isSkipped) {
                    if (!seenUser) continue

                    if (a.isNotEmpty()) {
                        lines += ReviewChatLine(kind = ReviewChatKind.AI, text = a)
                    } else if (fallback.isNotEmpty()) {
                        lines += ReviewChatLine(kind = ReviewChatKind.AI, text = fallback)
                    }

                    val q2 = q?.trim()
                    if (!q2.isNullOrBlank() && q2 != promptTrimmed) {
                        lines += ReviewChatLine(kind = ReviewChatKind.FOLLOW_UP, text = q2)
                    }

                    val raw = m.streamText?.trim().orEmpty()
                    if (raw.isNotEmpty() && m.streamState != ChatStreamState.NONE) {
                        appendModelRawOnce(raw)
                    }
                    continue
                }

                if (a.isNotEmpty()) {
                    lines += ReviewChatLine(kind = ReviewChatKind.AI, text = a)
                } else if (fallback.isNotEmpty()) {
                    lines += ReviewChatLine(kind = ReviewChatKind.AI, text = fallback)
                }

                val q2 = q?.trim()
                if (!q2.isNullOrBlank() && q2 != promptTrimmed) {
                    lines += ReviewChatLine(kind = ReviewChatKind.FOLLOW_UP, text = q2)
                }

                val raw = m.streamText?.trim().orEmpty()
                if (raw.isNotEmpty() && m.streamState != ChatStreamState.NONE) {
                    appendModelRawOnce(raw)
                }
            }

            ChatRole.MODEL -> {
                if (isSkipped && !seenUser) continue
                val raw = (m.streamText?.takeIf { it.isNotBlank() } ?: m.text).trim()
                if (raw.isNotEmpty()) {
                    appendModelRawOnce(raw)
                }
            }
        }
    }

    Log.d(
        TAG,
        "buildReviewQuestionLog: qid=$questionId skipped=$isSkipped lines=${lines.size} payloadLen=${completionPayload.length}"
    )

    return ReviewQuestionLog(
        questionId = questionId,
        prompt = prompt,
        isSkipped = isSkipped,
        completionPayload = completionPayload,
        lines = lines
    )
}

private fun normalizeFollowUp(text: String?): String? {
    val raw = text ?: return null
    var t = raw.trim().replace("\u0000", "")
    if (t.isBlank()) return null

    val prefixPatterns = listOf(
        "follow-up question:",
        "follow up question:",
        "follow-up:",
        "follow up:",
        "followup:",
        "question:",
        "next question:"
    )

    val lower0 = t.lowercase(Locale.US)
    for (p in prefixPatterns) {
        if (lower0.startsWith(p)) {
            t = t.drop(p.length).trim()
            break
        }
    }

    if (t.isBlank()) return null

    val l2 = t.lowercase(Locale.US)
    val garbage = setOf(
        "none", "(none)", "n/a", "na", "null", "nil",
        "no", "nope", "no follow up", "no follow-up",
        "skip", "0", "-"
    )
    if (l2 in garbage) return null

    if (t.length < 3) return null

    return t
}

private class ChatQuestionViewModelFactory(
    private val questionId: String,
    private val prompt: String,
    private val validator: AnswerValidator,
    private val streamBridge: ChatStreamBridge,
    private val draftStore: ChatDraftStore,
    private val draftKey: DraftKey
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatQuestionViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return ChatQuestionViewModel(
            questionId = questionId,
            prompt = prompt,
            validator = validator,
            streamBridge = streamBridge,
            draftStore = draftStore,
            draftKey = draftKey
        ) as T
    }
}

private object ChatDraftStoreHolder {
    val store: ChatDraftStore = InMemoryChatDraftStore()
}

private const val TAG = "ChatQuestionScreen"