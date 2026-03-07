/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Fake SLM Repository)
 *  ---------------------------------------------------------------------
 *  File: FakeSlmRepository.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A fake "SLM" repository used for deterministic integration testing.
 *
 * Supported modes:
 * - One-step generation:
 *   - Streams answer-like plain text.
 * - Legacy prompt/request mode:
 *   - Streams JSON directly when PHASE markers are embedded in the prompt.
 * - Structured two-step validation:
 *   - Step 1: Assessment JSON
 *   - Step 2: Follow-up JSON (only when needed)
 *
 * Design goals:
 * - Behave like a streaming LLM backend.
 * - Keep outputs deterministic from prompt fingerprints.
 * - Mimic the real repository contract closely enough for UI / ViewModel tests.
 *
 * Important:
 * - Final ACCEPTED vs NEED_FOLLOW_UP is decided from Step-1 score in code.
 * - Step-2 must not override the Step-1 decision.
 *
 * Privacy:
 * - Do not log raw prompts or answers through [logger].
 * - Keep logger metadata-only.
 */
class FakeSlmRepository(
    config: Config = Config(),
    /** Shared prompt builder to keep prompt contract aligned with the real repository. */
    private val promptBuilder: SlmPromptBuilderI = DefaultSlmPromptBuilder,
) : ChatValidation.RepositoryI {

    data class Config(
        /** Delay before starting a request. */
        val requestSetupDelayMs: Long = 0L,

        /** Delay before emitting the first chunk. */
        val firstChunkDelayMs: Long = 0L,

        /** Delay between chunks. */
        val chunkDelayMs: Long = 0L,

        /** Chunk size used for fake streaming. */
        val chunkSizeChars: Int = 32,

        /** Throw during request setup before a Flow is returned. */
        val throwOnRequestSetup: Boolean = false,

        /**
         * Throw after this many emitted chunks for public streaming.
         *
         * Notes:
         * - 0 means fail before the first emit.
         * - -1 means no failure.
         */
        val throwAfterEmits: Int = -1,

        /** Inject failure into Step-1 internal simulation. */
        val throwOnEvalInternal: Boolean = false,

        /** Throw after this many Step-1 chunks internally. */
        val throwAfterEmitsOnEvalInternal: Int = -1,

        /**
         * If true, sometimes prepend non-JSON text before JSON.
         *
         * Useful to test extraction robustness.
         */
        val occasionallyPrependNonJson: Boolean = false,

        /** Add a trailing newline after JSON/plain text. */
        val appendTrailingNewline: Boolean = true,

        /** Enable repository-level two-step behavior. */
        val enableTwoStepEval: Boolean = true,

        /** Reset conversation per request (metadata only in fake). */
        val resetConversationEachRequest: Boolean = true,

        /** Step-1 timeout budget used in fake internal collection. */
        val evalTimeoutMs: Long = 8_000L,

        /** Step-1 max chars used in fake internal collection. */
        val evalMaxChars: Int = 8_192,

        /** Step-2 max streamed chars. */
        val followUpMaxChars: Int = 4_096,

        /** Score threshold: score >= threshold => ACCEPTED. */
        val acceptScoreThreshold: Int = 70,

        /** Optional metadata logger. */
        val logger: ((String) -> Unit)? = null,
    ) {
        fun normalized(): Config {
            return copy(
                requestSetupDelayMs = requestSetupDelayMs.coerceAtLeast(0L),
                firstChunkDelayMs = firstChunkDelayMs.coerceAtLeast(0L),
                chunkDelayMs = chunkDelayMs.coerceAtLeast(0L),
                chunkSizeChars = chunkSizeChars.coerceAtLeast(1),
                evalTimeoutMs = evalTimeoutMs.coerceAtLeast(1L),
                evalMaxChars = evalMaxChars.coerceAtLeast(128),
                followUpMaxChars = followUpMaxChars.coerceAtLeast(128),
                acceptScoreThreshold = acceptScoreThreshold.coerceIn(0, 100),
            )
        }
    }

    private val cfg: Config = config.normalized()

    /**
     * Compatibility helper for older direct callers.
     *
     * Notes:
     * - This is intentionally not marked override.
     * - New code should use the phase-aware overload.
     */
    fun buildPrompt(userPrompt: String): String {
        return promptBuilder.buildAnswerLikePrompt(
            systemPrompt = null,
            userPrompt = userPrompt,
        )
    }

    override fun buildPrompt(
        userPrompt: String,
        phase: ChatValidation.PromptPhase,
    ): String {
        return buildValidatorStylePrompt(
            userPrompt = userPrompt,
            phase = phase,
        )
    }

    override suspend fun request(prompt: String): Flow<String> {
        val raw = prompt.trim()
        if (raw.isBlank()) return emptyFlow()

        cfg.logger?.invoke(
            "FakeSlmRepository.request: setup chars=${raw.length} " +
                    "setupDelayMs=${cfg.requestSetupDelayMs} firstChunkDelayMs=${cfg.firstChunkDelayMs} " +
                    "twoStep=${cfg.enableTwoStepEval}",
        )

        maybeThrowOnSetup()
        maybeDelaySetup()

        val explicitPhase = detectPhaseFromPrompt(raw)
        if (explicitPhase != null) {
            val json = buildLegacyValidatorJsonFromPrompt(
                prompt = raw,
                phase = explicitPhase,
            )
            return streamTextAsCallbackFlow(
                seed = raw,
                fullText = jsonWithOptionalNoise(seed = raw, body = json),
                throwAfterEmits = cfg.throwAfterEmits,
                firstChunkDelayMs = cfg.firstChunkDelayMs,
                chunkDelayMs = cfg.chunkDelayMs,
                chunkSizeChars = cfg.chunkSizeChars,
            )
        }

        val userPrompt = extractUserPromptBestEffort(raw)
        return if (cfg.enableTwoStepEval) {
            requestTwoStepEvalThenFollowUp(
                userPrompt = userPrompt,
                rawSeed = raw,
            )
        } else {
            requestOneStepLikeReal(
                userPrompt = userPrompt,
                rawSeed = raw,
            )
        }
    }

    /**
     * Structured two-step assessment used by the real validator path.
     *
     * Behavior:
     * - Step 1 is streamed through [streamBridge] with phase STEP1_EVAL.
     * - Final decision is derived from Step-1 score.
     * - Step 2 is streamed only when a follow-up is needed.
     */
    override suspend fun runTwoStepAssessment(
        request: ChatValidation.TwoStepAssessmentRequest,
        streamBridge: ChatStreamBridge?,
    ): ChatValidation.TwoStepAssessmentResult {
        maybeThrowOnSetup()
        maybeDelaySetup()

        val questionId = request.questionId.trim().ifBlank {
            "AUTO-${UUID.randomUUID().toString().take(8)}"
        }

        val phase = if (request.followUpAnswerPayload.isNullOrBlank()) {
            ChatValidation.PromptPhase.VALIDATE_MAIN
        } else {
            ChatValidation.PromptPhase.VALIDATE_FOLLOW_UP
        }

        val validationUserPrompt = buildValidationUserPrompt(
            phase = phase,
            questionId = questionId,
            mainAnswer = request.mainAnswer,
            followUpAnswerPayload = request.followUpAnswerPayload,
        )

        if (cfg.resetConversationEachRequest) {
            cfg.logger?.invoke("FakeSlmRepository: resetConversation (step1) metaOnly=true")
        }

        val step1Prompt = promptBuilder.buildEvalScorePrompt(
            questionId = questionId,
            userPrompt = validationUserPrompt,
            acceptScoreThreshold = cfg.acceptScoreThreshold,
            userPromptMaxChars = EVAL_USER_PROMPT_MAX_CHARS,
        )

        val step1IdealJson = generateEvalScoreJson(
            seed = "${questionId}#STEP1#${promptFingerprint(validationUserPrompt)}",
            questionId = questionId,
            userPrompt = validationUserPrompt,
            threshold = cfg.acceptScoreThreshold,
        )

        val step1Simulated = simulateStructuredPhase(
            phase = ChatStreamEvent.Phase.STEP1_EVAL,
            bridge = streamBridge,
            seed = "${questionId}#STEP1_STREAM",
            fullText = jsonWithOptionalNoise(
                seed = "${questionId}#STEP1_STREAM",
                body = step1IdealJson,
            ),
            throwAfterEmits = if (cfg.throwOnEvalInternal) {
                cfg.throwAfterEmitsOnEvalInternal
            } else {
                -1
            },
            maxChars = cfg.evalMaxChars,
        )

        cfg.logger?.invoke(
            "FakeSlmRepository.runTwoStepAssessment: step1 promptChars=${step1Prompt.length} " +
                    "outChars=${step1Simulated.text.length} completed=${step1Simulated.completed} " +
                    "errorToken=${step1Simulated.errorToken ?: "-"}",
        )

        val rawEvalJson = extractFirstJsonObjectBestEffort(step1Simulated.text)
            ?: step1Simulated.text.trim()

        val parsedStep1 = parseStep1EvalStrict(
            raw = rawEvalJson,
            fallbackReason = fallbackStep1Reason(
                completed = step1Simulated.completed,
                errorToken = step1Simulated.errorToken,
            ),
        )

        val finalStatus = if (parsedStep1.score >= cfg.acceptScoreThreshold) {
            ChatModels.ValidationStatus.ACCEPTED
        } else {
            ChatModels.ValidationStatus.NEED_FOLLOW_UP
        }

        val finalStep1 = parsedStep1.copy(status = finalStatus)

        cfg.logger?.invoke(
            "FakeSlmRepository.runTwoStepAssessment: step1 decision status=${finalStep1.status.name} " +
                    "score=${finalStep1.score} reasonLen=${finalStep1.reason.length}",
        )

        if (finalStatus == ChatModels.ValidationStatus.ACCEPTED) {
            return ChatValidation.TwoStepAssessmentResult(
                step1 = finalStep1,
                step2 = null,
                rawEvalJson = rawEvalJson,
                rawFollowUpJson = null,
            )
        }

        if (cfg.resetConversationEachRequest) {
            cfg.logger?.invoke("FakeSlmRepository: resetConversation (step2) metaOnly=true")
        }

        val step2Prompt = promptBuilder.buildFollowUpPrompt(
            questionId = questionId,
            userPrompt = validationUserPrompt,
            evalJson = rawEvalJson,
            acceptScoreThreshold = cfg.acceptScoreThreshold,
            userPromptMaxChars = FOLLOWUP_USER_PROMPT_MAX_CHARS,
            evalJsonMaxChars = FOLLOWUP_EVAL_JSON_MAX_CHARS,
        )

        val step2IdealJson = generateFollowUpJson(
            seed = "${questionId}#STEP2#${promptFingerprint(validationUserPrompt)}",
            questionId = questionId,
            userPrompt = validationUserPrompt,
            evalJson = rawEvalJson,
            threshold = cfg.acceptScoreThreshold,
        )

        val step2StreamText = clipForSafety(
            text = jsonWithOptionalNoise(
                seed = "${questionId}#STEP2_STREAM",
                body = step2IdealJson,
            ),
            maxChars = cfg.followUpMaxChars,
        )

        val step2Simulated = simulateStructuredPhase(
            phase = ChatStreamEvent.Phase.STEP2_FOLLOW_UP,
            bridge = streamBridge,
            seed = "${questionId}#STEP2_STREAM",
            fullText = step2StreamText,
            throwAfterEmits = cfg.throwAfterEmits,
            maxChars = cfg.followUpMaxChars,
        )

        cfg.logger?.invoke(
            "FakeSlmRepository.runTwoStepAssessment: step2 promptChars=${step2Prompt.length} " +
                    "outChars=${step2Simulated.text.length} completed=${step2Simulated.completed} " +
                    "errorToken=${step2Simulated.errorToken ?: "-"}",
        )

        val rawFollowUpJson = extractFirstJsonObjectBestEffort(step2Simulated.text)
            ?: step2Simulated.text.trim()

        val parsedStep2 = parseStep2FollowUpStrict(
            raw = rawFollowUpJson,
            fallbackFollowUp = request.fallbackFollowUp,
        )

        return ChatValidation.TwoStepAssessmentResult(
            step1 = finalStep1,
            step2 = parsedStep2.copy(status = ChatModels.ValidationStatus.NEED_FOLLOW_UP),
            rawEvalJson = rawEvalJson,
            rawFollowUpJson = rawFollowUpJson,
        )
    }

    // ---------------------------------------------------------------------
    // One-step
    // ---------------------------------------------------------------------

    private fun requestOneStepLikeReal(
        userPrompt: String,
        rawSeed: String,
    ): Flow<String> {
        if (cfg.resetConversationEachRequest) {
            cfg.logger?.invoke("FakeSlmRepository: resetConversation (oneStep) metaOnly=true")
        }

        val main = generateAnswerLikeText(
            userPrompt = userPrompt,
            seed = rawSeed,
        )

        cfg.logger?.invoke("FakeSlmRepository.oneStep: outChars=${main.length}")

        return streamTextAsCallbackFlow(
            seed = rawSeed,
            fullText = main,
            throwAfterEmits = cfg.throwAfterEmits,
            firstChunkDelayMs = cfg.firstChunkDelayMs,
            chunkDelayMs = cfg.chunkDelayMs,
            chunkSizeChars = cfg.chunkSizeChars,
        )
    }

    // ---------------------------------------------------------------------
    // Legacy two-step request(prompt) flow
    // ---------------------------------------------------------------------

    /**
     * Legacy public request(prompt) mode.
     *
     * Notes:
     * - This path streams plain text/JSON to the returned Flow.
     * - It now respects the Step-1 score threshold and skips Step-2 when accepted.
     */
    private suspend fun requestTwoStepEvalThenFollowUp(
        userPrompt: String,
        rawSeed: String,
    ): Flow<String> {
        val u = userPrompt.trim()
        if (u.isBlank()) return emptyFlow()

        val questionId = extractQuestionIdFromUserPrompt(u)
            ?: "AUTO-${UUID.randomUUID().toString().take(8)}"

        if (cfg.resetConversationEachRequest) {
            cfg.logger?.invoke("FakeSlmRepository: resetConversation (evalScore) metaOnly=true")
        }

        val evalPrompt = promptBuilder.buildEvalScorePrompt(
            questionId = questionId,
            userPrompt = u,
            acceptScoreThreshold = cfg.acceptScoreThreshold,
            userPromptMaxChars = EVAL_USER_PROMPT_MAX_CHARS,
        )

        val evalJsonIdeal = generateEvalScoreJson(
            seed = "$rawSeed#EVAL",
            questionId = questionId,
            userPrompt = u,
            threshold = cfg.acceptScoreThreshold,
        )

        val evalCollected = simulateInternalCollection(
            seed = "$rawSeed#EVAL_INTERNAL",
            fullText = jsonWithOptionalNoise(
                seed = "$rawSeed#EVAL_INTERNAL",
                body = evalJsonIdeal,
            ),
            throwAfterEmits = if (cfg.throwOnEvalInternal) {
                cfg.throwAfterEmitsOnEvalInternal
            } else {
                -1
            },
            maxChars = cfg.evalMaxChars,
        )

        val rawEvalJson = extractFirstJsonObjectBestEffort(evalCollected.text)
            ?: evalCollected.text.trim()

        val parsedStep1 = parseStep1EvalStrict(
            raw = rawEvalJson,
            fallbackReason = fallbackStep1Reason(
                completed = evalCollected.completed,
                errorToken = evalCollected.errorToken,
            ),
        )

        val accepted = parsedStep1.score >= cfg.acceptScoreThreshold

        cfg.logger?.invoke(
            "FakeSlmRepository.twoStepLegacy: evalPromptChars=${evalPrompt.length} " +
                    "evalOutChars=${evalCollected.text.length} evalScore=${parsedStep1.score} accepted=$accepted",
        )

        if (accepted) {
            return streamTextAsCallbackFlow(
                seed = "$rawSeed#STEP1_ONLY",
                fullText = jsonWithOptionalNoise(
                    seed = "$rawSeed#STEP1_ONLY",
                    body = rawEvalJson.ifBlank { evalJsonIdeal },
                ),
                throwAfterEmits = cfg.throwAfterEmits,
                firstChunkDelayMs = cfg.firstChunkDelayMs,
                chunkDelayMs = cfg.chunkDelayMs,
                chunkSizeChars = cfg.chunkSizeChars,
            )
        }

        if (cfg.resetConversationEachRequest) {
            cfg.logger?.invoke("FakeSlmRepository: resetConversation (followUp) metaOnly=true")
        }

        val followUpPrompt = promptBuilder.buildFollowUpPrompt(
            questionId = questionId,
            userPrompt = u,
            evalJson = rawEvalJson,
            acceptScoreThreshold = cfg.acceptScoreThreshold,
            userPromptMaxChars = FOLLOWUP_USER_PROMPT_MAX_CHARS,
            evalJsonMaxChars = FOLLOWUP_EVAL_JSON_MAX_CHARS,
        )

        val followUpJson = generateFollowUpJson(
            seed = "$rawSeed#FOLLOWUP",
            questionId = questionId,
            userPrompt = u,
            evalJson = rawEvalJson,
            threshold = cfg.acceptScoreThreshold,
        )

        val streamed = clipForSafety(
            text = followUpJson,
            maxChars = cfg.followUpMaxChars,
        )

        cfg.logger?.invoke(
            "FakeSlmRepository.twoStepLegacy: followUpPromptChars=${followUpPrompt.length} " +
                    "followUpOutChars=${streamed.length}",
        )

        return streamTextAsCallbackFlow(
            seed = "$rawSeed#FOLLOWUP_STREAM",
            fullText = jsonWithOptionalNoise(
                seed = "$rawSeed#FOLLOWUP_STREAM",
                body = streamed,
            ),
            throwAfterEmits = cfg.throwAfterEmits,
            firstChunkDelayMs = cfg.firstChunkDelayMs,
            chunkDelayMs = cfg.chunkDelayMs,
            chunkSizeChars = cfg.chunkSizeChars,
        )
    }

    // ---------------------------------------------------------------------
    // Validator-style prompt compatibility
    // ---------------------------------------------------------------------

    private fun buildValidatorStylePrompt(
        userPrompt: String,
        phase: ChatValidation.PromptPhase,
    ): String {
        val phaseLine = when (phase) {
            ChatValidation.PromptPhase.VALIDATE_MAIN -> "PHASE=VALIDATE_MAIN"
            ChatValidation.PromptPhase.VALIDATE_FOLLOW_UP -> "PHASE=VALIDATE_FOLLOW_UP"
            else -> {""}
        }

        return """
SYSTEM:
You are a helpful assistant.
$phaseLine
USER:
$userPrompt
""".trimIndent()
    }

    // ---------------------------------------------------------------------
    // Structured streaming simulation
    // ---------------------------------------------------------------------

    private data class StreamSimulationResult(
        val text: String,
        val completed: Boolean,
        val errorToken: String?,
    )

    /**
     * Simulate phase streaming through ChatStreamBridge.
     *
     * Notes:
     * - This is used by runTwoStepAssessment().
     * - Partial text is preserved when failures happen.
     * - Cancellation is propagated normally.
     */
    private suspend fun simulateStructuredPhase(
        phase: ChatStreamEvent.Phase,
        bridge: ChatStreamBridge?,
        seed: String,
        fullText: String,
        throwAfterEmits: Int,
        maxChars: Int,
    ): StreamSimulationResult {
        val safeText = clipForSafety(fullText, maxChars)
        val safeChunkSize = cfg.chunkSizeChars.coerceAtLeast(1)
        val sessionId = bridge?.begin(phase)

        val out = StringBuilder(minOf(safeText.length, maxChars))
        var emitCount = 0

        try {
            if (throwAfterEmits == 0) {
                throw RuntimeException("fake_stream_error")
            }

            if (cfg.firstChunkDelayMs > 0L) {
                delay(cfg.firstChunkDelayMs)
            }

            var i = 0
            while (i < safeText.length) {
                if (throwAfterEmits >= 0 && emitCount >= throwAfterEmits) {
                    throw RuntimeException("fake_stream_error")
                }

                val end = min(i + safeChunkSize, safeText.length)
                val chunk = safeText.substring(i, end)
                out.append(chunk)

                if (sessionId != null && chunk.isNotEmpty()) {
                    bridge.emitChunk(sessionId, chunk)
                }

                emitCount += 1
                i = end

                if (cfg.chunkDelayMs > 0L && i < safeText.length) {
                    delay(cfg.chunkDelayMs)
                }
            }

            if (sessionId != null) {
                bridge.end(sessionId)
            }

            cfg.logger?.invoke(
                "FakeSlmRepository.simulateStructuredPhase: phase=$phase completed=true " +
                        "seedFp=${promptFingerprint(seed)} outChars=${out.length}",
            )

            return StreamSimulationResult(
                text = out.toString(),
                completed = true,
                errorToken = null,
            )
        } catch (ce: CancellationException) {
            if (sessionId != null) {
                runCatching {
                    bridge.error(
                        sessionId = sessionId,
                        token = ChatStreamEvent.Codes.CANCELLED,
                        code = ChatStreamEvent.Codes.CANCELLED,
                    )
                }
            }
            cfg.logger?.invoke(
                "FakeSlmRepository.simulateStructuredPhase: phase=$phase cancelled seedFp=${promptFingerprint(seed)}",
            )
            throw ce
        } catch (t: Throwable) {
            val token = t.message?.takeIf { it.isNotBlank() }
                ?: t::class.java.simpleName

            if (sessionId != null) {
                runCatching {
                    bridge.error(
                        sessionId = sessionId,
                        token = token,
                        code = ChatStreamEvent.Codes.ERROR,
                    )
                }
            }

            cfg.logger?.invoke(
                "FakeSlmRepository.simulateStructuredPhase: phase=$phase completed=false " +
                        "seedFp=${promptFingerprint(seed)} outChars=${out.length} errorToken=$token",
            )

            return StreamSimulationResult(
                text = out.toString(),
                completed = false,
                errorToken = token,
            )
        }
    }

    /**
     * Simulate internal collection for legacy request(prompt) mode.
     *
     * Notes:
     * - This does not use ChatStreamBridge.
     * - It is intentionally deterministic and budget-aware.
     */
    private suspend fun simulateInternalCollection(
        seed: String,
        fullText: String,
        throwAfterEmits: Int,
        maxChars: Int,
    ): StreamSimulationResult {
        val safeText = clipForSafety(fullText, maxChars)
        val safeChunkSize = cfg.chunkSizeChars.coerceAtLeast(1)
        val out = StringBuilder(minOf(safeText.length, maxChars))
        var emitCount = 0

        try {
            if (throwAfterEmits == 0) {
                throw RuntimeException("fake_stream_error")
            }

            if (cfg.firstChunkDelayMs > 0L) {
                delay(cfg.firstChunkDelayMs)
            }

            var i = 0
            while (i < safeText.length) {
                if (throwAfterEmits >= 0 && emitCount >= throwAfterEmits) {
                    throw RuntimeException("fake_stream_error")
                }

                val end = min(i + safeChunkSize, safeText.length)
                val chunk = safeText.substring(i, end)

                val remaining = maxChars - out.length
                if (remaining <= 0) {
                    break
                }

                if (chunk.length <= remaining) {
                    out.append(chunk)
                } else {
                    out.append(chunk.take(remaining))
                    break
                }

                emitCount += 1
                i = end

                if (cfg.chunkDelayMs > 0L && i < safeText.length) {
                    delay(cfg.chunkDelayMs)
                }
            }

            cfg.logger?.invoke(
                "FakeSlmRepository.simulateInternalCollection: completed=true seedFp=${promptFingerprint(seed)} outChars=${out.length}",
            )

            return StreamSimulationResult(
                text = out.toString(),
                completed = true,
                errorToken = null,
            )
        } catch (ce: CancellationException) {
            cfg.logger?.invoke(
                "FakeSlmRepository.simulateInternalCollection: cancelled seedFp=${promptFingerprint(seed)}",
            )
            throw ce
        } catch (t: Throwable) {
            val token = t.message?.takeIf { it.isNotBlank() }
                ?: t::class.java.simpleName

            cfg.logger?.invoke(
                "FakeSlmRepository.simulateInternalCollection: completed=false seedFp=${promptFingerprint(seed)} " +
                        "outChars=${out.length} errorToken=$token",
            )

            return StreamSimulationResult(
                text = out.toString(),
                completed = false,
                errorToken = token,
            )
        }
    }

    // ---------------------------------------------------------------------
    // Streaming callbackFlow
    // ---------------------------------------------------------------------

    private fun streamTextAsCallbackFlow(
        seed: String,
        fullText: String,
        throwAfterEmits: Int,
        firstChunkDelayMs: Long,
        chunkDelayMs: Long,
        chunkSizeChars: Int,
    ): Flow<String> {
        val safeChunkSize = chunkSizeChars.coerceAtLeast(1)
        val safeChunkDelay = chunkDelayMs.coerceAtLeast(0L)
        val safeFirstDelay = firstChunkDelayMs.coerceAtLeast(0L)

        return callbackFlow {
            val closed = AtomicBoolean(false)
            var producer: Job? = null

            fun closeOnce(cause: Throwable? = null) {
                if (!closed.compareAndSet(false, true)) return
                if (cause == null) close() else close(cause)
            }

            producer = launch(Dispatchers.Default) {
                try {
                    if (throwAfterEmits == 0) {
                        throw RuntimeException("fake_stream_error")
                    }

                    if (safeFirstDelay > 0L) {
                        delay(safeFirstDelay)
                    }

                    var emitCount = 0
                    var i = 0
                    while (i < fullText.length) {
                        if (throwAfterEmits >= 0 && emitCount >= throwAfterEmits) {
                            throw RuntimeException("fake_stream_error")
                        }

                        val end = min(i + safeChunkSize, fullText.length)
                        val chunk = fullText.substring(i, end)
                        trySend(chunk)
                        emitCount += 1
                        i = end

                        if (safeChunkDelay > 0L && i < fullText.length) {
                            delay(safeChunkDelay)
                        }
                    }

                    cfg.logger?.invoke(
                        "FakeSlmRepository.stream: done seedFp=${promptFingerprint(seed)} outChars=${fullText.length}",
                    )
                    closeOnce()
                } catch (ce: CancellationException) {
                    cfg.logger?.invoke(
                        "FakeSlmRepository.stream: cancelled seedFp=${promptFingerprint(seed)}",
                    )
                    closeOnce()
                } catch (t: Throwable) {
                    cfg.logger?.invoke(
                        "FakeSlmRepository.stream: error type=${t::class.java.simpleName} seedFp=${promptFingerprint(seed)}",
                    )
                    closeOnce(t)
                }
            }

            awaitClose {
                producer.cancel()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Deterministic Step-1 / Step-2 generators
    // ---------------------------------------------------------------------

    /**
     * Generate Step-1 eval JSON deterministically.
     *
     * Heuristic:
     * - Longer, more structured prompts score higher.
     * - Very short / obviously noisy prompts score lower.
     */
    private fun generateEvalScoreJson(
        seed: String,
        questionId: String,
        userPrompt: String,
        threshold: Int,
    ): String {
        val normalized = userPrompt.trim()
        val fp = promptFingerprint(seed)

        val lenScore = (normalized.length * 3).coerceIn(0, 100)
        val structureBoost = listOf(
            "QUESTION_ID:",
            "MAIN_ANSWER_BEGIN",
            "FOLLOW_UP_ANSWER_BEGIN",
            "FOLLOW_UP_HISTORY_BEGIN",
        ).count { normalized.contains(it) } * 6

        val obviousNoisePenalty = if (looksLikeNoiseOnly(normalized)) 35 else 0
        val jitter = (fp % 9) - 4

        val score = (lenScore + structureBoost + jitter - obviousNoisePenalty)
            .coerceIn(0, 100)

        val status = if (score >= threshold) {
            "ACCEPTED"
        } else {
            "NEED_FOLLOW_UP"
        }

        val reason = if (status == "ACCEPTED") {
            "The response is specific enough to continue without another clarification."
        } else {
            "The response is missing important constraints or concrete details."
        }

        val missing = if (score >= threshold) {
            emptyList()
        } else {
            buildMissingHints(normalized)
        }

        return buildString {
            append("{\n")
            append("  \"status\": ").append(status.jsonQuote()).append(",\n")
            append("  \"score\": ").append(score).append(",\n")
            append("  \"reason\": ").append(reason.jsonQuote())
            if (missing.isNotEmpty()) {
                append(",\n")
                append("  \"missing\": [")
                missing.forEachIndexed { index, item ->
                    if (index > 0) append(", ")
                    append(item.jsonQuote())
                }
                append("]\n")
            } else {
                append("\n")
            }
            append("}")
        }
    }

    /**
     * Generate Step-2 follow-up JSON deterministically.
     *
     * Rules:
     * - If score >= threshold => ACCEPTED with empty followUpQuestion.
     * - Else => NEED_FOLLOW_UP with exactly one concise follow-up question.
     */
    private fun generateFollowUpJson(
        seed: String,
        questionId: String,
        userPrompt: String,
        evalJson: String,
        threshold: Int,
    ): String {
        val score = parseScoreBestEffort(evalJson) ?: 0
        if (score >= threshold) {
            return """
{
  "status": "ACCEPTED",
  "followUpQuestion": ""
}
""".trimIndent()
        }

        val question = pickFollowUpQuestion(
            seed = seed,
            userPrompt = userPrompt,
            score = score,
        )
        val assistantMessage = pickFollowUpAssistantMessage(
            seed = seed,
            questionId = questionId,
            userPrompt = userPrompt,
            score = score,
        )

        return """
{
  "status": "NEED_FOLLOW_UP",
  "assistantMessage": ${assistantMessage.jsonQuote()},
  "followUpQuestion": ${question.jsonQuote()}
}
""".trimIndent()
    }

    private fun pickFollowUpQuestion(
        seed: String,
        userPrompt: String,
        score: Int,
    ): String {
        val t = userPrompt.trim()
        val idx = abs(promptFingerprint(seed) + score + t.length) % 4
        return when (idx) {
            0 -> "What is the exact goal or expected output?"
            1 -> "Which constraint matters most here: time, budget, device, language, or format?"
            2 -> "Can you provide one concrete example input and the expected result?"
            else -> "How will you judge whether the result is correct or successful?"
        }
    }

    private fun pickFollowUpAssistantMessage(
        seed: String,
        questionId: String,
        userPrompt: String,
        score: Int,
    ): String {
        val idx = abs(promptFingerprint("$seed#$questionId") + userPrompt.length + score) % 3
        return when (idx) {
            0 -> "Thanks. I need one more specific detail before I can proceed."
            1 -> "I understand the direction, but one key constraint is still unclear."
            else -> "This is close, but I still need one concrete clarification."
        }
    }

    // ---------------------------------------------------------------------
    // Legacy PHASE prompt compatibility
    // ---------------------------------------------------------------------

    private fun buildLegacyValidatorJsonFromPrompt(
        prompt: String,
        phase: ChatValidation.PromptPhase,
    ): String {
        val mainAnswer = extractBlockAny(
            text = prompt,
            candidates = listOf(
                MarkerPair("MAIN_ANSWER_BEGIN", "MAIN_ANSWER_END"),
            ),
        ).orEmpty()

        return when (phase) {
            ChatValidation.PromptPhase.VALIDATE_MAIN -> {
                if (mainAnswer.trim().length >= 12) {
                    """
{
  "status": "ACCEPTED",
  "assistantMessage": "Looks good. Thanks!"
}
""".trimIndent()
                } else {
                    """
{
  "status": "NEED_FOLLOW_UP",
  "assistantMessage": "Thanks. I need more detail to validate your answer.",
  "followUpQuestion": "Add one concrete example (what, where, or when)."
}
""".trimIndent()
                }
            }
            ChatValidation.PromptPhase.VALIDATE_FOLLOW_UP -> {
                """
{
  "status": "NEED_FOLLOW_UP",
  "assistantMessage": "Good. One more measurable detail would make this complete.",
  "followUpQuestion": "Add one measurable detail such as number, duration, frequency, or cost."
}
""".trimIndent()
            }

            else -> {""}
        }
    }

    // ---------------------------------------------------------------------
    // Prompt reconstruction for structured two-step mode
    // ---------------------------------------------------------------------

    /**
     * Build a validation-style user prompt aligned with the real repository path.
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

        val extracted = if (hasFollowUp && treatAsHistory) {
            extractLatestFollowUpTurn(fuRaw)
        } else {
            null
        }

        val followUpAnswerText = when {
            !hasFollowUp -> ""
            extracted != null && extracted.answer.isNotBlank() -> extracted.answer.trim()
            !treatAsHistory -> fuRaw
            else -> fuRaw
        }

        val followUpQuestionText = when {
            extracted != null && extracted.question.isNotBlank() -> extracted.question.trim()
            else -> ""
        }

        if (hasFollowUp) {
            cfg.logger?.invoke(
                "FakeSlmRepository.followUpDetected: phase=${phase.name} " +
                        "treatAsHistory=$treatAsHistory latestQlen=${followUpQuestionText.length} " +
                        "latestAlen=${followUpAnswerText.length}",
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

    // ---------------------------------------------------------------------
    // Strict parsing helpers for structured mode
    // ---------------------------------------------------------------------

    private fun parseStep1EvalStrict(
        raw: String,
        fallbackReason: String,
    ): ChatValidation.Step1EvalResult {
        val json = extractFirstJsonObjectBestEffort(raw)
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
        val json = extractFirstJsonObjectBestEffort(raw)
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
        completed: Boolean,
        errorToken: String?,
    ): String {
        return when {
            completed -> "Validation result could not be parsed."
            errorToken.equals("fake_stream_error", ignoreCase = true) ->
                "Validation failed before a reliable score was produced."
            errorToken.equals(ChatStreamEvent.Codes.CANCELLED, ignoreCase = true) ->
                "Validation was cancelled."
            else ->
                "Validation did not complete successfully."
        }
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

        val lower0 = t.lowercase()
        for (p in prefixPatterns) {
            if (lower0.startsWith(p)) {
                t = t.drop(p.length).trim()
                break
            }
        }

        val lower = t.lowercase()
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
        if (lower in garbage) return null
        if (t.length < 3) return null

        return t
    }

    // ---------------------------------------------------------------------
    // Follow-up payload parsing helpers
    // ---------------------------------------------------------------------

    private fun looksLikeFollowUpHistory(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        return raw.lineSequence().any { line ->
            FOLLOW_UP_HISTORY_LINE_RE.matches(line.trim())
        }
    }

    private data class FollowUpTurnExtract(
        val question: String,
        val answer: String,
    )

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
                return@let
            }
            if (CURRENT_Q_RE.matches(t)) continue

            CURRENT_A_RE.matchEntire(t)?.let { m ->
                val (body, nextIdx) = readContinuation(i, m.groupValues[1])
                currentA = body
                i = nextIdx
                return@let
            }
            if (CURRENT_A_RE.matches(t)) continue

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

            i += 1
        }

        val q = (currentQ ?: latestQ).orEmpty().trim()
        val a = (currentA ?: latestA).orEmpty().trim()

        if (q.isBlank() && a.isBlank()) return null
        return FollowUpTurnExtract(
            question = q,
            answer = a,
        )
    }

    // ---------------------------------------------------------------------
    // Extraction helpers
    // ---------------------------------------------------------------------

    private fun detectPhaseFromPrompt(prompt: String): ChatValidation.PromptPhase? {
        val m = Regex("""(?m)^\s*PHASE=(VALIDATE_MAIN|VALIDATE_FOLLOW_UP)\s*$""")
            .find(prompt)
            ?: return null

        return when (m.groupValues.getOrNull(1)) {
            "VALIDATE_FOLLOW_UP" -> ChatValidation.PromptPhase.VALIDATE_FOLLOW_UP
            else -> ChatValidation.PromptPhase.VALIDATE_MAIN
        }
    }

    private fun extractQuestionIdFromUserPrompt(prompt: String): String? {
        val m = Regex("""(?m)^\s*QUESTION_ID:\s*([^\s]+)\s*$""")
            .find(prompt)
            ?: return null

        return m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractUserPromptBestEffort(prompt: String): String {
        val lines = prompt.lines()
        val idx = lines.indexOfFirst { it.trim() == "USER:" }
        if (idx >= 0 && idx + 1 < lines.size) {
            return lines.subList(idx + 1, lines.size)
                .joinToString("\n")
                .trim()
        }
        return prompt.trim()
    }

    private fun extractFirstJsonObjectBestEffort(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null

        var start = -1
        var depth = 0
        var inString = false
        var escape = false

        for (i in t.indices) {
            val c = t[i]

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
                        return t.substring(start, i + 1).trim()
                    }
                }
            }
        }

        return null
    }

    private fun parseScoreBestEffort(json: String): Int? {
        val s = json.trim()
        if (s.isEmpty()) return null

        return runCatching {
            val obj = JSONObject(s)
            if (!obj.has("score")) return@runCatching null
            val score = obj.optInt("score", -1)
            score.takeIf { it in 0..100 }
        }.getOrNull()
    }

    private fun clipForSafety(
        text: String,
        maxChars: Int,
    ): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text

        var end = maxChars.coerceAtMost(text.length)
        if (end <= 0) return ""

        if (end < text.length) {
            val last = text[end - 1]
            val next = text[end]
            if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
                end -= 1
            }
        }

        if (end <= 0) return ""
        return text.take(end)
    }

    private fun maybeThrowOnSetup() {
        if (cfg.throwOnRequestSetup) {
            throw IllegalStateException("fake_request_setup_error")
        }
    }

    private suspend fun maybeDelaySetup() {
        if (cfg.requestSetupDelayMs > 0L) {
            delay(cfg.requestSetupDelayMs)
        }
    }

    private fun jsonWithOptionalNoise(
        seed: String,
        body: String,
    ): String {
        val prependNonJson = shouldPrependNonJson(seed)
        return buildString {
            if (prependNonJson) {
                append("analysis: fake streaming output begins\n")
            }
            append(body)
            if (cfg.appendTrailingNewline) {
                append('\n')
            }
        }
    }

    private fun shouldPrependNonJson(seed: String): Boolean {
        if (!cfg.occasionallyPrependNonJson) return false
        val fp = promptFingerprint(seed)
        return (fp % 5) == 0
    }

    private fun promptFingerprint(prompt: String): Int {
        var h = 17
        val step = (prompt.length / 64).coerceAtLeast(1)
        var i = 0
        while (i < prompt.length) {
            h = 31 * h + prompt[i].code
            i += step
        }
        return h
    }

    // ---------------------------------------------------------------------
    // Heuristics
    // ---------------------------------------------------------------------

    private fun looksLikeNoiseOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return true

        val letters = trimmed.count { it.isLetter() }
        val digits = trimmed.count { it.isDigit() }
        val spaces = trimmed.count { it.isWhitespace() }
        val punctuation = trimmed.length - letters - digits - spaces

        if (trimmed.length <= 6) return true
        if (letters <= 2 && digits <= 1) return true
        if (punctuation > letters + digits) return true

        return false
    }

    private fun buildMissingHints(text: String): List<String> {
        val out = ArrayList<String>(3)

        if (text.length < 20) out += "goal"
        if (!text.contains("example", ignoreCase = true)) out += "example"
        if (!text.contains("constraint", ignoreCase = true) &&
            !text.contains("budget", ignoreCase = true) &&
            !text.contains("format", ignoreCase = true) &&
            !text.contains("language", ignoreCase = true)
        ) {
            out += "constraint"
        }

        return out.distinct().take(3)
    }

    // ---------------------------------------------------------------------
    // Legacy block extraction helpers
    // ---------------------------------------------------------------------

    private data class MarkerPair(
        val begin: String,
        val end: String,
    )

    private fun extractBlock(
        text: String,
        begin: String,
        end: String,
    ): String? {
        var inside = false
        val out = StringBuilder()

        text.lineSequence().forEach { line ->
            val t = line.trim()
            if (!inside) {
                if (t == begin) inside = true
                return@forEach
            }

            if (t == end) {
                return out.toString().trim()
            }

            out.append(line).append('\n')
        }

        return null
    }

    private fun extractBlockAny(
        text: String,
        candidates: List<MarkerPair>,
    ): String? {
        for (p in candidates) {
            val v = extractBlock(text, p.begin, p.end)
            if (v != null) return v
        }
        return null
    }

    // ---------------------------------------------------------------------
    // One-step answer-like generator
    // ---------------------------------------------------------------------

    private fun generateAnswerLikeText(
        userPrompt: String,
        seed: String,
    ): String {
        val fp = promptFingerprint(seed)
        val firstLine = userPrompt.lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(48)

        return buildString {
            append("Fake answer (fp=").append(fp).append("). ")
            if (firstLine.isNotBlank()) {
                append("TopicHint=")
                append(
                    firstLine
                        .replace("{", "")
                        .replace("}", "")
                        .replace("\"", ""),
                )
                append(". ")
            }
            append("This is a deterministic streaming response used for integration testing.")
        }
    }

    // ---------------------------------------------------------------------
    // JSON escaping
    // ---------------------------------------------------------------------

    private fun String.jsonQuote(): String {
        val sb = StringBuilder(length + 2)
        sb.append('"')
        for (ch in this) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
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
        private const val EVAL_USER_PROMPT_MAX_CHARS: Int = 4_000
        private const val FOLLOWUP_USER_PROMPT_MAX_CHARS: Int = 4_000
        private const val FOLLOWUP_EVAL_JSON_MAX_CHARS: Int = 2_000

        private const val MAX_FOLLOW_UP_ANSWER_CHARS = 8_000
        private const val MAX_FOLLOW_UP_QUESTION_CHARS = 2_000
        private const val MAX_FOLLOW_UP_HISTORY_CHARS = 16_000

        private val FOLLOW_UP_HISTORY_LINE_RE = Regex("""^FOLLOW_UP_\d+_[QA]:\s*.*$""")
        private val CURRENT_Q_RE = Regex("""^CURRENT_FOLLOW_UP_Q:\s*(.*)$""")
        private val CURRENT_A_RE = Regex("""^CURRENT_FOLLOW_UP_A:\s*(.*)$""")
        private val TURN_Q_RE = Regex("""^FOLLOW_UP_(\d+)_Q:\s*(.*)$""")
        private val TURN_A_RE = Regex("""^FOLLOW_UP_(\d+)_A:\s*(.*)$""")

        private val ANY_MARKER_RE = Regex(
            """^(CURRENT_FOLLOW_UP_[QA]|FOLLOW_UP_\d+_[QA]|FOLLOW_UP_TURNS:|FOLLOW_UP_HISTORY_BEGIN|FOLLOW_UP_HISTORY_END)\s*:?.*$""",
        )
    }
}