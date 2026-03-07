/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (LiteRT-LM Repository)
 *  ---------------------------------------------------------------------
 *  File: SlmRepository.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm

import android.content.Context
import com.google.ai.edge.litertlm.Message
import com.negi.surveys.chat.ChatModels
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.chat.ChatStreamEvent
import com.negi.surveys.chat.ChatValidation
import com.negi.surveys.chat.DefaultSlmPromptBuilder
import com.negi.surveys.chat.SlmPromptBuilderI
import com.negi.surveys.config.InstalledSurveyConfigStore
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.warmup.WarmupController
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

/**
 * Repository that orchestrates LiteRT-LM inference for:
 * - One-step generation
 * - Structured two-step validation
 *
 * Two-step contract:
 * 1) Step 1: Assessment JSON
 *    - status
 *    - score
 *    - reason
 *    - optional missing[]
 * 2) Step 2: Follow-up JSON
 *    - assistantMessage (optional)
 *    - followUpQuestion (required when follow-up is needed)
 *
 * Important:
 * - Final accept / follow-up decision is derived by code from Step-1 score.
 * - Step-2 output must never override the Step-1 decision.
 *
 * Privacy:
 * - Do not log raw user answers or model paths in normal logs.
 * - Debug clipped/full prompt/result logging must stay opt-in.
 */
class SlmRepository(
    context: Context,
    private val configAssetName: String = "survey.yaml",
    private val fallbackModelName: String = "Gemma3n4B",
    private val fallbackModelFileName: String = "Gemma3n4B.litertlm",
    private val resetConversationEachRequest: Boolean = true,

    /**
     * Legacy constructor name kept for source compatibility.
     *
     * Semantic meaning:
     * - If true, run the structured two-step assessment flow.
     * - If false, run a single generation stream.
     */
    private val enableTwoStepEval: Boolean = true,

    /** Score threshold below which a follow-up is required. */
    private val acceptScoreThreshold: Int = 70,

    /**
     * If true, allow loading config from assets when installed config is unavailable.
     *
     * Important:
     * - This is used only in suspend paths.
     * - This repository must never install process config; Application owns that.
     */
    private val allowAssetConfigFallback: Boolean = false,

    /**
     * Injected prompt builder.
     *
     * Notes:
     * - Stateless only.
     * - No I/O inside the builder.
     */
    private val promptBuilder: SlmPromptBuilderI = DefaultSlmPromptBuilder,

    /** Debug options. */
    debug: DebugConfig = DebugConfig(),
) : ChatValidation.RepositoryI, WarmupController.WarmupCapableRepository {

    data class DebugConfig(
        /** Enable debug logging. */
        val enabled: Boolean = false,

        /** If true, logs include clipped prompt/result blocks. */
        val logClippedText: Boolean = true,

        /** If true, logs include full prompt/result text. Dangerous in production. */
        val logFullText: Boolean = false,

        /** Max chars logged for prompt text when clipped logging is enabled. */
        val maxPromptLogChars: Int = 2_000,

        /** Max chars logged for result text when clipped logging is enabled. */
        val maxResultLogChars: Int = 2_000,

        /**
         * Legacy property name kept for compatibility.
         *
         * Semantic meaning:
         * - If true, Step-1 text is streamed to legacy request(prompt) callers.
         */
        val streamEvalOutputToClient: Boolean = true,
    ) {
        val streamAssessmentOutputToClient: Boolean
            get() = streamEvalOutputToClient

        fun normalized(): DebugConfig {
            return copy(
                maxPromptLogChars = maxPromptLogChars.coerceAtLeast(0),
                maxResultLogChars = maxResultLogChars.coerceAtLeast(0),
            )
        }
    }

    private val dbg: DebugConfig = debug.normalized()
    private val appContext: Context = context.applicationContext

    /**
     * Canonical internal flag for the structured two-step protocol.
     */
    private val enableTwoStepAssessment: Boolean = enableTwoStepEval

    /**
     * Cached config for fast paths.
     *
     * Installed config remains the canonical source.
     */
    private val cachedConfig = AtomicReference<SurveyConfig?>(null)
    private val configLoadMutex = Mutex()

    /** Model cache keyed by "name::absolutePath". */
    private val modelCache = ConcurrentHashMap<String, ModelHolder>()

    class ModelNotReadyException(message: String) : IllegalStateException(message)

    // ---------------------------------------------------------------------
    // Public contract
    // ---------------------------------------------------------------------

    /**
     * Compatibility helper.
     *
     * Notes:
     * - This method is intentionally not marked override.
     * - New code should prefer the phase-aware overload.
     */
    fun buildPrompt(userPrompt: String): String {
        val u = userPrompt.trim()
        if (u.isBlank()) return ""

        val sys = getSystemPromptFromCacheOnly()
        return promptBuilder.buildAnswerLikePrompt(systemPrompt = sys, userPrompt = u)
    }

    /**
     * Phase-aware prompt building.
     *
     * Important:
     * - Validation prompts may already be strict JSON-only prompts.
     * - We do not inject extra phase markers into prompt text here.
     */
    override fun buildPrompt(
        userPrompt: String,
        phase: ChatValidation.PromptPhase,
    ): String {
        val u = userPrompt.trim()
        if (u.isBlank()) return ""

        val sys = getSystemPromptFromCacheOnly()
        return promptBuilder.buildAnswerLikePrompt(systemPrompt = sys, userPrompt = u)
    }

    override suspend fun request(prompt: String): Flow<String> {
        val input = prompt.trim()
        if (input.isBlank()) return emptyFlow()

        val cfg = getConfigSuspendBestEffort()
        val file = resolveModelFile(cfg)
        val stat = safeFileStat(file)

        if (!stat.exists || !stat.isFile || stat.length <= 0L) {
            AppLog.w(TAG, "Model not ready: exists=${stat.exists} isFile=${stat.isFile} len=${stat.length}")
            throw ModelNotReadyException("Model file missing or empty")
        }

        val model = getOrCreateModel(cfg = cfg, file = file)

        withContext(Dispatchers.Default) {
            awaitInitializedModelOnce(
                model = model,
                initOptions = InitOptions.defaultForRepo(),
            )
        }

        return if (enableTwoStepAssessment) {
            requestAssessmentThenFollowUp(model = model, userPrompt = input)
        } else {
            val sys = runCatching { cfg?.composeSystemPrompt() }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            val p = promptBuilder.buildAnswerLikePrompt(systemPrompt = sys, userPrompt = input)
            requestOneStep(model = model, prompt = p)
        }
    }

    /**
     * Structured two-step assessment entry point used by the validator.
     *
     * Design:
     * - Build the legacy-compatible validation user prompt internally.
     * - Run Step 1 and parse strict JSON.
     * - Final status is derived by code from score threshold.
     * - Step 2 is executed only when follow-up is needed.
     * - Raw JSON for both steps is returned for UI details.
     */
    override suspend fun runTwoStepAssessment(
        request: ChatValidation.TwoStepAssessmentRequest,
        streamBridge: ChatStreamBridge?,
    ): ChatValidation.TwoStepAssessmentResult {
        val cfg = getConfigSuspendBestEffort()
        val file = resolveModelFile(cfg)
        val stat = safeFileStat(file)

        if (!stat.exists || !stat.isFile || stat.length <= 0L) {
            throw ModelNotReadyException("Model file missing or empty")
        }

        val model = getOrCreateModel(cfg = cfg, file = file)

        withContext(Dispatchers.Default) {
            awaitInitializedModelOnce(
                model = model,
                initOptions = InitOptions.defaultForRepo(),
            )
        }

        val questionId = request.questionId.trim().ifBlank {
            "AUTO-${UUID.randomUUID().toString().take(8)}"
        }

        val validationUserPrompt = buildValidationUserPrompt(
            phase = if (request.followUpAnswerPayload.isNullOrBlank()) {
                ChatValidation.PromptPhase.VALIDATE_MAIN
            } else {
                ChatValidation.PromptPhase.VALIDATE_FOLLOW_UP
            },
            questionId = questionId,
            mainAnswer = request.mainAnswer,
            followUpAnswerPayload = request.followUpAnswerPayload,
        )

        if (resetConversationEachRequest) {
            resetConversationBestEffort(model, reason = "step1")
        }

        val step1Prompt = promptBuilder.buildEvalScorePrompt(
            questionId = questionId,
            userPrompt = validationUserPrompt,
            acceptScoreThreshold = acceptScoreThreshold,
            userPromptMaxChars = ASSESSMENT_USER_PROMPT_MAX_CHARS,
        )

        val step1Collected = collectStructuredJsonForPhase(
            model = model,
            input = step1Prompt,
            phase = ChatStreamEvent.Phase.STEP1_EVAL,
            streamBridge = streamBridge,
            timeoutMs = ASSESSMENT_TIMEOUT_MS,
            maxChars = ASSESSMENT_MAX_CHARS,
        )

        val rawEvalJson = extractFirstCompleteJsonObjectBestEffort(step1Collected.text)
            ?: step1Collected.text.trim()

        val parsedStep1 = parseStep1EvalStrict(
            raw = rawEvalJson,
            fallbackReason = fallbackStep1Reason(
                collectedReason = step1Collected.reason,
                errorToken = step1Collected.errorToken,
            ),
        )

        val finalStatus = if (parsedStep1.score >= acceptScoreThreshold) {
            ChatModels.ValidationStatus.ACCEPTED
        } else {
            ChatModels.ValidationStatus.NEED_FOLLOW_UP
        }

        val finalStep1 = parsedStep1.copy(status = finalStatus)

        dbgLogStructuredStep1(
            questionId = questionId,
            rawEvalJson = rawEvalJson,
            parsed = finalStep1,
        )

        if (finalStatus == ChatModels.ValidationStatus.ACCEPTED) {
            return ChatValidation.TwoStepAssessmentResult(
                step1 = finalStep1,
                step2 = null,
                rawEvalJson = rawEvalJson,
                rawFollowUpJson = null,
            )
        }

        if (resetConversationEachRequest) {
            resetConversationBestEffort(model, reason = "step2")
        }

        val step2Prompt = promptBuilder.buildFollowUpPrompt(
            questionId = questionId,
            userPrompt = validationUserPrompt,
            evalJson = rawEvalJson,
            acceptScoreThreshold = acceptScoreThreshold,
            userPromptMaxChars = FOLLOWUP_USER_PROMPT_MAX_CHARS,
            evalJsonMaxChars = FOLLOWUP_ASSESSMENT_JSON_MAX_CHARS,
        )

        val step2Collected = collectStructuredJsonForPhase(
            model = model,
            input = step2Prompt,
            phase = ChatStreamEvent.Phase.STEP2_FOLLOW_UP,
            streamBridge = streamBridge,
            timeoutMs = FOLLOWUP_TIMEOUT_MS,
            maxChars = FOLLOWUP_MAX_CHARS,
        )

        val rawFollowUpJson = extractFirstCompleteJsonObjectBestEffort(step2Collected.text)
            ?: step2Collected.text.trim()

        val parsedStep2 = parseStep2FollowUpStrict(
            raw = rawFollowUpJson,
            fallbackFollowUp = request.fallbackFollowUp,
        )

        dbgLogStructuredStep2(
            questionId = questionId,
            rawFollowUpJson = rawFollowUpJson,
            parsed = parsedStep2,
        )

        return ChatValidation.TwoStepAssessmentResult(
            step1 = finalStep1,
            step2 = parsedStep2.copy(status = ChatModels.ValidationStatus.NEED_FOLLOW_UP),
            rawEvalJson = rawEvalJson,
            rawFollowUpJson = rawFollowUpJson,
        )
    }

    /**
     * Warmup entry point for WarmupController.
     *
     * Contract:
     * - Must not hop threads internally.
     * - Must not perform network I/O.
     */
    override suspend fun warmup(
        appContext: Context,
        modelFile: File,
        options: WarmupController.Options,
    ): Boolean {
        val cfg = InstalledSurveyConfigStore.getOrNull() ?: cachedConfig.get()

        val stat = safeFileStat(modelFile)
        if (!stat.exists || !stat.isFile || stat.length <= 0L) {
            AppLog.w(
                TAG,
                "warmup: skipped (model file missing/empty) exists=${stat.exists} isFile=${stat.isFile} len=${stat.length}",
            )
            return false
        }

        return runCatching {
            val model = getOrCreateModel(cfg = cfg, file = modelFile)
            awaitInitializedModelOnce(
                model = model,
                initOptions = InitOptions.fromWarmupOptions(options),
            )
            true
        }.getOrElse { t ->
            AppLog.w(TAG, "warmup: failed type=${t.javaClass.simpleName}")
            false
        }
    }

    // ---------------------------------------------------------------------
    // Two-step legacy request(prompt) flow
    // ---------------------------------------------------------------------

    /**
     * Legacy two-step request flow that streams raw text to the caller.
     *
     * Notes:
     * - This path is kept for compatibility.
     * - Unlike the structured validator path, it emits text chunks directly.
     * - It now respects the Step-1 score threshold and skips Step-2 when accepted.
     */
    private fun requestAssessmentThenFollowUp(model: Model, userPrompt: String): Flow<String> {
        val u = userPrompt.trim()
        if (u.isBlank()) return emptyFlow()

        return streamingFlow(model = model) { emit, closeOk, closeErr ->
            val traceId = UUID.randomUUID().toString().take(8)
            val streamAssessmentToClient = dbg.streamAssessmentOutputToClient
            val questionId = extractQuestionId(u) ?: "AUTO-${UUID.randomUUID().toString().take(8)}"

            if (resetConversationEachRequest) {
                resetConversationBestEffort(model, reason = "assessment")
            }

            val assessmentPrompt = promptBuilder.buildEvalScorePrompt(
                questionId = questionId,
                userPrompt = u,
                acceptScoreThreshold = acceptScoreThreshold,
                userPromptMaxChars = ASSESSMENT_USER_PROMPT_MAX_CHARS,
            )

            dbgLogPrompt(
                traceId = traceId,
                step = "ASSESSMENT PHASE",
                questionId = questionId,
                prompt = assessmentPrompt,
            )

            if (streamAssessmentToClient) {
                emit(STREAM_ASSESSMENT_PREFIX)
            }

            val assessmentResult = runSdkAndCollectWithBudget(
                model = model,
                input = assessmentPrompt,
                timeoutMs = ASSESSMENT_TIMEOUT_MS,
                maxChars = ASSESSMENT_MAX_CHARS,
                onDelta = if (streamAssessmentToClient) {
                    { chunk ->
                        if (chunk.isNotEmpty()) emit(chunk)
                    }
                } else {
                    null
                },
            )

            dbgLogResult(
                traceId = traceId,
                step = "ASSESSMENT PHASE",
                questionId = questionId,
                reason = assessmentResult.reason,
                isEarlyStop = assessmentResult.isEarlyStop,
                text = assessmentResult.text,
            )

            val assessmentJsonRaw = extractFirstCompleteJsonObjectBestEffort(assessmentResult.text)
            val assessmentParsed = parseStep1EvalStrict(
                raw = assessmentJsonRaw ?: assessmentResult.text,
                fallbackReason = fallbackStep1Reason(
                    collectedReason = assessmentResult.reason,
                    errorToken = assessmentResult.errorToken,
                ),
            )

            AppLog.d(
                TAG,
                "assessment done: trace=$traceId qid=$questionId score=${assessmentParsed.score} " +
                        "status=${assessmentParsed.status} reason=${assessmentResult.reason} earlyStop=${assessmentResult.isEarlyStop}",
            )

            if (streamAssessmentToClient) {
                emit(
                    "\n$STREAM_ASSESSMENT_RESULT_PREFIX status=${assessmentParsed.status.name} score=${assessmentParsed.score}\n",
                )
            }

            val accepted = assessmentParsed.score >= acceptScoreThreshold
            if (accepted) {
                closeOk()
                return@streamingFlow
            }

            if (streamAssessmentToClient) {
                emit(STREAM_FOLLOWUP_PREFIX)
            }

            if (assessmentResult.isEarlyStop) {
                delay(EARLY_STOP_STABILIZE_DELAY_MS)
            }

            if (resetConversationEachRequest) {
                resetConversationBestEffort(model, reason = "followUp")
            }

            val followUpPrompt = promptBuilder.buildFollowUpPrompt(
                questionId = questionId,
                userPrompt = u,
                evalJson = assessmentJsonRaw ?: assessmentResult.text,
                acceptScoreThreshold = acceptScoreThreshold,
                userPromptMaxChars = FOLLOWUP_USER_PROMPT_MAX_CHARS,
                evalJsonMaxChars = FOLLOWUP_ASSESSMENT_JSON_MAX_CHARS,
            )

            dbgLogPrompt(
                traceId = traceId,
                step = "FOLLOWUP PHASE",
                questionId = questionId,
                prompt = followUpPrompt,
            )

            val s2Buffer = if (dbg.enabled) StringBuilder(1024) else null
            val s2MaxCapture = if (dbg.enabled) dbg.maxResultLogChars.coerceAtLeast(1024) else 0

            runSdkAndStream(
                model = model,
                input = followUpPrompt,
                onDelta = { chunk ->
                    if (chunk.isNotEmpty()) {
                        if (s2Buffer != null && s2Buffer.length < s2MaxCapture) {
                            val remaining = s2MaxCapture - s2Buffer.length
                            if (remaining > 0) {
                                if (chunk.length <= remaining) {
                                    s2Buffer.append(chunk)
                                } else {
                                    s2Buffer.append(chunk.take(remaining))
                                }
                            }
                        }
                        emit(chunk)
                    }
                },
                onTerminal = { msg ->
                    if (s2Buffer != null) {
                        dbgLogResult(
                            traceId = traceId,
                            step = "FOLLOWUP PHASE",
                            questionId = questionId,
                            reason = msg,
                            isEarlyStop = false,
                            text = s2Buffer.toString(),
                        )
                    }
                    closeOk()
                },
                onError = { msg ->
                    if (s2Buffer != null) {
                        dbgLogResult(
                            traceId = traceId,
                            step = "FOLLOWUP PHASE",
                            questionId = questionId,
                            reason = "ERROR:$msg",
                            isEarlyStop = true,
                            text = s2Buffer.toString(),
                        )
                    }
                    if (msg.equals("Cancelled", ignoreCase = true)) {
                        closeOk()
                    } else {
                        closeErr(IllegalStateException(msg))
                    }
                },
            )
        }
    }

    // ---------------------------------------------------------------------
    // One-step
    // ---------------------------------------------------------------------

    private fun requestOneStep(model: Model, prompt: String): Flow<String> {
        val p = prompt.trim()
        if (p.isBlank()) return emptyFlow()

        return streamingFlow(model = model) { emit, closeOk, closeErr ->
            if (resetConversationEachRequest) {
                resetConversationBestEffort(model, reason = "oneStep")
            }

            runSdkAndStream(
                model = model,
                input = p,
                onDelta = { chunk ->
                    if (chunk.isNotEmpty()) emit(chunk)
                },
                onTerminal = {
                    closeOk()
                },
                onError = { msg ->
                    if (msg.equals("Cancelled", ignoreCase = true)) {
                        closeOk()
                    } else {
                        closeErr(IllegalStateException(msg))
                    }
                },
            )
        }
    }

    // ---------------------------------------------------------------------
    // Flow scaffolding
    // ---------------------------------------------------------------------

    /**
     * Build a streaming flow guarded by a per-model semaphore.
     *
     * Notes:
     * - Avoid concurrent inference on the same model instance.
     * - Provide consistent cancellation behavior.
     */
    private fun streamingFlow(
        model: Model,
        block: suspend (
            emit: (String) -> Unit,
            closeOk: () -> Unit,
            closeErr: (Throwable) -> Unit,
        ) -> Unit,
    ): Flow<String> {
        return callbackFlow {
            val gate = gateFor(model)
            val permitAcquired = CompletableDeferred<Unit>()

            val closed = AtomicBoolean(false)

            fun closeOnce(cause: Throwable? = null) {
                if (!closed.compareAndSet(false, true)) return
                if (cause == null) close() else close(cause)
            }

            fun emitSafely(chunk: String) {
                if (chunk.isEmpty()) return
                val r = trySend(chunk)
                if (r.isSuccess) return

                launch {
                    runCatching { send(chunk) }
                }
            }

            val job = launch(Dispatchers.Default) {
                var acquired = false
                try {
                    gate.acquire()
                    acquired = true
                    permitAcquired.complete(Unit)

                    block(
                        { emitSafely(it) },
                        { closeOnce(null) },
                        { closeOnce(it) },
                    )
                } catch (_: CancellationException) {
                    closeOnce(null)
                } catch (t: Throwable) {
                    closeOnce(t)
                } finally {
                    if (acquired) {
                        runCatching { gate.release() }
                    }
                }
            }

            awaitClose {
                if (permitAcquired.isCompleted && !closed.get()) {
                    runCatching { SLM.cancel(model) }
                }
                job.cancel()
            }
        }.buffer(STREAM_BUFFER_CAPACITY)
    }

    // ---------------------------------------------------------------------
    // Structured two-step helpers
    // ---------------------------------------------------------------------

    /**
     * Build a legacy-compatible validation user prompt.
     *
     * Notes:
     * - This preserves the previous pipeline shape where repository step builders wrap a user prompt body.
     * - Follow-up payload may be either a direct answer or a history-like blob.
     */
    private fun buildValidationUserPrompt(
        phase: ChatValidation.PromptPhase,
        questionId: String,
        mainAnswer: String,
        followUpAnswerPayload: String?,
    ): String {
        val qid = questionId.trim()
        val main = mainAnswer.trim()

        val fuRaw = followUpAnswerPayload?.trim().orEmpty()
        val hasFollowUp = fuRaw.isNotBlank()

        val treatAsHistory = hasFollowUp && looksLikeFollowUpHistory(fuRaw)

        val extracted: FollowUpTurnExtract? = if (hasFollowUp && treatAsHistory) {
            extractLatestFollowUpTurn(fuRaw)
        } else {
            null
        }

        val followUpAnswerText: String = when {
            !hasFollowUp -> ""
            extracted != null && extracted.answer.isNotBlank() -> extracted.answer.trim()
            !treatAsHistory -> fuRaw
            else -> fuRaw
        }

        val followUpQuestionText: String = when {
            extracted != null && extracted.question.isNotBlank() -> extracted.question.trim()
            else -> ""
        }

        if (hasFollowUp) {
            val sha8 = sha256Hex(followUpAnswerText).take(8)
            dbgLogCompatFollowUpPayload(
                phase = phase,
                latestQuestionLength = followUpQuestionText.length,
                latestAnswerLength = followUpAnswerText.length,
                latestAnswerSha8 = sha8,
                treatAsHistory = treatAsHistory,
            )
        }

        val followUpSection = if (!hasFollowUp) {
            ""
        } else {
            val clippedA = followUpAnswerText.safeTrimAndClip(MAX_FOLLOW_UP_ANSWER_CHARS)
            val clippedQ = followUpQuestionText.safeTrimAndClip(MAX_FOLLOW_UP_QUESTION_CHARS)
            val clippedHistory = if (treatAsHistory) {
                fuRaw.safeTrimAndClip(MAX_FOLLOW_UP_HISTORY_CHARS)
            } else {
                ""
            }

            buildString {
                appendLine("FOLLOW_UP_ANSWER_BEGIN")
                if (clippedA.isNotBlank()) appendLine(clippedA)
                appendLine("FOLLOW_UP_ANSWER_END")
                appendLine()

                appendLine("FOLLOW_UP_QUESTION:")
                if (clippedQ.isNotBlank()) appendLine(clippedQ)
                appendLine()

                if (treatAsHistory) {
                    appendLine("FOLLOW_UP_HISTORY_BEGIN")
                    if (clippedHistory.isNotBlank()) appendLine(clippedHistory)
                    appendLine("FOLLOW_UP_HISTORY_END")
                    appendLine()
                }
            }
        }

        return """
Return exactly ONE JSON object and nothing else.
- No markdown, no code fences, no backticks.
- Output must start with "{" and end with "}".
- Do not repeat the user's full answer.

Valid shapes:
1) {"status":"ACCEPTED","assistantMessage":"..."}
2) {"status":"NEED_FOLLOW_UP","assistantMessage":"...","followUpQuestion":"..."}

Rules:
- Evaluate sufficiency using the COMBINED information:
  MAIN_ANSWER plus FOLLOW_UP_ANSWER (and FOLLOW_UP_HISTORY if present).
- If the combined information is sufficient: ACCEPTED.
- Else: NEED_FOLLOW_UP and ask exactly ONE concise follow-up question.
- If FOLLOW_UP_ANSWER is non-empty, do NOT repeat the same follow-up question again.
  Either ACCEPTED, or ask a DIFFERENT and more specific follow-up question.

QUESTION_ID: $qid

MAIN_ANSWER_BEGIN
$main
MAIN_ANSWER_END

$followUpSection
""".trimIndent()
    }

    /**
     * Collect one structured JSON result for a single phase and optionally mirror it into ChatStreamBridge.
     */
    private suspend fun collectStructuredJsonForPhase(
        model: Model,
        input: String,
        phase: ChatStreamEvent.Phase,
        streamBridge: ChatStreamBridge?,
        timeoutMs: Long,
        maxChars: Int,
    ): CollectedResult {
        val sessionId = streamBridge?.begin(phase)

        return try {
            val result = runSdkAndCollectWithBudget(
                model = model,
                input = input,
                timeoutMs = timeoutMs,
                maxChars = maxChars,
                onDelta = { chunk ->
                    if (chunk.isNotEmpty() && sessionId != null) {
                        streamBridge.emitChunk(sessionId, chunk)
                    }
                },
            )

            if (sessionId != null) {
                when {
                    result.reason.startsWith("COMPLETED") -> {
                        streamBridge.end(sessionId)
                    }
                    result.reason.startsWith("CANCELLED") -> {
                        streamBridge.error(
                            sessionId = sessionId,
                            token = ChatStreamEvent.Codes.CANCELLED,
                            code = ChatStreamEvent.Codes.CANCELLED,
                        )
                    }
                    result.reason.startsWith("TIMEOUT") -> {
                        streamBridge.error(
                            sessionId = sessionId,
                            token = ChatStreamEvent.Codes.TIMEOUT,
                            code = ChatStreamEvent.Codes.TIMEOUT,
                        )
                    }
                    result.reason.startsWith("MAX_CHARS") -> {
                        streamBridge.error(
                            sessionId = sessionId,
                            token = "max_chars",
                            code = ChatStreamEvent.Codes.ERROR,
                        )
                    }
                    else -> {
                        streamBridge.error(
                            sessionId = sessionId,
                            token = result.errorToken ?: ChatStreamEvent.Codes.ERROR,
                            code = ChatStreamEvent.Codes.ERROR,
                        )
                    }
                }
            }

            result
        } catch (ce: CancellationException) {
            if (sessionId != null) {
                runCatching {
                    streamBridge.error(
                        sessionId = sessionId,
                        token = ChatStreamEvent.Codes.CANCELLED,
                        code = ChatStreamEvent.Codes.CANCELLED,
                    )
                }
            }
            throw ce
        } catch (t: Throwable) {
            if (sessionId != null) {
                runCatching {
                    streamBridge.error(
                        sessionId = sessionId,
                        token = t.javaClass.simpleName.ifBlank { ChatStreamEvent.Codes.ERROR },
                        code = ChatStreamEvent.Codes.ERROR,
                    )
                }
            }
            throw t
        }
    }

    private fun parseStep1EvalStrict(
        raw: String,
        fallbackReason: String,
    ): ChatValidation.Step1EvalResult {
        val json = extractFirstCompleteJsonObjectBestEffort(raw)
        if (json == null) {
            return ChatValidation.Step1EvalResult(
                status = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                score = 0,
                reason = fallbackReason,
                missing = emptyList(),
            )
        }

        return runCatching {
            val obj = JSONObject(json)

            val rawStatus = obj.optString("status", "").trim()
            val status = when (rawStatus) {
                "ACCEPTED" -> ChatModels.ValidationStatus.ACCEPTED
                "NEED_FOLLOW_UP" -> ChatModels.ValidationStatus.NEED_FOLLOW_UP
                else -> ChatModels.ValidationStatus.NEED_FOLLOW_UP
            }

            val score = obj.optInt("score", 0).coerceIn(0, 100)
            val reason = obj.optString("reason", "").trim().ifBlank { fallbackReason }

            val missing = buildList {
                val arr = obj.optJSONArray("missing") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim()
                    if (item.isNotBlank()) add(item)
                }
            }

            ChatValidation.Step1EvalResult(
                status = status,
                score = score,
                reason = reason,
                missing = missing,
            )
        }.getOrElse {
            ChatValidation.Step1EvalResult(
                status = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                score = 0,
                reason = fallbackReason,
                missing = emptyList(),
            )
        }
    }

    private fun parseStep2FollowUpStrict(
        raw: String,
        fallbackFollowUp: String,
    ): ChatValidation.Step2FollowUpResult {
        val json = extractFirstCompleteJsonObjectBestEffort(raw)
        if (json == null) {
            return ChatValidation.Step2FollowUpResult(
                status = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "",
                followUpQuestion = fallbackFollowUp,
            )
        }

        return runCatching {
            val obj = JSONObject(json)

            val assistantMessage = obj.optString("assistantMessage", "")
                .replace("\u0000", "")
                .trim()
                .ifBlank { "" }

            val followUpQuestion = normalizeFollowUpQuestion(
                obj.optString("followUpQuestion", ""),
            ) ?: fallbackFollowUp

            ChatValidation.Step2FollowUpResult(
                status = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = assistantMessage,
                followUpQuestion = followUpQuestion,
            )
        }.getOrElse {
            ChatValidation.Step2FollowUpResult(
                status = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "",
                followUpQuestion = fallbackFollowUp,
            )
        }
    }

    private fun fallbackStep1Reason(
        collectedReason: String,
        errorToken: String?,
    ): String {
        return when {
            collectedReason.startsWith("TIMEOUT") ->
                "Validation timed out before a reliable score was produced."
            collectedReason.startsWith("MAX_CHARS") ->
                "Validation output was clipped before a reliable score was produced."
            collectedReason.startsWith("ERROR") ->
                when (errorToken) {
                    "ModelNotReadyException" -> "Model is still warming up."
                    else -> "Validation failed before a reliable score was produced."
                }
            collectedReason.startsWith("CANCELLED") ->
                "Validation was cancelled."
            else ->
                "Validation result could not be parsed."
        }
    }

    // ---------------------------------------------------------------------
    // SDK helpers
    // ---------------------------------------------------------------------

    private fun runSdkAndStream(
        model: Model,
        input: String,
        onDelta: (String) -> Unit,
        onTerminal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val p = input.trim()
        if (p.isBlank()) {
            onTerminal("EMPTY_INPUT")
            return
        }

        val ended = AtomicBoolean(false)

        fun terminalOnce(reason: String) {
            if (ended.compareAndSet(false, true)) {
                onTerminal(reason)
            }
        }

        fun errorOnce(msg: String) {
            if (ended.compareAndSet(false, true)) {
                onError(msg)
            }
        }

        SLM.runInference(
            model = model,
            input = p,
            resultListener = { partialResult, done ->
                if (partialResult.isNotEmpty()) onDelta(partialResult)
                if (done) terminalOnce("done on resultListener")
            },
            cleanUpListener = { terminalOnce("done on cleanUpListener") },
            onError = { msg -> errorOnce(msg) },
            images = emptyList(),
            audioClips = emptyList(),
            notifyCancelToOnError = false,
        )
    }

    private suspend fun runSdkAndCollectWithBudget(
        model: Model,
        input: String,
        timeoutMs: Long,
        maxChars: Int,
        onDelta: ((String) -> Unit)? = null,
    ): CollectedResult {
        val p = input.trim()
        if (p.isBlank()) {
            return CollectedResult(
                text = "",
                reason = "EMPTY_INPUT",
                errorToken = null,
                isEarlyStop = false,
            )
        }

        val safeMax = maxChars.coerceAtLeast(0)
        val out = StringBuilder(minOf(safeMax, 4_096))
        val done = CompletableDeferred<Unit>()
        val errRef = AtomicReference<String?>(null)
        val reasonRef = AtomicReference("UNKNOWN")
        val earlyStopRef = AtomicBoolean(false)
        val finished = AtomicBoolean(false)

        fun finishOnce(reason: String, earlyStop: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            reasonRef.set(reason)
            if (earlyStop) earlyStopRef.set(true)
            if (!done.isCompleted) done.complete(Unit)
        }

        runSdkAndStream(
            model = model,
            input = p,
            onDelta = { chunk ->
                if (chunk.isEmpty()) return@runSdkAndStream
                if (finished.get()) return@runSdkAndStream

                val remaining = safeMax - out.length
                if (remaining <= 0) {
                    earlyStopRef.set(true)
                    finishOnce(reason = "MAX_CHARS", earlyStop = true)
                    runCatching { SLM.cancel(model) }
                    return@runSdkAndStream
                }

                val appended = if (chunk.length <= remaining) {
                    out.append(chunk)
                    chunk
                } else {
                    val part = chunk.take(remaining)
                    out.append(part)
                    earlyStopRef.set(true)
                    finishOnce(reason = "MAX_CHARS", earlyStop = true)
                    runCatching { SLM.cancel(model) }
                    part
                }

                if (appended.isNotEmpty()) {
                    runCatching { onDelta?.invoke(appended) }
                }
            },
            onTerminal = { msg ->
                val current = reasonRef.get()
                if (current == "MAX_CHARS") {
                    finishOnce(reason = "MAX_CHARS; $msg", earlyStop = true)
                } else {
                    finishOnce(reason = "COMPLETED; $msg", earlyStop = false)
                }
            },
            onError = { msg ->
                val current = reasonRef.get()
                if (msg.equals("Cancelled", ignoreCase = true)) {
                    when (current) {
                        "MAX_CHARS" -> finishOnce(reason = "MAX_CHARS; Cancelled", earlyStop = true)
                        "TIMEOUT" -> finishOnce(reason = "TIMEOUT; Cancelled", earlyStop = true)
                        else -> finishOnce(reason = "CANCELLED", earlyStop = true)
                    }
                } else {
                    errRef.set(msg)
                    finishOnce(reason = "ERROR", earlyStop = true)
                }
            },
        )

        val completed = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            done.await()
            true
        } == true

        if (!completed) {
            earlyStopRef.set(true)
            reasonRef.set("TIMEOUT")
            runCatching { SLM.cancel(model) }
        }

        return CollectedResult(
            text = out.toString(),
            reason = reasonRef.get(),
            errorToken = errRef.get(),
            isEarlyStop = earlyStopRef.get() || !completed,
        )
    }

    private data class CollectedResult(
        val text: String,
        val reason: String,
        val errorToken: String?,
        val isEarlyStop: Boolean,
    )

    // ---------------------------------------------------------------------
    // JSON helpers
    // ---------------------------------------------------------------------

    /**
     * Extract the first complete JSON object from a text stream.
     */
    private fun extractFirstCompleteJsonObjectBestEffort(text: String): String? {
        val s = text
        if (s.isBlank()) return null

        var start = -1
        var depth = 0
        var inString = false
        var escape = false

        for (i in s.indices) {
            val c = s[i]

            if (start < 0) {
                if (c == '{') {
                    start = i
                    depth = 1
                    inString = false
                    escape = false
                }
                continue
            }

            if (escape) {
                escape = false
                continue
            }

            if (c == '\\' && inString) {
                escape = true
                continue
            }

            if (c == '"') {
                inString = !inString
                continue
            }

            if (inString) continue

            when (c) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return s.substring(start, i + 1).trim()
                    }
                }
            }
        }

        return null
    }

    private fun normalizeFollowUpQuestion(text: String?): String? {
        val raw = text ?: return null
        var t = raw.replace("\u0000", "").trim()
        if (t.isBlank()) return null

        val prefixPatterns = listOf(
            "follow-up question:",
            "follow up question:",
            "follow-up:",
            "follow up:",
            "followup:",
            "question:",
            "next question:",
        )

        val lower0 = t.lowercase(Locale.US)
        for (p in prefixPatterns) {
            if (lower0.startsWith(p)) {
                t = t.drop(p.length).trim()
                break
            }
        }

        val l2 = t.lowercase(Locale.US)
        val garbage = setOf(
            "none",
            "(none)",
            "n/a",
            "na",
            "null",
            "nil",
            "no",
            "nope",
            "no follow up",
            "no follow-up",
            "skip",
            "0",
            "-",
        )
        if (l2 in garbage) return null
        if (t.length < 3) return null

        return t
    }

    // ---------------------------------------------------------------------
    // Follow-up payload parsing helpers
    // ---------------------------------------------------------------------

    private fun looksLikeFollowUpHistory(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        return raw.lineSequence().any { line -> FOLLOW_UP_HISTORY_LINE_RE.matches(line.trim()) }
    }

    private fun extractLatestFollowUpTurn(payload: String): FollowUpTurnExtract? {
        val normalized = payload
            .replace("\u0000", "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val lines = normalized.split('\n')
        if (lines.isEmpty()) return null

        var currentQ: String? = null
        var currentA: String? = null

        var latestQ: String? = null
        var latestA: String? = null
        var latestIdx = -1

        fun readContinuation(startIndex: Int, head: String): Pair<String, Int> {
            val buf = StringBuilder().append(head)
            var j = startIndex + 1
            while (j < lines.size && !ANY_MARKER_RE.matches(lines[j].trim())) {
                buf.append('\n').append(lines[j])
                j++
            }
            return buf.toString() to j
        }

        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()

            CURRENT_Q_RE.matchEntire(t)?.let { m ->
                val (body, nextIdx) = readContinuation(i, m.groupValues[1])
                currentQ = body
                i = nextIdx
                continue
            }

            CURRENT_A_RE.matchEntire(t)?.let { m ->
                val (body, nextIdx) = readContinuation(i, m.groupValues[1])
                currentA = body
                i = nextIdx
                continue
            }

            val qm = TURN_Q_RE.matchEntire(t)
            if (qm != null) {
                val idx = qm.groupValues[1].toIntOrNull() ?: -1
                val (body, nextIdx) = readContinuation(i, qm.groupValues[2])
                if (idx >= latestIdx) {
                    latestIdx = idx
                    latestQ = body
                }
                i = nextIdx
                continue
            }

            val am = TURN_A_RE.matchEntire(t)
            if (am != null) {
                val idx = am.groupValues[1].toIntOrNull() ?: -1
                val (body, nextIdx) = readContinuation(i, am.groupValues[2])
                if (idx >= latestIdx) {
                    latestIdx = idx
                    latestA = body
                }
                i = nextIdx
                continue
            }

            i++
        }

        val q = (currentQ ?: latestQ).orEmpty().trim()
        val a = (currentA ?: latestA).orEmpty().trim()

        if (q.isBlank() && a.isBlank()) return null
        return FollowUpTurnExtract(question = q, answer = a)
    }

    private data class FollowUpTurnExtract(
        val question: String,
        val answer: String,
    )

    // ---------------------------------------------------------------------
    // Debug logging helpers
    // ---------------------------------------------------------------------

    private fun dbgLogPrompt(
        traceId: String,
        step: String,
        questionId: String,
        prompt: String,
    ) {
        if (!dbg.enabled) return
        val len = prompt.length
        val sha = sha256Hex(prompt).take(8)

        AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId PROMPT meta len=$len sha8=$sha")

        if (dbg.logFullText) {
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId PROMPT full:\n$prompt")
            return
        }
        if (dbg.logClippedText && dbg.maxPromptLogChars > 0) {
            val clip = clipForLog(prompt, dbg.maxPromptLogChars)
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId PROMPT clip(${clip.length}/$len):\n$clip")
        }
    }

    private fun dbgLogResult(
        traceId: String,
        step: String,
        questionId: String,
        reason: String,
        isEarlyStop: Boolean,
        text: String,
    ) {
        if (!dbg.enabled) return
        val len = text.length
        val sha = sha256Hex(text).take(8)

        AppLog.d(
            TAG,
            "[$step] trace=$traceId qid=$questionId RESULT meta len=$len sha8=$sha reason=$reason earlyStop=$isEarlyStop",
        )

        if (dbg.logFullText) {
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId RESULT full:\n$text")
            return
        }
        if (dbg.logClippedText && dbg.maxResultLogChars > 0) {
            val clip = clipForLog(text, dbg.maxResultLogChars)
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId RESULT clip(${clip.length}/$len):\n$clip")
        }
    }

    private fun dbgLogStructuredStep1(
        questionId: String,
        rawEvalJson: String,
        parsed: ChatValidation.Step1EvalResult,
    ) {
        if (!dbg.enabled) return

        val sha = sha256Hex(rawEvalJson).take(8)
        AppLog.d(
            TAG,
            "[STEP1] qid=$questionId status=${parsed.status.name} score=${parsed.score} " +
                    "reasonLen=${parsed.reason.length} rawLen=${rawEvalJson.length} rawSha8=$sha missing=${parsed.missing.size}",
        )

        if (dbg.logFullText) {
            AppLog.d(TAG, "[STEP1] qid=$questionId raw(full):\n$rawEvalJson")
        } else if (dbg.logClippedText && dbg.maxResultLogChars > 0) {
            AppLog.d(TAG, "[STEP1] qid=$questionId raw(clip):\n${clipForLog(rawEvalJson, dbg.maxResultLogChars)}")
        }
    }

    private fun dbgLogStructuredStep2(
        questionId: String,
        rawFollowUpJson: String,
        parsed: ChatValidation.Step2FollowUpResult,
    ) {
        if (!dbg.enabled) return

        val sha = sha256Hex(rawFollowUpJson).take(8)
        AppLog.d(
            TAG,
            "[STEP2] qid=$questionId status=${parsed.status.name} aLen=${parsed.assistantMessage?.length ?: 0} " +
                    "qLen=${parsed.followUpQuestion?.length ?: 0} rawLen=${rawFollowUpJson.length} rawSha8=$sha",
        )

        if (dbg.logFullText) {
            AppLog.d(TAG, "[STEP2] qid=$questionId raw(full):\n$rawFollowUpJson")
        } else if (dbg.logClippedText && dbg.maxResultLogChars > 0) {
            AppLog.d(TAG, "[STEP2] qid=$questionId raw(clip):\n${clipForLog(rawFollowUpJson, dbg.maxResultLogChars)}")
        }
    }

    private fun dbgLogCompatFollowUpPayload(
        phase: ChatValidation.PromptPhase,
        latestQuestionLength: Int,
        latestAnswerLength: Int,
        latestAnswerSha8: String,
        treatAsHistory: Boolean,
    ) {
        if (!dbg.enabled) return
        AppLog.d(
            TAG,
            "followUpDetected: phase=${phase.name} treatAsHistory=$treatAsHistory " +
                    "latestQlen=$latestQuestionLength latestAlen=$latestAnswerLength latestAsha8=$latestAnswerSha8",
        )
    }

    private fun clipForLog(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text

        val head = (maxChars * 0.7).toInt().coerceAtLeast(0)
        val tail = (maxChars - head - 64).coerceAtLeast(0)

        val h = text.take(head)
        val suffix = if (tail > 0) text.takeLast(tail) else ""
        val omitted = (text.length - (h.length + suffix.length)).coerceAtLeast(0)

        return buildString {
            append(h)
            append("\n…(omitted ").append(omitted).append(" chars)…\n")
            append(suffix)
        }
    }

    // ---------------------------------------------------------------------
    // Config + model resolution
    // ---------------------------------------------------------------------

    /**
     * Return system prompt only if it is already available without I/O.
     */
    private fun getSystemPromptFromCacheOnly(): String? {
        val cfg = InstalledSurveyConfigStore.getOrNull() ?: cachedConfig.get()
        return runCatching { cfg?.composeSystemPrompt() }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun getConfigSuspendBestEffort(): SurveyConfig? {
        InstalledSurveyConfigStore.getOrNull()?.let { cfg ->
            cachedConfig.set(cfg)
            return cfg
        }

        cachedConfig.get()?.let { return it }

        if (!allowAssetConfigFallback) {
            SafeLog.w(TAG, "Config missing: installed=null; assetFallback=false")
            return null
        }

        return configLoadMutex.withLock {
            InstalledSurveyConfigStore.getOrNull()?.let { cfg ->
                cachedConfig.set(cfg)
                return@withLock cfg
            }
            cachedConfig.get()?.let { return@withLock it }

            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    SurveyConfigLoader.fromAssetsValidated(appContext, configAssetName)
                }.onFailure { t ->
                    SafeLog.e(TAG, "Config fallback load failed type=${t::class.java.simpleName}", t)
                }.getOrNull()
            }

            if (loaded != null) {
                SafeLog.w(TAG, "Config loaded from assets as fallback (NOT installed): asset=$configAssetName")
                cachedConfig.set(loaded)
            }
            loaded
        }
    }

    private fun resolveModelFile(cfg: SurveyConfig?): File {
        val rawFileName = cfg?.modelDefaults?.defaultFileName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackModelFileName

        val safeName = sanitizeSimpleFileName(rawFileName) ?: fallbackModelFileName
        return File(appContext.filesDir, safeName)
    }

    private fun sanitizeSimpleFileName(name: String): String? {
        val n = name.trim()
        if (n.isBlank()) return null
        if (n.contains('/') || n.contains('\\')) return null
        if (n.contains("..")) return null
        if (n.length > 200) return null
        return n
    }

    private data class FileStat(
        val exists: Boolean,
        val isFile: Boolean,
        val length: Long,
    )

    private fun safeFileStat(file: File): FileStat {
        return runCatching {
            FileStat(
                exists = file.exists(),
                isFile = file.isFile,
                length = file.length(),
            )
        }.getOrElse {
            FileStat(exists = false, isFile = false, length = 0L)
        }
    }

    private fun getOrCreateModel(cfg: SurveyConfig?, file: File): Model {
        val modelName = cfg?.modelDefaults?.modelName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackModelName

        val key = "$modelName::${file.absolutePath}"
        val holder = modelCache.getOrPut(key) {
            val modelConfig: Map<Model.ConfigKey, Any> = Model.buildModelConfigSafe(cfg?.slm)

            val fileLen = runCatching { file.length() }.getOrDefault(-1L)
            AppLog.d(TAG, "ModelCache create: modelName=$modelName cfgPresent=${cfg != null} fileLen=$fileLen")

            ModelHolder(
                model = Model(
                    name = modelName,
                    taskPath = file.absolutePath,
                    config = modelConfig,
                ),
            )
        }
        return holder.model
    }

    private data class ModelHolder(
        val model: Model,
        val initMutex: Mutex = Mutex(),
        val initialized: AtomicBoolean = AtomicBoolean(false),
        val initSignatureRef: AtomicReference<String?> = AtomicReference(null),
    )

    private data class InitOptions(
        val supportImage: Boolean,
        val supportAudio: Boolean,
        val systemMessage: Message?,
        val tools: List<Any>,
    ) {
        fun signature(): String {
            val sys = if (systemMessage != null) 1 else 0
            return "img=${if (supportImage) 1 else 0};aud=${if (supportAudio) 1 else 0};sys=$sys;tools=${tools.size}"
        }

        companion object {
            fun defaultForRepo(): InitOptions {
                return InitOptions(
                    supportImage = false,
                    supportAudio = false,
                    systemMessage = null,
                    tools = emptyList(),
                )
            }

            fun fromWarmupOptions(options: WarmupController.Options): InitOptions {
                val sys = options.systemMessage as? Message
                return InitOptions(
                    supportImage = options.supportImage,
                    supportAudio = options.supportAudio,
                    systemMessage = sys,
                    tools = options.tools,
                )
            }
        }
    }

    private suspend fun awaitInitializedModelOnce(
        model: Model,
        initOptions: InitOptions,
    ) {
        val key = "${model.name}::${model.taskPath}"
        val holder = modelCache[key]
        if (holder == null) {
            initializeSdkIfNeeded(
                model = model,
                initOptions = initOptions,
            )
            return
        }

        val desiredSig = initOptions.signature()
        val existingSig = holder.initSignatureRef.get()
        if (holder.initialized.get() && existingSig == desiredSig) return

        holder.initMutex.withLock {
            val sigNow = holder.initSignatureRef.get()
            if (holder.initialized.get() && sigNow == desiredSig) return@withLock

            initializeSdkIfNeeded(
                model = model,
                initOptions = initOptions,
            )

            holder.initialized.set(true)
            holder.initSignatureRef.set(desiredSig)
        }
    }

    private suspend fun initializeSdkIfNeeded(
        model: Model,
        initOptions: InitOptions,
    ) {
        runCatching { SLM.setApplicationContext(appContext) }

        AppLog.d(TAG, "initializeIfNeeded: model='${model.name}'")

        SLM.initializeIfNeeded(
            context = appContext,
            model = model,
            supportImage = initOptions.supportImage,
            supportAudio = initOptions.supportAudio,
            systemMessage = initOptions.systemMessage,
            tools = initOptions.tools,
        )
    }

    private suspend fun resetConversationBestEffort(model: Model, reason: String) {
        val ok = runCatching {
            SLM.resetConversationAndAwait(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemMessage = null,
                tools = emptyList(),
                timeoutMs = RESET_CONVERSATION_TIMEOUT_MS,
            )
        }.getOrNull() == true

        if (!ok) {
            AppLog.w(
                TAG,
                "resetConversationAndAwait failed or timed out; continuing: reason='$reason' model='${model.name}'",
            )
        }
    }

    private fun extractQuestionId(prompt: String): String? {
        val m = Regex("""(?m)^\s*QUESTION_ID:\s*([^\s]+)\s*$""").find(prompt) ?: return null
        return m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun gateFor(model: Model): Semaphore {
        val key = "${model.name}::${model.taskPath}"
        return modelGates.getOrPut(key) { Semaphore(permits = 1) }
    }

    // ---------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------

    private fun sha256Hex(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(dig.size * 2)
        for (b in dig) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Trim + clip while preserving surrogate pairs.
     */
    private fun String.safeTrimAndClip(limit: Int): String {
        val t = trim()
        val n = limit.coerceAtLeast(0)
        if (n == 0) return ""
        if (t.length <= n) return t

        var end = n
        if (end > 0) {
            val last = t[end - 1]
            val next = t[end]
            if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
                end -= 1
            }
        }
        if (end <= 0) return ""
        return t.substring(0, end)
    }

    companion object {
        private const val TAG: String = "SlmRepository"

        private const val RESET_CONVERSATION_TIMEOUT_MS: Long = 15_000L
        private const val EARLY_STOP_STABILIZE_DELAY_MS: Long = 250L

        // Step 1 budgets
        private const val ASSESSMENT_TIMEOUT_MS: Long = 60_000L
        private const val ASSESSMENT_MAX_CHARS: Int = 8_192
        private const val ASSESSMENT_USER_PROMPT_MAX_CHARS: Int = 4_000

        // Step 2 budgets
        private const val FOLLOWUP_TIMEOUT_MS: Long = 60_000L
        private const val FOLLOWUP_MAX_CHARS: Int = 8_192
        private const val FOLLOWUP_USER_PROMPT_MAX_CHARS: Int = 4_000
        private const val FOLLOWUP_ASSESSMENT_JSON_MAX_CHARS: Int = 2_000

        // Follow-up payload guards
        private const val MAX_FOLLOW_UP_ANSWER_CHARS = 8_000
        private const val MAX_FOLLOW_UP_QUESTION_CHARS = 2_000
        private const val MAX_FOLLOW_UP_HISTORY_CHARS = 16_000

        // Stream buffering
        private const val STREAM_BUFFER_CAPACITY: Int = 64

        // Legacy stream markers for request(prompt)
        private const val STREAM_ASSESSMENT_PREFIX: String = "\n[ASSESSMENT]\n"
        private const val STREAM_ASSESSMENT_RESULT_PREFIX: String = "[ASSESSMENT_RESULT]"
        private const val STREAM_FOLLOWUP_PREFIX: String = "\n[FOLLOWUP]\n"

        private const val HEX = "0123456789abcdef"

        private val FOLLOW_UP_HISTORY_LINE_RE = Regex("""^FOLLOW_UP_\d+_[QA]:\s*.*$""")
        private val CURRENT_Q_RE = Regex("""^CURRENT_FOLLOW_UP_Q:\s*(.*)$""")
        private val CURRENT_A_RE = Regex("""^CURRENT_FOLLOW_UP_A:\s*(.*)$""")
        private val TURN_Q_RE = Regex("""^FOLLOW_UP_(\d+)_Q:\s*(.*)$""")
        private val TURN_A_RE = Regex("""^FOLLOW_UP_(\d+)_A:\s*(.*)$""")

        private val ANY_MARKER_RE = Regex(
            """^(CURRENT_FOLLOW_UP_[QA]|FOLLOW_UP_\d+_[QA]|FOLLOW_UP_TURNS:|FOLLOW_UP_HISTORY_BEGIN|FOLLOW_UP_HISTORY_END)\s*:?.*$""",
        )

        private val modelGates: ConcurrentHashMap<String, Semaphore> = ConcurrentHashMap()
    }
}