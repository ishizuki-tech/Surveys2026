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
import com.negi.surveys.chat.ChatValidation
import com.negi.surveys.chat.DefaultSlmPromptBuilder
import com.negi.surveys.chat.SlmPromptBuilderI
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

/**
 * Repository that orchestrates a 2-step LLM flow:
 * 1) Eval (internal): Judge if the prompt is answerable / sufficient and return a JSON score.
 * 2) Follow-up (streamed): Generate the next follow-up question as JSON (or ACCEPTED).
 *
 * Design notes:
 * - buildPrompt() MUST NOT perform I/O (it is non-suspend and may be called on main thread).
 * - SurveyConfig should come from a single source (installed config).
 * - Optional asset fallback is allowed ONLY in suspend paths and must never auto-install config.
 * - Prompt composition is delegated to [SlmPromptBuilderI] for single-source prompt contract.
 *
 * Warmup notes:
 * - WarmupController/SLMWarmupEngine will attempt to warm the repository by calling [warmup].
 * - IMPORTANT: warmup() must NOT hop threads internally (to preserve EGL context if the caller provided one).
 */
class SlmRepository(
    context: Context,
    private val configAssetName: String = "survey.yaml",
    private val fallbackModelName: String = "Gemma3n4B",
    private val fallbackModelFileName: String = "Gemma3n4B.litertlm",
    private val resetConversationEachRequest: Boolean = true,

    /** If true, run Eval -> FollowUpQuestion flow. If false, run a single generation stream. */
    private val enableTwoStepEval: Boolean = true,

    /** Score threshold below which we must ask a follow-up question. */
    private val acceptScoreThreshold: Int = 70,

    /**
     * If true, allow loading config from assets when installed config is not available.
     *
     * IMPORTANT:
     * - This is executed ONLY from suspend paths (request()).
     * - This must never call installProcessConfig() here; Application is the owner.
     * - Prefer keeping this false in production to guarantee single-source behavior.
     */
    private val allowAssetConfigFallback: Boolean = false,

    /**
     * Injected prompt builder.
     *
     * NOTE:
     * - Keep it stateless.
     * - Do NOT perform I/O inside the builder.
     */
    private val promptBuilder: SlmPromptBuilderI = DefaultSlmPromptBuilder,

    /** Debug options (safe defaults). */
    debug: DebugConfig = DebugConfig(),
) : ChatValidation.RepositoryI, WarmupController.WarmupCapableRepository {

    data class DebugConfig(
        /** Enable debug logging. */
        val enabled: Boolean = false,

        /** If true, logs include clipped prompt/result blocks. */
        val logClippedText: Boolean = true,

        /** If true, logs include FULL prompt/result text (DANGEROUS in prod). */
        val logFullText: Boolean = false,

        /** Max chars logged for prompt text (when clipped logging is enabled). */
        val maxPromptLogChars: Int = 2_000,

        /** Max chars logged for result text (when clipped logging is enabled). */
        val maxResultLogChars: Int = 2_000,

        /**
         * If true, stream Step-1 (Eval) output to the caller flow.
         *
         * IMPORTANT:
         * - This changes the output stream (Eval output may appear before Follow-up output).
         * - Keep this OFF in production unless UI expects it.
         */
        val streamEvalOutputToClient: Boolean = true,
    ) {
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
     * Cached config reference for fast paths.
     *
     * NOTE:
     * - Do not assume cached config is always present.
     * - Installed config should be the canonical source.
     */
    private val cachedConfig = AtomicReference<SurveyConfig?>(null)
    private val configLoadMutex = Mutex()

    /** Model cache keyed by "name::absolutePath". */
    private val modelCache = ConcurrentHashMap<String, ModelHolder>()

    class ModelNotReadyException(message: String) : IllegalStateException(message)

    override fun buildPrompt(userPrompt: String): String {
        val u = userPrompt.trim()
        if (u.isBlank()) return ""

        // IMPORTANT: No I/O here. Only use already-installed or cached config.
        val sys = getSystemPromptFromCacheOnly()
        return promptBuilder.buildAnswerLikePrompt(systemPrompt = sys, userPrompt = u)
    }

    /**
     * Phase-aware prompt building.
     *
     * IMPORTANT:
     * - Validation prompts may already be "strict JSON-only" prompts.
     * - We do NOT inject any PHASE markers into the prompt text here.
     */
    override fun buildPrompt(userPrompt: String, phase: ChatValidation.PromptPhase): String {
        val u = userPrompt.trim()
        if (u.isBlank()) return ""

        // No I/O here.
        val sys = getSystemPromptFromCacheOnly()

        // Today: same wrapping strategy for both phases.
        // Future: if you want different system prompt text per phase, do it here.
        return promptBuilder.buildAnswerLikePrompt(systemPrompt = sys, userPrompt = u)
    }

    override suspend fun request(prompt: String): Flow<String> {
        val input = prompt.trim()
        if (input.isBlank()) return emptyFlow()

        val cfg = getConfigSuspendBestEffort()
        val file = resolveModelFile(cfg)
        val stat = safeFileStat(file)

        if (!stat.exists || !stat.isFile || stat.length <= 0L) {
            // Privacy-safe: do not log file name/path.
            AppLog.w(TAG, "Model not ready: exists=${stat.exists} isFile=${stat.isFile} len=${stat.length}")
            throw ModelNotReadyException("Model file missing or empty")
        }

        val model = getOrCreateModel(cfg = cfg, file = file)

        // IMPORTANT:
        // - Do not run heavy initialization on the main thread.
        // - warmup() path may run inside an EGL context; therefore init itself must not hop threads there.
        withContext(Dispatchers.Default) {
            awaitInitializedModelOnce(
                model = model,
                initOptions = InitOptions.defaultForRepo(),
            )
        }

        return if (enableTwoStepEval) {
            requestEvalScoreThenFollowUp(model = model, userPrompt = input)
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
     * Warmup entry point for WarmupController/SLMWarmupEngine.
     *
     * Contract:
     * - Must not hop threads internally (caller may have provided EGL context).
     * - Must not perform network I/O.
     * - Safe to call multiple times (idempotent-ish via init signature).
     */
    override suspend fun warmup(
        appContext: Context,
        modelFile: File,
        options: WarmupController.Options,
    ): Boolean {
        val cfg = SurveyConfigLoader.getInstalledConfigOrNull() ?: cachedConfig.get()

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
            // Privacy-safe: avoid logging exception messages/paths.
            AppLog.w(TAG, "warmup: failed type=${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * Returns system prompt only if it is already available without I/O.
     * This protects buildPrompt() from accidental blocking calls.
     */
    private fun getSystemPromptFromCacheOnly(): String? {
        val cfg = SurveyConfigLoader.getInstalledConfigOrNull() ?: cachedConfig.get()
        return runCatching { cfg?.composeSystemPrompt() }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------------
    // Two-step: Eval score (internal) -> Follow-up question (streamed)
    // ---------------------------------------------------------------------

    private fun requestEvalScoreThenFollowUp(model: Model, userPrompt: String): Flow<String> {
        val u = userPrompt.trim()
        if (u.isBlank()) return emptyFlow()

        return streamingFlow(model = model) { emit, closeOk, closeErr ->
            val traceId = UUID.randomUUID().toString().take(8)
            val streamEvalToClient = dbg.streamEvalOutputToClient

            val questionId = extractQuestionId(u) ?: "AUTO-${UUID.randomUUID().toString().take(8)}"

            // ----------------------------
            // Step 1: Eval score (optional streaming)
            // ----------------------------
            if (resetConversationEachRequest) {
                resetConversationBestEffort(model, reason = "evalScore")
            }

            val evalPrompt = promptBuilder.buildEvalScorePrompt(
                questionId = questionId,
                userPrompt = u,
                acceptScoreThreshold = acceptScoreThreshold,
                userPromptMaxChars = EVAL_USER_PROMPT_MAX_CHARS,
            )

            dbgLogPrompt(
                traceId = traceId,
                step = "EVAL PHASE",
                questionId = questionId,
                prompt = evalPrompt,
            )

            if (streamEvalToClient) {
                emit(STREAM_EVAL_PREFIX)
            }

            val evalResult = runSdkAndCollectWithBudget(
                model = model,
                input = evalPrompt,
                timeoutMs = EVAL_TIMEOUT_MS,
                maxChars = EVAL_MAX_CHARS,
                onDelta = if (streamEvalToClient) { chunk ->
                    if (chunk.isNotEmpty()) emit(chunk)
                } else {
                    null
                },
            )

            dbgLogResult(
                traceId = traceId,
                step = "EVAL PHASE",
                questionId = questionId,
                reason = evalResult.reason,
                isEarlyStop = evalResult.isEarlyStop,
                text = evalResult.text,
            )

            val evalJsonRaw = extractFirstCompleteJsonObjectBestEffort(evalResult.text)
            val evalParsed = parseEvalScoreBestEffort(evalJsonRaw)

            dbgLogJsonExtraction(
                traceId = traceId,
                step = "EVAL PHASE",
                questionId = questionId,
                raw = evalResult.text,
                extractedJson = evalJsonRaw,
                parsedStatus = evalParsed.status,
                parsedScore = evalParsed.score,
            )

            AppLog.d(
                TAG,
                "eval done: trace=$traceId qid=$questionId score=${evalParsed.score ?: -1} " +
                        "status=${evalParsed.status ?: "?"} reason=${evalResult.reason} earlyStop=${evalResult.isEarlyStop}",
            )

            if (streamEvalToClient) {
                val s = evalParsed.status ?: "?"
                val sc = evalParsed.score ?: -1
                emit("\n$STREAM_EVAL_RESULT_PREFIX status=$s score=$sc\n")
                emit(STREAM_FOLLOWUP_PREFIX)
            }

            if (evalResult.isEarlyStop) {
                delay(EARLY_STOP_STABILIZE_DELAY_MS)
            }

            // ----------------------------
            // Step 2: Follow-up question (streamed)
            // ----------------------------
            if (resetConversationEachRequest) {
                resetConversationBestEffort(model, reason = "followUp")
            }

            val followUpPrompt = promptBuilder.buildFollowUpPrompt(
                questionId = questionId,
                userPrompt = u,
                evalJson = evalJsonRaw ?: evalResult.text,
                acceptScoreThreshold = acceptScoreThreshold,
                userPromptMaxChars = FOLLOWUP_USER_PROMPT_MAX_CHARS,
                evalJsonMaxChars = FOLLOWUP_EVAL_JSON_MAX_CHARS,
            )

            dbgLogPrompt(
                traceId = traceId,
                step = "FOLLOWUP PHASE",
                questionId = questionId,
                prompt = followUpPrompt,
            )

            val s2Buffer = if (dbg.enabled) StringBuilder(1024) else null
            val s2MaxCapture = if (dbg.enabled) (dbg.maxResultLogChars.coerceAtLeast(1024)) else 0

            runSdkAndStream(
                model = model,
                input = followUpPrompt,
                onDelta = { chunk ->
                    if (chunk.isNotEmpty()) {
                        if (s2Buffer != null && s2Buffer.length < s2MaxCapture) {
                            val remaining = s2MaxCapture - s2Buffer.length
                            if (remaining > 0) {
                                if (chunk.length <= remaining) s2Buffer.append(chunk)
                                else s2Buffer.append(chunk.take(remaining))
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
                    if (msg.equals("Cancelled", ignoreCase = true)) closeOk()
                    else closeErr(IllegalStateException(msg))
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
                    if (msg.equals("Cancelled", ignoreCase = true)) closeOk()
                    else closeErr(IllegalStateException(msg))
                },
            )
        }
    }

    // ---------------------------------------------------------------------
    // Flow scaffolding
    // ---------------------------------------------------------------------

    /**
     * Builds a streaming flow guarded by a per-model semaphore.
     *
     * Why:
     * - Avoid concurrent inference on the same model instance.
     * - Provide consistent cancellation behavior.
     * - Reduce callbackFlow boilerplate duplication.
     *
     * Note:
     * - We intentionally avoid Semaphore.withPermit() to be resilient to coroutines version differences.
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

                // Fallback: try a suspending send to reduce drop risk under backpressure.
                // NOTE: This may enqueue many small sends if callbacks burst; keep buffer capacity reasonable.
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
                // If inference already started (permit acquired) and the flow is cancelled,
                // best-effort cancel to stop SDK callbacks.
                if (permitAcquired.isCompleted && !closed.get()) {
                    runCatching { SLM.cancel(model) }
                }
                job.cancel()
            }
        }.buffer(STREAM_BUFFER_CAPACITY)
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

        // The SDK may signal completion via multiple callbacks.
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

                val appended: String = if (chunk.length <= remaining) {
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
    // JSON parsing helpers
    // ---------------------------------------------------------------------

    private data class EvalScore(
        val status: String?,
        val score: Int?,
        val reason: String?,
    )

    /**
     * Extracts the first complete JSON object from a text stream.
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

    private fun parseEvalScoreBestEffort(json: String?): EvalScore {
        val s = json?.trim().orEmpty()
        if (s.isEmpty()) return EvalScore(status = null, score = null, reason = null)

        return runCatching {
            val obj = JSONObject(s)
            val status = obj.optString("status").takeIf { it.isNotBlank() }
            val score = if (obj.has("score")) obj.optInt("score", -1).takeIf { it in 0..100 } else null
            val reason = obj.optString("reason").takeIf { it.isNotBlank() }
            EvalScore(status = status, score = score, reason = reason)
        }.getOrElse {
            EvalScore(status = null, score = null, reason = null)
        }
    }

    // ---------------------------------------------------------------------
    // Debug logging helpers
    // ---------------------------------------------------------------------

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

    private fun dbgLogJsonExtraction(
        traceId: String,
        step: String,
        questionId: String,
        raw: String,
        extractedJson: String?,
        parsedStatus: String?,
        parsedScore: Int?,
    ) {
        if (!dbg.enabled) return
        val rawLen = raw.length
        val rawSha = sha256Hex(raw).take(8)
        val jsonLen = extractedJson?.length ?: 0
        val jsonSha = extractedJson?.let { sha256Hex(it).take(8) } ?: "-"

        AppLog.d(
            TAG,
            "[$step] trace=$traceId qid=$questionId JSON meta rawLen=$rawLen rawSha8=$rawSha jsonLen=$jsonLen jsonSha8=$jsonSha " +
                    "parsedStatus=${parsedStatus ?: "?"} parsedScore=${parsedScore ?: -1}",
        )

        if (dbg.logFullText) {
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId JSON raw(full):\n$raw")
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId JSON extracted(full):\n${extractedJson ?: "(null)"}")
            return
        }

        if (dbg.logClippedText) {
            val rawClip = clipForLog(raw, dbg.maxResultLogChars)
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId JSON raw(clip):\n$rawClip")
            val jsonClip = clipForLog(extractedJson.orEmpty(), dbg.maxResultLogChars)
            AppLog.d(TAG, "[$step] trace=$traceId qid=$questionId JSON extracted(clip):\n$jsonClip")
        }
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

    private fun sha256Hex(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(dig.size * 2)
        for (b in dig) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------
    // Config + model resolution
    // ---------------------------------------------------------------------

    private suspend fun getConfigSuspendBestEffort(): SurveyConfig? {
        SurveyConfigLoader.getInstalledConfigOrNull()?.let { cfg ->
            cachedConfig.set(cfg)
            return cfg
        }

        cachedConfig.get()?.let { return it }

        if (!allowAssetConfigFallback) {
            SafeLog.w(TAG, "Config missing: installed=null; assetFallback=false")
            return null
        }

        return configLoadMutex.withLock {
            SurveyConfigLoader.getInstalledConfigOrNull()?.let { cfg ->
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

            // Privacy-safe: do not log absolute paths or file names.
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

        // Privacy-safe: avoid logging file paths/names.
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

    companion object {
        private const val TAG: String = "SlmRepository"

        private const val RESET_CONVERSATION_TIMEOUT_MS: Long = 15_000L
        private const val EARLY_STOP_STABILIZE_DELAY_MS: Long = 250L

        // Eval score budgets
        private const val EVAL_TIMEOUT_MS: Long = 60_000L
        private const val EVAL_MAX_CHARS: Int = 8_192
        private const val EVAL_USER_PROMPT_MAX_CHARS: Int = 4_000

        // Follow-up budgets
        private const val FOLLOWUP_USER_PROMPT_MAX_CHARS: Int = 4_000
        private const val FOLLOWUP_EVAL_JSON_MAX_CHARS: Int = 2_000

        // Stream buffering (avoid token drops under bursty callbacks).
        private const val STREAM_BUFFER_CAPACITY: Int = 64

        // Optional stream markers (Step-1 streaming).
        private const val STREAM_EVAL_PREFIX: String = "\n[EVAL]\n"
        private const val STREAM_EVAL_RESULT_PREFIX: String = "[EVAL_RESULT]"
        private const val STREAM_FOLLOWUP_PREFIX: String = "\n[FOLLOWUP]\n"

        private val modelGates: ConcurrentHashMap<String, Semaphore> = ConcurrentHashMap()
    }
}