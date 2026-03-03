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
import com.negi.surveys.chat.RepositoryI
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.SafeLog
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
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Repository that orchestrates a 2-step LLM flow:
 * 1) Eval (internal): Judge if the prompt is answerable / sufficient and return a JSON score.
 * 2) Follow-up (streamed): Generate the next follow-up question as JSON (or ACCEPTED).
 *
 * Debug:
 * - Can emit Step1/Step2 prompt + result diagnostics (clipped + sha256 by default).
 * - Full prompt/result logging is OFF by default for privacy.
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

    /** Debug options (safe defaults). */
    debug: DebugConfig = DebugConfig(),
) : RepositoryI {

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
    private val cachedConfig = AtomicReference<SurveyConfig?>(null)

    class ModelNotReadyException(message: String) : IllegalStateException(message)

    override fun buildPrompt(userPrompt: String): String {
        return PromptBuilder.buildAnswerLikePrompt(
            systemPrompt = getSystemPromptBestEffort(),
            userPrompt = userPrompt,
        )
    }

    override suspend fun request(prompt: String): Flow<String> {
        val userPrompt = prompt.trim()
        if (userPrompt.isBlank()) return emptyFlow()

        val file = resolveModelFile()
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            throw ModelNotReadyException("Model file missing or empty: ${file.name}")
        }

        val model = buildModel(file)

        return if (enableTwoStepEval) {
            requestEvalScoreThenFollowUp(model = model, userPrompt = userPrompt)
        } else {
            requestOneStep(model = model, prompt = buildPrompt(userPrompt))
        }
    }

    // ---------------------------------------------------------------------
    // Prompt building
    // ---------------------------------------------------------------------

    private object PromptBuilder {

        fun buildEvalScorePrompt(
            questionId: String,
            userPrompt: String,
        ): String {
            val clippedUser = clipForEval(userPrompt, EVAL_USER_PROMPT_MAX_CHARS)

            return """
                Return exactly ONE JSON object and nothing else.
                - No markdown, no code fences, no backticks.
                - Output must start with "{" and end with "}".
                - Use double quotes for all JSON strings.
                - Do not include trailing commas.

                Required keys:
                - "status": either "ACCEPTED" or "NEED_FOLLOW_UP"
                - "score": integer from 0 to 100
                - "reason": short string (<= 160 chars)

                Optional key:
                - "missing": array of short strings describing missing info.

                Scoring guidance:
                - 90-100: fully answerable, clear constraints, no key ambiguity.
                - 70-89: answerable but minor ambiguity.
                - 40-69: missing important constraints; follow-up is needed.
                - 0-39: very unclear; must ask follow-up.

                Decision rule:
                - If score >= 70 => status="ACCEPTED"
                - Else => status="NEED_FOLLOW_UP"

                QUESTION_ID: $questionId

                USER_PROMPT:
                $clippedUser
            """.trimIndent()
        }

        fun buildFollowUpPrompt(
            questionId: String,
            userPrompt: String,
            evalJson: String,
            acceptScoreThreshold: Int,
        ): String {

            val clippedEval = clipForEval(evalJson, FOLLOWUP_EVAL_JSON_MAX_CHARS)
            val clippedUser = clipForEval(userPrompt, FOLLOWUP_USER_PROMPT_MAX_CHARS)

            return """
                Return exactly ONE JSON object and nothing else.
                - No markdown, no code fences, no backticks.
                - Output must start with "{" and end with "}".
                - Use double quotes for all JSON strings.
                - Do not include trailing commas.

                Valid shapes:
                1) {"status":"ACCEPTED","followUpQuestion":""}
                2) {"status":"NEED_FOLLOW_UP","followUpQuestion":"..."}

                Rules:
                - Read EVAL_JSON and USER_PROMPT.
                - If EVAL_JSON.score >= $acceptScoreThreshold:
                    - Return ACCEPTED with followUpQuestion="".
                - Else:
                    - Return NEED_FOLLOW_UP and ask exactly ONE concise follow-up question.
                    - The question must target the most important missing constraint.
                    - Do not repeat large chunks of USER_PROMPT verbatim.

                QUESTION_ID: $questionId

                EVAL_JSON:
                $clippedEval

                USER_PROMPT:
                $clippedUser
            """.trimIndent()
        }

        fun buildAnswerLikePrompt(
            systemPrompt: String?,
            userPrompt: String,
        ): String {
            val u = userPrompt.trim()
            if (u.isBlank()) return ""

            val sys = systemPrompt.orEmpty().trim()
            if (sys.isBlank()) return u

            return """
                $sys

                $u
            """.trimIndent()
        }

        private fun clipForEval(text: String, maxChars: Int): String {
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
    }

    private fun getSystemPromptBestEffort(): String? {
        val cfg = getConfigBestEffort()
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

        return callbackFlow {
            val closed = AtomicBoolean(false)
            val terminal = AtomicBoolean(false)
            val gate = gateFor(model)
            val gatePermit = CompletableDeferred<Unit>()

            fun closeOnce(cause: Throwable? = null) {
                if (closed.compareAndSet(false, true)) {
                    terminal.set(true)
                    if (cause == null) close() else close(cause)
                }
            }

            val job = launch(Dispatchers.Default) {
                val traceId = UUID.randomUUID().toString().take(8)
                try {
                    gate.withPermit {

                        gatePermit.complete(Unit)

                        awaitInitializedModel(model)

                        val questionId = extractQuestionId(u) ?: "AUTO-${UUID.randomUUID().toString().take(8)}"

                        // ----------------------------
                        // Step 1: Eval score (internal)
                        // ----------------------------
                        if (resetConversationEachRequest) {
                            resetConversationBestEffort(model, reason = "evalScore")
                        }

                        val evalPrompt = PromptBuilder.buildEvalScorePrompt(
                            questionId = questionId,
                            userPrompt = u,
                        )

                        dbgLogPrompt(
                            traceId = traceId,
                            step = "S1_EVAL",
                            questionId = questionId,
                            prompt = evalPrompt,
                        )

                        val evalResult = runSdkAndCollectWithBudget(
                            model = model,
                            input = evalPrompt,
                            timeoutMs = EVAL_TIMEOUT_MS,
                            maxChars = EVAL_MAX_CHARS,
                        )

                        dbgLogResult(
                            traceId = traceId,
                            step = "S1_EVAL",
                            questionId = questionId,
                            reason = evalResult.reason,
                            isEarlyStop = evalResult.isEarlyStop,
                            text = evalResult.text,
                        )

                        val evalJsonRaw = extractFirstJsonObjectBestEffort(evalResult.text)
                        val evalParsed = parseEvalScoreBestEffort(evalJsonRaw)

                        dbgLogJsonExtraction(
                            traceId = traceId,
                            step = "S1_EVAL",
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

                        if (evalResult.isEarlyStop) {
                            delay(EARLY_STOP_STABILIZE_DELAY_MS)
                        }

                        // ----------------------------
                        // Step 2: Follow-up question (streamed)
                        // ----------------------------
                        if (resetConversationEachRequest) {
                            resetConversationBestEffort(model, reason = "followUp")
                        }

                        val followUpPrompt = PromptBuilder.buildFollowUpPrompt(
                            questionId = questionId,
                            userPrompt = u,
                            evalJson = evalJsonRaw ?: evalResult.text,
                            acceptScoreThreshold = acceptScoreThreshold,
                        )

                        dbgLogPrompt(
                            traceId = traceId,
                            step = "S2_FOLLOWUP",
                            questionId = questionId,
                            prompt = followUpPrompt,
                        )

                        // Stream S2 result to caller; also capture for debug log.
                        val s2Buffer = if (dbg.enabled) StringBuilder(1024) else null
                        val s2MaxCapture = if (dbg.enabled) (EVAL_MAX_CHARS.coerceAtLeast(1024)) else 0

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
                                    trySend(chunk)
                                }
                            },
                            onTerminal = { msg ->
                                if (s2Buffer != null) {
                                    dbgLogResult(
                                        traceId = traceId,
                                        step = "S2_FOLLOWUP",
                                        questionId = questionId,
                                        reason = msg,
                                        isEarlyStop = false,
                                        text = s2Buffer.toString(),
                                    )
                                }
                                closeOnce()
                            },
                            onError = { msg ->
                                if (s2Buffer != null) {
                                    dbgLogResult(
                                        traceId = traceId,
                                        step = "S2_FOLLOWUP",
                                        questionId = questionId,
                                        reason = "ERROR:$msg",
                                        isEarlyStop = true,
                                        text = s2Buffer.toString(),
                                    )
                                }
                                if (msg.equals("Cancelled", ignoreCase = true)) closeOnce()
                                else closeOnce(IllegalStateException(msg))
                            },
                        )
                    }
                } catch (ce: CancellationException) {
                    closeOnce()
                } catch (t: Throwable) {
                    closeOnce(t)
                }
            }

            awaitClose {
                if (!terminal.get() && gatePermit.isCompleted) {
                    runCatching { SLM.cancel(model) }
                }
                job.cancel()
            }
        }
    }

    // ---------------------------------------------------------------------
    // One-step (legacy)
    // ---------------------------------------------------------------------

    private fun requestOneStep(model: Model, prompt: String): Flow<String> {
        val p = prompt.trim()
        if (p.isBlank()) return emptyFlow()

        return callbackFlow {
            val closed = AtomicBoolean(false)
            val terminal = AtomicBoolean(false)
            val gate = gateFor(model)
            val gatePermit = CompletableDeferred<Unit>()

            fun closeOnce(cause: Throwable? = null) {
                if (closed.compareAndSet(false, true)) {
                    terminal.set(true)
                    if (cause == null) close() else close(cause)
                }
            }

            val job = launch(Dispatchers.Default) {
                try {
                    gate.withPermit {
                        gatePermit.complete(Unit)

                        awaitInitializedModel(model)

                        if (resetConversationEachRequest) {
                            resetConversationBestEffort(model, reason = "oneStep")
                        }

                        runSdkAndStream(
                            model = model,
                            input = p,
                            onDelta = { chunk ->
                                if (chunk.isNotEmpty()) trySend(chunk)
                            },
                            onTerminal = { mes ->
                                closeOnce()
                            },
                            onError = { msg ->
                                if (msg.equals("Cancelled", ignoreCase = true)) closeOnce()
                                else closeOnce(IllegalStateException(msg))
                            },
                        )
                    }
                } catch (ce: CancellationException) {
                    closeOnce()
                } catch (t: Throwable) {
                    closeOnce(t)
                }
            }

            awaitClose {
                if (!terminal.get() && gatePermit.isCompleted) {
                    runCatching { SLM.cancel(model) }
                }
                job.cancel()
            }
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

        SLM.runInference(
            model = model,
            input = p,
            resultListener = { partialResult, done ->
                if (partialResult.isNotEmpty()) onDelta(partialResult)
                if (done) onTerminal("done on resultListener")
            },
            cleanUpListener = { onTerminal("done on cleanUpListener") },
            onError = { msg -> onError(msg) },
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

        val out = StringBuilder(minOf(maxChars.coerceAtLeast(0), 4_096))
        val done = CompletableDeferred<Unit>()
        val errRef = AtomicReference<String?>(null)
        val reasonRef = AtomicReference("UNKNOWN")
        val earlyStopRef = AtomicBoolean(false)

        fun finish(reason: String, earlyStop: Boolean) {
            reasonRef.set(reason)
            if (earlyStop) earlyStopRef.set(true)
            if (!done.isCompleted) done.complete(Unit)
        }

        runSdkAndStream(
            model = model,
            input = p,
            onDelta = { chunk ->
                if (chunk.isEmpty()) return@runSdkAndStream
                val remaining = maxChars - out.length
                if (remaining <= 0) {
                    earlyStopRef.set(true)
                    reasonRef.set("MAX_CHARS")
                    runCatching { SLM.cancel(model) }
                    return@runSdkAndStream
                }

                if (chunk.length <= remaining) {
                    out.append(chunk)
                } else {
                    out.append(chunk.take(remaining))
                    earlyStopRef.set(true)
                    reasonRef.set("MAX_CHARS")
                    runCatching { SLM.cancel(model) }
                }
            },
            onTerminal = { mes ->
                val current = reasonRef.get()
                if (current == "MAX_CHARS") finish(reason = current + mes, earlyStop = true)
                else finish(reason = "COMPLETED $mes", earlyStop = false)
            },
            onError = { msg ->
                if (msg.equals("Cancelled", ignoreCase = true)) {
                    finish(reason = "CANCELLED", earlyStop = true)
                } else {
                    errRef.set(msg)
                    finish(reason = "ERROR", earlyStop = true)
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

    private fun extractFirstJsonObjectBestEffort(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val start = t.indexOf('{')
        if (start < 0) return null
        val end = t.lastIndexOf('}')
        if (end <= start) return null
        return t.substring(start, end + 1)
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
        val t = text
        if (t.length <= maxChars) return t
        val head = (maxChars * 0.7).toInt().coerceAtLeast(0)
        val tail = (maxChars - head - 64).coerceAtLeast(0)
        val h = t.take(head)
        val suffix = if (tail > 0) t.takeLast(tail) else ""
        val omitted = t.length - (h.length + suffix.length)
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
            sb.append(((b.toInt() shr 4) and 0xF).toString(16))
            sb.append((b.toInt() and 0xF).toString(16))
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------
    // Config + model resolution
    // ---------------------------------------------------------------------

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

    private fun getConfigBestEffort(): SurveyConfig? {
        SurveyConfigLoader.getInstalledConfigOrNull()?.let { cfg ->
            cachedConfig.set(cfg)
            return cfg
        }

        val existing = cachedConfig.get()
        if (existing != null) return existing

        val loaded = runCatching {
            SurveyConfigLoader.fromAssetsValidated(appContext, configAssetName)
        }.onFailure { t ->
            SafeLog.e(TAG, "getConfigBestEffort: load failed type=${t::class.java.simpleName}", t)
        }.getOrNull()

        if (loaded != null) cachedConfig.set(loaded)
        return loaded
    }

    private fun resolveModelFile(): File {
        val cfg = getConfigBestEffort()
        val fileName = cfg?.modelDefaults?.defaultFileName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackModelFileName

        return File(appContext.filesDir, fileName)
    }

    private fun buildModel(file: File): Model {
        val cfg = getConfigBestEffort()

        val modelName = cfg?.modelDefaults?.modelName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackModelName

        val modelConfig: Map<Model.ConfigKey, Any> = Model.buildModelConfigSafe(cfg?.slm)

        return Model(
            name = modelName,
            taskPath = file.absolutePath,
            config = modelConfig,
        )
    }

    private suspend fun awaitInitializedModel(model: Model) {
        withContext(Dispatchers.Default) {
            runCatching { SLM.setApplicationContext(appContext) }

            AppLog.d(TAG, "initializeIfNeeded: model='${model.name}' file='${File(model.taskPath).name}'")
            SLM.initializeIfNeeded(
                context = appContext,
                model = model,
                supportImage = false,
                supportAudio = false,
                systemMessage = null,
                tools = emptyList(),
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

        private val modelGates: ConcurrentHashMap<String, Semaphore> = ConcurrentHashMap()
    }
}