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
import com.negi.surveys.config.NodeType
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.warmup.WarmupController
import java.io.File
import java.security.MessageDigest
import java.util.Locale
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository that orchestrates LiteRT-LM inference for:
 * - One-step generation
 * - Structured two-step validation
 *
 * Two-step contract:
 * 1) Step 1: Assessment JSON
 * 2) Step 2: Follow-up JSON
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
    private val enableTwoStepEval: Boolean = true,
    private val acceptScoreThreshold: Int = 70,
    private val allowAssetConfigFallback: Boolean = false,
    private val promptBuilder: SlmPromptBuilderI = DefaultSlmPromptBuilder,
    debug: DebugConfig = DebugConfig(),
) : ChatValidation.RepositoryI, WarmupController.WarmupCapableRepository {

    data class DebugConfig(
        val enabled: Boolean = false,
        val logClippedText: Boolean = true,
        val logFullText: Boolean = false,
        val maxPromptLogChars: Int = 2_000,
        val maxResultLogChars: Int = 2_000,
        val streamEvalOutputToClient: Boolean = false,
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

    private data class NodePromptContext(
        val questionId: String,
        val nodeType: NodeType,
        val questionText: String,
        val options: List<String>,
        val evalTaskPrompt: String?,
        val followUpTaskPrompt: String?,
    )

    private enum class ValidationPayloadKind {
        STEP1_EVAL,
        STEP2_FOLLOW_UP,
    }

    private val dbg: DebugConfig = debug.normalized()
    private val appContext: Context = context.applicationContext
    private val enableTwoStepAssessment: Boolean = enableTwoStepEval
    private val cachedConfig = AtomicReference<SurveyConfig?>(null)
    private val configLoadMutex = Mutex()
    private val modelCache = ConcurrentHashMap<String, ModelHolder>()

    class ModelNotReadyException(message: String) : IllegalStateException(message)

    fun buildPrompt(userPrompt: String): String {
        val u = userPrompt.trim()
        if (u.isBlank()) return ""
        val sys = getSystemPromptFromCacheOnly()
        return promptBuilder.buildAnswerLikePrompt(systemPrompt = sys, userPrompt = u)
    }

    override fun buildPrompt(
        userPrompt: String,
        phase: ChatValidation.PromptPhase,
    ): String {
        val u = userPrompt.trim()
        if (u.isBlank()) return ""
        val cfg = InstalledSurveyConfigStore.getOrNull() ?: cachedConfig.get()
        val baseSystem = resolveBaseSystemPromptForPhase(cfg = cfg, phase = phase)
        return promptBuilder.buildAnswerLikePrompt(
            systemPrompt = baseSystem?.trim()?.takeIf { it.isNotBlank() },
            userPrompt = u,
        )
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
            awaitInitializedModelOnce(model = model, initOptions = InitOptions.defaultForRepo())
        }

        return if (enableTwoStepAssessment) {
            requestAssessmentThenFollowUp(model = model, userPrompt = input)
        } else {
            val sys = runCatching { cfg?.composeSystemPrompt() }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
            val p = promptBuilder.buildAnswerLikePrompt(systemPrompt = sys, userPrompt = input)
            requestOneStep(model = model, prompt = p)
        }
    }

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
            awaitInitializedModelOnce(model = model, initOptions = InitOptions.defaultForRepo())
        }

        val questionId = request.questionId.trim().ifBlank { "AUTO-${UUID.randomUUID().toString().take(8)}" }
        val promptContext = resolveNodePromptContext(cfg = cfg, questionId = questionId)
        dbgLogNodePromptContext(promptContext)

        val step1Payload = buildValidationDataPayload(
            payloadKind = ValidationPayloadKind.STEP1_EVAL,
            promptContext = promptContext,
            mainAnswer = request.mainAnswer,
            followUpAnswerPayload = request.followUpAnswerPayload,
        )

        if (resetConversationEachRequest) {
            resetConversationBestEffort(model, reason = "step1")
        }

        val step1Body = promptBuilder.buildEvalScorePrompt(
            questionId = questionId,
            userPrompt = step1Payload,
            acceptScoreThreshold = acceptScoreThreshold,
            userPromptMaxChars = ASSESSMENT_USER_PROMPT_MAX_CHARS,
        )

        val step1System = composeStructuredSystemPrompt(
            baseSystemPrompt = resolveBaseSystemPromptForPhase(cfg = cfg, phaseName = "VALIDATE_MAIN"),
            nodeTaskPrompt = promptContext.evalTaskPrompt,
        )

        val step1Prompt = composeSdkInput(systemPrompt = step1System, promptBody = step1Body)

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

        val parsedStep1 = parseStep1EvalFlexible(
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
        val canonicalEvalJson = buildCanonicalStep1EvalJson(finalStep1)

        dbgLogStructuredStep1(questionId = questionId, rawEvalJson = rawEvalJson, parsed = finalStep1)

        if (finalStatus == ChatModels.ValidationStatus.ACCEPTED) {
            return ChatValidation.TwoStepAssessmentResult(
                step1 = finalStep1,
                step2 = null,
                rawEvalJson = canonicalEvalJson,
                rawFollowUpJson = null,
            )
        }

        if (resetConversationEachRequest) {
            resetConversationBestEffort(model, reason = "step2")
        }

        val step2Payload = buildValidationDataPayload(
            payloadKind = ValidationPayloadKind.STEP2_FOLLOW_UP,
            promptContext = promptContext,
            mainAnswer = request.mainAnswer,
            followUpAnswerPayload = request.followUpAnswerPayload,
        )

        val step2Body = promptBuilder.buildFollowUpPrompt(
            questionId = questionId,
            userPrompt = step2Payload,
            evalJson = canonicalEvalJson,
            acceptScoreThreshold = acceptScoreThreshold,
            userPromptMaxChars = FOLLOWUP_USER_PROMPT_MAX_CHARS,
            evalJsonMaxChars = FOLLOWUP_ASSESSMENT_JSON_MAX_CHARS,
        )

        val step2System = composeStructuredSystemPrompt(
            baseSystemPrompt = resolveBaseSystemPromptForPhase(cfg = cfg, phaseName = "VALIDATE_FOLLOW_UP"),
            nodeTaskPrompt = promptContext.followUpTaskPrompt,
        )

        val step2Prompt = composeSdkInput(systemPrompt = step2System, promptBody = step2Body)

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

        val parsedStep2 = parseStep2FollowUpFlexible(
            raw = rawFollowUpJson,
            fallbackFollowUp = request.fallbackFollowUp,
        )

        val previousTurn = request.followUpAnswerPayload
            ?.takeIf { it.isNotBlank() && looksLikeFollowUpHistory(it) }
            ?.let { extractLatestFollowUpTurn(it) }

        val finalFollowUpQuestion = resolveFinalFollowUpQuestion(
            questionId = questionId,
            parsedQuestion = parsedStep2.followUpQuestion,
            previousQuestion = previousTurn?.question,
            defaultFallback = request.fallbackFollowUp,
            followUpAnswerPayload = request.followUpAnswerPayload,
        )

        val finalStep2 = parsedStep2.copy(
            assistantMessage = parsedStep2.assistantMessage
                ?.safeTrimAndClip(MAX_STEP2_ASSISTANT_MESSAGE_CHARS)
                ?.takeIf { it.isNotBlank() },
            followUpQuestion = finalFollowUpQuestion.safeTrimAndClip(MAX_FOLLOW_UP_QUESTION_CHARS),
        )

        val canonicalFollowUpJson = buildCanonicalStep2FollowUpJson(finalStep2)

        dbgLogStructuredStep2(questionId = questionId, rawFollowUpJson = rawFollowUpJson, parsed = finalStep2)
        dbgLogFollowUpResolution(
            questionId = questionId,
            previousQuestion = previousTurn?.question,
            modelQuestion = parsedStep2.followUpQuestion,
            finalQuestion = finalStep2.followUpQuestion,
        )

        return ChatValidation.TwoStepAssessmentResult(
            step1 = finalStep1,
            step2 = finalStep2.copy(status = ChatModels.ValidationStatus.NEED_FOLLOW_UP),
            rawEvalJson = canonicalEvalJson,
            rawFollowUpJson = canonicalFollowUpJson,
        )
    }

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
            awaitInitializedModelOnce(model = model, initOptions = InitOptions.fromWarmupOptions(options))
            true
        }.getOrElse { t ->
            AppLog.w(TAG, "warmup: failed type=${t.javaClass.simpleName}")
            false
        }
    }

    private fun requestAssessmentThenFollowUp(model: Model, userPrompt: String): Flow<String> {
        val u = userPrompt.trim()
        if (u.isBlank()) return emptyFlow()

        return streamingFlow(model = model) { emit, closeOk, closeErr ->
            val traceId = UUID.randomUUID().toString().take(8)
            val streamAssessmentToClient = dbg.streamAssessmentOutputToClient
            val questionId = extractQuestionId(u) ?: "AUTO-${UUID.randomUUID().toString().take(8)}"
            val cfg = InstalledSurveyConfigStore.getOrNull() ?: cachedConfig.get()
            val promptContext = resolveNodePromptContext(cfg, questionId)

            if (resetConversationEachRequest) {
                resetConversationBestEffort(model, reason = "assessment")
            }

            val assessmentBody = promptBuilder.buildEvalScorePrompt(
                questionId = questionId,
                userPrompt = u,
                acceptScoreThreshold = acceptScoreThreshold,
                userPromptMaxChars = ASSESSMENT_USER_PROMPT_MAX_CHARS,
            )

            val assessmentPrompt = composeSdkInput(
                systemPrompt = composeStructuredSystemPrompt(
                    baseSystemPrompt = resolveBaseSystemPromptForPhase(cfg = cfg, phaseName = "VALIDATE_MAIN"),
                    nodeTaskPrompt = promptContext.evalTaskPrompt,
                ),
                promptBody = assessmentBody,
            )

            dbgLogPrompt(traceId = traceId, step = "ASSESSMENT PHASE", questionId = questionId, prompt = assessmentPrompt)

            if (streamAssessmentToClient) {
                emit(STREAM_ASSESSMENT_PREFIX)
            }

            val assessmentResult = runSdkAndCollectWithBudget(
                model = model,
                input = assessmentPrompt,
                timeoutMs = ASSESSMENT_TIMEOUT_MS,
                maxChars = ASSESSMENT_MAX_CHARS,
                onDelta = if (streamAssessmentToClient) {
                    { chunk -> if (chunk.isNotEmpty()) emit(chunk) }
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
            val assessmentParsed = parseStep1EvalFlexible(
                raw = assessmentJsonRaw ?: assessmentResult.text,
                fallbackReason = fallbackStep1Reason(
                    collectedReason = assessmentResult.reason,
                    errorToken = assessmentResult.errorToken,
                ),
            )

            val accepted = assessmentParsed.score >= acceptScoreThreshold
            val finalAssessment = assessmentParsed.copy(
                status = if (accepted) ChatModels.ValidationStatus.ACCEPTED else ChatModels.ValidationStatus.NEED_FOLLOW_UP,
            )

            AppLog.d(
                TAG,
                "assessment done: trace=$traceId qid=$questionId score=${finalAssessment.score} " +
                        "status=${finalAssessment.status} reason=${assessmentResult.reason} earlyStop=${assessmentResult.isEarlyStop}",
            )

            if (streamAssessmentToClient) {
                emit("\n$STREAM_ASSESSMENT_RESULT_PREFIX status=${finalAssessment.status.name} score=${finalAssessment.score}\n")
            }

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

            val canonicalEvalJson = buildCanonicalStep1EvalJson(finalAssessment)

            val followUpBody = promptBuilder.buildFollowUpPrompt(
                questionId = questionId,
                userPrompt = u,
                evalJson = canonicalEvalJson,
                acceptScoreThreshold = acceptScoreThreshold,
                userPromptMaxChars = FOLLOWUP_USER_PROMPT_MAX_CHARS,
                evalJsonMaxChars = FOLLOWUP_ASSESSMENT_JSON_MAX_CHARS,
            )

            val followUpPrompt = composeSdkInput(
                systemPrompt = composeStructuredSystemPrompt(
                    baseSystemPrompt = resolveBaseSystemPromptForPhase(cfg = cfg, phaseName = "VALIDATE_FOLLOW_UP"),
                    nodeTaskPrompt = promptContext.followUpTaskPrompt,
                ),
                promptBody = followUpBody,
            )

            dbgLogPrompt(traceId = traceId, step = "FOLLOWUP PHASE", questionId = questionId, prompt = followUpPrompt)

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
                                if (chunk.length <= remaining) s2Buffer.append(chunk) else s2Buffer.append(chunk.take(remaining))
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
                    if (msg.equals("Cancelled", ignoreCase = true)) closeOk() else closeErr(IllegalStateException(msg))
                },
            )
        }
    }

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
                onDelta = { chunk -> if (chunk.isNotEmpty()) emit(chunk) },
                onTerminal = { closeOk() },
                onError = { msg ->
                    if (msg.equals("Cancelled", ignoreCase = true)) closeOk() else closeErr(IllegalStateException(msg))
                },
            )
        }
    }

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
                launch { runCatching { send(chunk) } }
            }

            val job = launch(Dispatchers.Default) {
                var acquired = false
                try {
                    gate.acquire()
                    acquired = true
                    permitAcquired.complete(Unit)
                    block({ emitSafely(it) }, { closeOnce(null) }, { closeOnce(it) })
                } catch (_: CancellationException) {
                    closeOnce(null)
                } catch (t: Throwable) {
                    closeOnce(t)
                } finally {
                    if (acquired) runCatching { gate.release() }
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

    private fun resolveNodePromptContext(
        cfg: SurveyConfig?,
        questionId: String,
    ): NodePromptContext {
        val qid = questionId.trim()
        val node = cfg?.graph?.nodes?.firstOrNull { it.id.trim() == qid }

        val questionText = node?.question?.trim().orEmpty()
            .ifBlank { node?.title?.trim().orEmpty() }
            .ifBlank { qid }

        val options = node?.options?.mapNotNull { it.trim().takeIf { v -> v.isNotBlank() } }.orEmpty()

        val oneStepPrompt = cfg?.resolveOneStepPrompt(qid)?.trim()?.takeIf { it.isNotBlank() }

        val evalTaskPrompt = cfg?.resolveEvalPrompt(qid)?.trim()?.takeIf { it.isNotBlank() } ?: oneStepPrompt
        val followUpTaskPrompt = cfg?.resolveFollowupPrompt(qid)?.trim()?.takeIf { it.isNotBlank() } ?: oneStepPrompt

        return NodePromptContext(
            questionId = qid,
            nodeType = node?.nodeType() ?: NodeType.UNKNOWN,
            questionText = questionText,
            options = options,
            evalTaskPrompt = evalTaskPrompt,
            followUpTaskPrompt = followUpTaskPrompt,
        )
    }

    private fun resolveBaseSystemPromptForPhase(
        cfg: SurveyConfig?,
        phase: ChatValidation.PromptPhase,
    ): String? = resolveBaseSystemPromptForPhase(cfg = cfg, phaseName = phase.name)

    private fun resolveBaseSystemPromptForPhase(
        cfg: SurveyConfig?,
        phaseName: String,
    ): String? {
        val raw = when (phaseName) {
            "VALIDATE_MAIN" -> runCatching { cfg?.composeSystemPromptEval() }.getOrNull()
            "VALIDATE_FOLLOW_UP" -> runCatching { cfg?.composeSystemPromptFollowup() }.getOrNull()
            else -> runCatching { cfg?.composeSystemPrompt() }.getOrNull()
        }?.trim()?.takeIf { it.isNotBlank() }

        return sanitizeBaseSystemPromptForPhase(systemPrompt = raw, phaseName = phaseName)
    }

    private fun sanitizeBaseSystemPromptForPhase(
        systemPrompt: String?,
        phaseName: String,
    ): String? {
        val raw = systemPrompt?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (phaseName != "VALIDATE_MAIN") return raw

        val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split('\n')
        val out = ArrayList<String>(lines.size)
        var skipNextJsonLine = false

        for (line in lines) {
            val trimmed = line.trim()

            if (skipNextJsonLine) {
                skipNextJsonLine = false
                if (trimmed.contains("followUpQuestion") || trimmed.contains("followup_question")) continue
            }

            if (trimmed.equals("If no follow-up is needed, return:", ignoreCase = true)) {
                skipNextJsonLine = true
                continue
            }

            if (trimmed.contains("followUpQuestion") || trimmed.contains("followup_question")) continue
            out += line
        }

        return out.joinToString("\n").trim().takeIf { it.isNotBlank() }
    }

    private fun composeStructuredSystemPrompt(
        baseSystemPrompt: String?,
        nodeTaskPrompt: String?,
    ): String? {
        val parts = buildList {
            baseSystemPrompt?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            nodeTaskPrompt?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n")
    }

    private fun composeSdkInput(
        systemPrompt: String?,
        promptBody: String,
    ): String {
        val body = promptBody.trim()
        if (body.isBlank()) return ""
        val sys = systemPrompt.orEmpty().trim()
        if (sys.isBlank()) return body
        return buildString(sys.length + body.length + 64) {
            append("SYSTEM_PROMPT_BEGIN\n")
            append(sys)
            append('\n')
            append("SYSTEM_PROMPT_END\n\n")
            append(body)
        }
    }

    private fun buildValidationDataPayload(
        payloadKind: ValidationPayloadKind,
        promptContext: NodePromptContext,
        mainAnswer: String,
        followUpAnswerPayload: String?,
    ): String {
        val qid = promptContext.questionId
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
                payloadKind = payloadKind,
                latestQuestionLength = followUpQuestionText.length,
                latestAnswerLength = followUpAnswerText.length,
                latestAnswerSha8 = sha8,
                treatAsHistory = treatAsHistory,
            )
        }

        val clippedQuestion = promptContext.questionText.safeTrimAndClip(MAX_QUESTION_TEXT_CHARS)
        val clippedMain = main.safeTrimAndClip(MAX_MAIN_ANSWER_CHARS)
        val clippedOptions = promptContext.options.take(MAX_OPTION_COUNT)
            .map { it.safeTrimAndClip(MAX_OPTION_CHARS) }
            .filter { it.isNotBlank() }

        val followUpSection = if (!hasFollowUp) {
            ""
        } else {
            val clippedA = followUpAnswerText.safeTrimAndClip(MAX_FOLLOW_UP_ANSWER_CHARS)
            val clippedQ = followUpQuestionText.safeTrimAndClip(MAX_FOLLOW_UP_QUESTION_CHARS)
            val clippedHistory = if (treatAsHistory) fuRaw.safeTrimAndClip(MAX_FOLLOW_UP_HISTORY_CHARS) else ""
            buildString {
                appendLine("FOLLOW_UP_ANSWER_BEGIN")
                if (clippedA.isNotBlank()) appendLine(clippedA)
                appendLine("FOLLOW_UP_ANSWER_END")
                appendLine()
                appendLine("FOLLOW_UP_QUESTION_BEGIN")
                if (clippedQ.isNotBlank()) appendLine(clippedQ)
                appendLine("FOLLOW_UP_QUESTION_END")
                appendLine()
                if (treatAsHistory) {
                    appendLine("FOLLOW_UP_HISTORY_BEGIN")
                    if (clippedHistory.isNotBlank()) appendLine(clippedHistory)
                    appendLine("FOLLOW_UP_HISTORY_END")
                    appendLine()
                }
            }
        }

        return buildString(2_048) {
            appendLine("SURVEY_NODE_ID: $qid")
            appendLine("NODE_TYPE: ${promptContext.nodeType.name}")
            appendLine()
            appendLine("QUESTION_TEXT_BEGIN")
            appendLine(clippedQuestion)
            appendLine("QUESTION_TEXT_END")
            appendLine()

            if (clippedOptions.isNotEmpty()) {
                appendLine("QUESTION_OPTIONS_BEGIN")
                clippedOptions.forEachIndexed { index, option ->
                    append(index + 1)
                    append(". ")
                    appendLine(option)
                }
                appendLine("QUESTION_OPTIONS_END")
                appendLine()
            }

            appendLine("MAIN_ANSWER_BEGIN")
            appendLine(clippedMain)
            appendLine("MAIN_ANSWER_END")
            appendLine()
            append(followUpSection)
        }.trimEnd()
    }

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
                    if (chunk.isNotEmpty() && sessionId != null) streamBridge.emitChunk(sessionId, chunk)
                },
            )

            if (sessionId != null) {
                when {
                    result.reason.startsWith("COMPLETED") -> streamBridge.end(sessionId)
                    result.reason.startsWith("CANCELLED") -> streamBridge.error(sessionId, ChatStreamEvent.Codes.CANCELLED, ChatStreamEvent.Codes.CANCELLED)
                    result.reason.startsWith("TIMEOUT") -> streamBridge.error(sessionId, ChatStreamEvent.Codes.TIMEOUT, ChatStreamEvent.Codes.TIMEOUT)
                    result.reason.startsWith("MAX_CHARS") -> streamBridge.error(sessionId, "max_chars", ChatStreamEvent.Codes.ERROR)
                    else -> streamBridge.error(sessionId, result.errorToken ?: ChatStreamEvent.Codes.ERROR, ChatStreamEvent.Codes.ERROR)
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

    private fun parseStep1EvalFlexible(
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
            val explicitStatus = parseStatusToken(obj.optString("status", ""))
            val accepted = optBooleanMaybe(obj, "accepted")
            val followupNeeded = optBooleanMaybe(obj, "followup_needed")
                ?: optBooleanMaybe(obj, "followUpNeeded")

            val status = explicitStatus ?: when {
                followupNeeded != null -> if (followupNeeded) ChatModels.ValidationStatus.NEED_FOLLOW_UP else ChatModels.ValidationStatus.ACCEPTED
                accepted != null -> if (accepted) ChatModels.ValidationStatus.ACCEPTED else ChatModels.ValidationStatus.NEED_FOLLOW_UP
                else -> ChatModels.ValidationStatus.NEED_FOLLOW_UP
            }

            val explicitScore = optIntMaybe(obj, "score")?.coerceIn(0, 100)
            val score = explicitScore ?: when (status) {
                ChatModels.ValidationStatus.ACCEPTED -> 100
                ChatModels.ValidationStatus.NEED_FOLLOW_UP -> 0
            }

            val reason = obj.optString("reason", "").trim().ifBlank { fallbackReason }
            val missing = buildList {
                val arr = obj.optJSONArray("missing") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "").trim()
                    if (item.isNotBlank()) add(item)
                }
            }

            ChatValidation.Step1EvalResult(status = status, score = score, reason = reason, missing = missing)
        }.getOrElse {
            ChatValidation.Step1EvalResult(
                status = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                score = 0,
                reason = fallbackReason,
                missing = emptyList(),
            )
        }
    }

    private fun parseStep2FollowUpFlexible(
        raw: String,
        fallbackFollowUp: String,
    ): ChatValidation.Step2FollowUpResult {
        val json = extractFirstCompleteJsonObjectBestEffort(raw)
        if (json == null) {
            val plainTextFollowUp = normalizeFollowUpQuestion(raw)
            return ChatValidation.Step2FollowUpResult(
                status = ChatModels.ValidationStatus.NEED_FOLLOW_UP,
                assistantMessage = "",
                followUpQuestion = plainTextFollowUp ?: fallbackFollowUp,
            )
        }

        return runCatching {
            val obj = JSONObject(json)
            val assistantMessage = obj.optString("assistantMessage", "")
                .ifBlank { obj.optString("assistant_message", "") }
                .replace("\u0000", "")
                .trim()
                .ifBlank { "" }

            val followUpQuestion = normalizeFollowUpQuestion(obj.optString("followUpQuestion", ""))
                ?: normalizeFollowUpQuestion(obj.optString("followup_question", ""))
                ?: fallbackFollowUp

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

    private fun buildCanonicalStep1EvalJson(
        step1: ChatValidation.Step1EvalResult,
    ): String {
        val obj = JSONObject()
        obj.put("status", step1.status.name)
        obj.put("score", step1.score.coerceIn(0, 100))
        obj.put("reason", step1.reason.safeTrimAndClip(MAX_STEP1_REASON_CHARS))

        if (step1.missing.isNotEmpty()) {
            val arr = JSONArray()
            step1.missing.forEach { item ->
                val t = item.safeTrimAndClip(MAX_STEP1_MISSING_ITEM_CHARS)
                if (t.isNotBlank()) arr.put(t)
            }
            if (arr.length() > 0) obj.put("missing", arr)
        }

        return obj.toString()
    }

    private fun buildCanonicalStep2FollowUpJson(
        step2: ChatValidation.Step2FollowUpResult,
    ): String {
        val obj = JSONObject()
        obj.put("status", step2.status.name)
        obj.put("assistantMessage", step2.assistantMessage?.safeTrimAndClip(MAX_STEP2_ASSISTANT_MESSAGE_CHARS).orEmpty())
        obj.put("followUpQuestion", step2.followUpQuestion?.safeTrimAndClip(MAX_FOLLOW_UP_QUESTION_CHARS).orEmpty())
        return obj.toString()
    }

    private fun parseStatusToken(rawStatus: String): ChatModels.ValidationStatus? =
        when (rawStatus.trim()) {
            "ACCEPTED" -> ChatModels.ValidationStatus.ACCEPTED
            "NEED_FOLLOW_UP" -> ChatModels.ValidationStatus.NEED_FOLLOW_UP
            else -> null
        }

    private fun optBooleanMaybe(obj: JSONObject, key: String): Boolean? {
        if (!obj.has(key)) return null
        return when (val v = obj.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.trim().lowercase(Locale.US)) {
                "true", "1", "yes", "y" -> true
                "false", "0", "no", "n" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun optIntMaybe(obj: JSONObject, key: String): Int? {
        if (!obj.has(key)) return null
        return when (val v = obj.opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    private fun fallbackStep1Reason(
        collectedReason: String,
        errorToken: String?,
    ): String = when {
        collectedReason.startsWith("TIMEOUT") -> "Validation timed out before a reliable score was produced."
        collectedReason.startsWith("MAX_CHARS") -> "Validation output was clipped before a reliable score was produced."
        collectedReason.startsWith("ERROR") -> when (errorToken) {
            "ModelNotReadyException" -> "Model is still warming up."
            else -> "Validation failed before a reliable score was produced."
        }
        collectedReason.startsWith("CANCELLED") -> "Validation was cancelled."
        else -> "Validation result could not be parsed."
    }

    private fun normalizeQuestionForComparison(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val cleaned = buildString(text.length) {
            text.forEach { ch ->
                when {
                    ch.isLetterOrDigit() || ch.isWhitespace() -> append(ch.lowercaseChar())
                    else -> append(' ')
                }
            }
        }
        return cleaned.trim().replace(Regex("\\s+"), " ")
    }

    private fun isSameFollowUpQuestion(a: String?, b: String?): Boolean {
        val na = normalizeQuestionForComparison(a)
        val nb = normalizeQuestionForComparison(b)
        if (na.isBlank() || nb.isBlank()) return false
        return na == nb
    }

    private fun countFollowUpTurns(payload: String?): Int {
        val raw = payload?.trim().orEmpty()
        if (raw.isBlank()) return 0
        return TURN_Q_RE.findAll(raw).count().coerceAtLeast(0)
    }

    private fun fallbackFollowUpCandidates(questionId: String): List<String> = when (questionId.trim()) {
        "ai_input_use" -> listOf(
            "Which input caused the biggest problem: fertilizer, seed, pesticide, or something else?",
            "What exactly was the problem with that input?",
            "How did that input problem affect your farming this season?",
        )
        "ai_yield_risk" -> listOf(
            "What specific risk was biggest for your maize this season?",
            "When does that risk usually happen?",
            "How does that risk affect your maize crop?",
        )
        "ai_support_needed" -> listOf(
            "What specific support would help you most next season?",
            "Who should provide that support?",
            "How would that support improve maize production?",
        )
        else -> listOf(
            "Could you give one specific detail?",
            "What exactly do you mean?",
            "How did it affect your situation?",
        )
    }

    private fun selectDistinctFallbackFollowUp(
        questionId: String,
        previousQuestion: String?,
        modelQuestion: String?,
        defaultFallback: String,
        followUpTurnCount: Int,
    ): String {
        val blocked = setOf(
            normalizeQuestionForComparison(previousQuestion),
            normalizeQuestionForComparison(modelQuestion),
            normalizeQuestionForComparison(defaultFallback),
        ).filter { it.isNotBlank() }.toSet()

        val candidates = buildList {
            addAll(fallbackFollowUpCandidates(questionId))
            if (defaultFallback.isNotBlank()) add(defaultFallback)
        }

        if (candidates.isEmpty()) return "Could you provide one specific detail?"

        val startIndex = followUpTurnCount.coerceAtLeast(0) % candidates.size
        for (offset in candidates.indices) {
            val idx = (startIndex + offset) % candidates.size
            val candidate = candidates[idx].trim()
            if (candidate.isBlank()) continue
            val norm = normalizeQuestionForComparison(candidate)
            if (norm !in blocked) return candidate
        }
        return defaultFallback.trim().ifBlank { "Could you provide one specific detail?" }
    }

    private fun resolveFinalFollowUpQuestion(
        questionId: String,
        parsedQuestion: String?,
        previousQuestion: String?,
        defaultFallback: String,
        followUpAnswerPayload: String?,
    ): String {
        val parsed = parsedQuestion?.trim().orEmpty()
        val previous = previousQuestion?.trim().orEmpty()
        val turnCount = countFollowUpTurns(followUpAnswerPayload)

        return when {
            parsed.isBlank() -> selectDistinctFallbackFollowUp(questionId, previous, null, defaultFallback, turnCount)
            isSameFollowUpQuestion(parsed, previous) -> selectDistinctFallbackFollowUp(questionId, previous, parsed, defaultFallback, turnCount)
            else -> parsed
        }
    }

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
            if (ended.compareAndSet(false, true)) onTerminal(reason)
        }

        fun errorOnce(msg: String) {
            if (ended.compareAndSet(false, true)) onError(msg)
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
            return CollectedResult(text = "", reason = "EMPTY_INPUT", errorToken = null, isEarlyStop = false)
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

                if (appended.isNotEmpty()) runCatching { onDelta?.invoke(appended) }
            },
            onTerminal = { msg ->
                val current = reasonRef.get()
                if (current == "MAX_CHARS") finishOnce(reason = "MAX_CHARS; $msg", earlyStop = true)
                else finishOnce(reason = "COMPLETED; $msg", earlyStop = false)
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

        val completed = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { done.await(); true } == true
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
                    if (depth == 0) return s.substring(start, i + 1).trim()
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
        val garbage = setOf("none", "(none)", "n/a", "na", "null", "nil", "no", "nope", "no follow up", "no follow-up", "skip", "0", "-")
        if (l2 in garbage) return null
        if (t.length < 3) return null
        return t
    }

    private fun looksLikeFollowUpHistory(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        return raw.lineSequence().any { line -> FOLLOW_UP_HISTORY_LINE_RE.matches(line.trim()) }
    }

    private fun extractLatestFollowUpTurn(payload: String): FollowUpTurnExtract? {
        val normalized = payload.replace("\u0000", "").replace("\r\n", "\n").replace("\r", "\n")
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
                buf.append('\n').append(lines[j]); j++
            }
            return buf.toString() to j
        }

        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            CURRENT_Q_RE.matchEntire(t)?.let { m ->
                val (body, nextIdx) = readContinuation(i, m.groupValues[1]); currentQ = body; i = nextIdx; continue
            }
            CURRENT_A_RE.matchEntire(t)?.let { m ->
                val (body, nextIdx) = readContinuation(i, m.groupValues[1]); currentA = body; i = nextIdx; continue
            }
            val qm = TURN_Q_RE.matchEntire(t)
            if (qm != null) {
                val idx = qm.groupValues[1].toIntOrNull() ?: -1
                val (body, nextIdx) = readContinuation(i, qm.groupValues[2])
                if (idx >= latestIdx) { latestIdx = idx; latestQ = body }
                i = nextIdx; continue
            }
            val am = TURN_A_RE.matchEntire(t)
            if (am != null) {
                val idx = am.groupValues[1].toIntOrNull() ?: -1
                val (body, nextIdx) = readContinuation(i, am.groupValues[2])
                if (idx >= latestIdx) { latestIdx = idx; latestA = body }
                i = nextIdx; continue
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

    private fun dbgLogPrompt(traceId: String, step: String, questionId: String, prompt: String) {
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

    private fun dbgLogResult(traceId: String, step: String, questionId: String, reason: String, isEarlyStop: Boolean, text: String) {
        if (!dbg.enabled) return
        val len = text.length
        val sha = sha256Hex(text).take(8)
        AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId RESULT meta len=$len sha8=$sha reason=$reason earlyStop=$isEarlyStop")
        if (dbg.logFullText) {
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId RESULT full:\n$text")
            return
        }
        if (dbg.logClippedText && dbg.maxResultLogChars > 0) {
            val clip = clipForLog(text, dbg.maxResultLogChars)
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId RESULT clip(${clip.length}/$len):\n$clip")
        }
    }

    private fun dbgLogStructuredStep1(questionId: String, rawEvalJson: String, parsed: ChatValidation.Step1EvalResult) {
        if (!dbg.enabled) return
        val sha = sha256Hex(rawEvalJson).take(8)
        AppLog.d(TAG, "[STEP1] qid=$questionId status=${parsed.status.name} score=${parsed.score} reasonLen=${parsed.reason.length} rawLen=${rawEvalJson.length} rawSha8=$sha missing=${parsed.missing.size}")
        if (dbg.logFullText) {
            AppLog.d(TAG, "[STEP1] qid=$questionId raw(full):\n$rawEvalJson")
        } else if (dbg.logClippedText && dbg.maxResultLogChars > 0) {
            AppLog.d(TAG, "[STEP1] qid=$questionId raw(clip):\n${clipForLog(rawEvalJson, dbg.maxResultLogChars)}")
        }
    }

    private fun dbgLogStructuredStep2(questionId: String, rawFollowUpJson: String, parsed: ChatValidation.Step2FollowUpResult) {
        if (!dbg.enabled) return
        val sha = sha256Hex(rawFollowUpJson).take(8)
        AppLog.d(TAG, "[STEP2] qid=$questionId status=${parsed.status.name} aLen=${parsed.assistantMessage?.length ?: 0} qLen=${parsed.followUpQuestion?.length ?: 0} rawLen=${rawFollowUpJson.length} rawSha8=$sha")
        if (dbg.logFullText) {
            AppLog.d(TAG, "[STEP2] qid=$questionId raw(full):\n$rawFollowUpJson")
        } else if (dbg.logClippedText && dbg.maxResultLogChars > 0) {
            AppLog.d(TAG, "[STEP2] qid=$questionId raw(clip):\n${clipForLog(rawFollowUpJson, dbg.maxResultLogChars)}")
        }
    }

    private fun dbgLogCompatFollowUpPayload(payloadKind: ValidationPayloadKind, latestQuestionLength: Int, latestAnswerLength: Int, latestAnswerSha8: String, treatAsHistory: Boolean) {
        if (!dbg.enabled) return
        AppLog.d(TAG, "followUpDetected: payloadKind=${payloadKind.name} treatAsHistory=$treatAsHistory latestQlen=$latestQuestionLength latestAlen=$latestAnswerLength latestAsha8=$latestAnswerSha8")
    }

    private fun dbgLogNodePromptContext(context: NodePromptContext) {
        if (!dbg.enabled) return
        AppLog.d(TAG, "nodePromptContext: qid=${context.questionId} nodeType=${context.nodeType.name} questionLen=${context.questionText.length} options=${context.options.size} evalTaskLen=${context.evalTaskPrompt?.length ?: 0} followTaskLen=${context.followUpTaskPrompt?.length ?: 0}")
    }

    private fun dbgLogFollowUpResolution(questionId: String, previousQuestion: String?, modelQuestion: String?, finalQuestion: String?) {
        if (!dbg.enabled) return
        val previous = previousQuestion.orEmpty()
        val model = modelQuestion.orEmpty()
        val final = finalQuestion.orEmpty()
        AppLog.d(
            TAG,
            "followUpResolution: qid=$questionId prevLen=${previous.length} modelLen=${model.length} finalLen=${final.length} prevSha8=${sha256Hex(previous).take(8)} modelSha8=${sha256Hex(model).take(8)} finalSha8=${sha256Hex(final).take(8)} repeated=${isSameFollowUpQuestion(previous, model)} replaced=${normalizeQuestionForComparison(model) != normalizeQuestionForComparison(final)}",
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

    private fun getSystemPromptFromCacheOnly(): String? {
        val cfg = InstalledSurveyConfigStore.getOrNull() ?: cachedConfig.get()
        return runCatching { cfg?.composeSystemPrompt() }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun getConfigSuspendBestEffort(): SurveyConfig? {
        InstalledSurveyConfigStore.getOrNull()?.let { cfg -> cachedConfig.set(cfg); return cfg }
        cachedConfig.get()?.let { return it }

        if (!allowAssetConfigFallback) {
            SafeLog.w(TAG, "Config missing: installed=null; assetFallback=false")
            return null
        }

        return configLoadMutex.withLock {
            InstalledSurveyConfigStore.getOrNull()?.let { cfg -> cachedConfig.set(cfg); return@withLock cfg }
            cachedConfig.get()?.let { return@withLock it }

            val loaded = withContext(Dispatchers.IO) {
                runCatching { SurveyConfigLoader.fromAssetsValidated(appContext, configAssetName) }
                    .onFailure { t -> SafeLog.e(TAG, "Config fallback load failed type=${t::class.java.simpleName}", t) }
                    .getOrNull()
            }

            if (loaded != null) {
                SafeLog.w(TAG, "Config loaded from assets as fallback (NOT installed): asset=$configAssetName")
                cachedConfig.set(loaded)
            }
            loaded
        }
    }

    private fun resolveModelFile(cfg: SurveyConfig?): File {
        val rawFileName = cfg?.modelDefaults?.defaultFileName?.trim()?.takeIf { it.isNotBlank() } ?: fallbackModelFileName
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
            FileStat(exists = file.exists(), isFile = file.isFile, length = file.length())
        }.getOrElse {
            FileStat(exists = false, isFile = false, length = 0L)
        }
    }

    private fun getOrCreateModel(cfg: SurveyConfig?, file: File): Model {
        val modelName = cfg?.modelDefaults?.modelName?.trim()?.takeIf { it.isNotBlank() } ?: fallbackModelName
        val key = "$modelName::${file.absolutePath}"
        val holder = modelCache.getOrPut(key) {
            val modelConfig: Map<Model.ConfigKey, Any> = Model.buildModelConfigSafe(cfg?.slm)
            val fileLen = runCatching { file.length() }.getOrDefault(-1L)
            AppLog.d(TAG, "ModelCache create: modelName=$modelName cfgPresent=${cfg != null} fileLen=$fileLen")
            ModelHolder(model = Model(name = modelName, taskPath = file.absolutePath, config = modelConfig))
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
            fun defaultForRepo(): InitOptions = InitOptions(false, false, null, emptyList())
            fun fromWarmupOptions(options: WarmupController.Options): InitOptions {
                val sys = options.systemMessage as? Message
                return InitOptions(options.supportImage, options.supportAudio, sys, options.tools)
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
            initializeSdkIfNeeded(model = model, initOptions = initOptions)
            return
        }

        val desiredSig = initOptions.signature()
        val existingSig = holder.initSignatureRef.get()
        if (holder.initialized.get() && existingSig == desiredSig) return

        holder.initMutex.withLock {
            val sigNow = holder.initSignatureRef.get()
            if (holder.initialized.get() && sigNow == desiredSig) return@withLock

            initializeSdkIfNeeded(model = model, initOptions = initOptions)
            holder.initialized.set(true)
            holder.initSignatureRef.set(desiredSig)
        }
    }

    private suspend fun initializeSdkIfNeeded(model: Model, initOptions: InitOptions) {
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
            AppLog.w(TAG, "resetConversationAndAwait failed or timed out; continuing: reason='$reason' model='${model.name}'")
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

    private fun String.safeTrimAndClip(limit: Int): String {
        val t = trim()
        val n = limit.coerceAtLeast(0)
        if (n == 0) return ""
        if (t.length <= n) return t
        var end = n
        if (end > 0 && end < t.length) {
            val last = t[end - 1]
            val next = t[end]
            if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) end -= 1
        }
        if (end <= 0) return ""
        return t.substring(0, end)
    }

    companion object {
        private const val TAG: String = "SlmRepository"
        private const val RESET_CONVERSATION_TIMEOUT_MS: Long = 15_000L
        private const val EARLY_STOP_STABILIZE_DELAY_MS: Long = 250L
        private const val ASSESSMENT_TIMEOUT_MS: Long = 60_000L
        private const val ASSESSMENT_MAX_CHARS: Int = 8_192
        private const val ASSESSMENT_USER_PROMPT_MAX_CHARS: Int = 4_000
        private const val FOLLOWUP_TIMEOUT_MS: Long = 60_000L
        private const val FOLLOWUP_MAX_CHARS: Int = 8_192
        private const val FOLLOWUP_USER_PROMPT_MAX_CHARS: Int = 4_000
        private const val FOLLOWUP_ASSESSMENT_JSON_MAX_CHARS: Int = 2_000
        private const val MAX_QUESTION_TEXT_CHARS = 2_000
        private const val MAX_MAIN_ANSWER_CHARS = 8_000
        private const val MAX_OPTION_COUNT = 32
        private const val MAX_OPTION_CHARS = 512
        private const val MAX_STEP1_REASON_CHARS = 4_000
        private const val MAX_STEP1_MISSING_ITEM_CHARS = 512
        private const val MAX_STEP2_ASSISTANT_MESSAGE_CHARS = 4_000
        private const val MAX_FOLLOW_UP_ANSWER_CHARS = 8_000
        private const val MAX_FOLLOW_UP_QUESTION_CHARS = 2_000
        private const val MAX_FOLLOW_UP_HISTORY_CHARS = 16_000
        private const val STREAM_BUFFER_CAPACITY: Int = 64
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
