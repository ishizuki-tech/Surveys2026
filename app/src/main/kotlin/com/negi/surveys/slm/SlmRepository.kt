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
import com.negi.surveys.chat.collectToTextResultWithBudget
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.SafeLog
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

class SlmRepository(
    context: Context,
    private val configAssetName: String = "survey.yaml",
    private val fallbackModelName: String = "Gemma3n4B",
    private val fallbackModelFileName: String = "Gemma3n4B.litertlm",
    private val resetConversationEachRequest: Boolean = true,
    private val enableTwoStepEval: Boolean = true,
) : RepositoryI {

    private val appContext: Context = context.applicationContext
    private val cachedConfig = AtomicReference<SurveyConfig?>(null)

    class ModelNotReadyException(message: String) : IllegalStateException(message)

    override fun buildPrompt(userPrompt: String): String {
        val u = userPrompt.trim()
        if (u.isBlank()) return ""

        val cfg = getConfigBestEffort()
        val sys = runCatching { cfg?.composeSystemPrompt() }.getOrNull().orEmpty().trim()

        if (sys.isBlank()) return u

        return """
            $sys

            $u
        """.trimIndent()
    }

    override suspend fun request(prompt: String): Flow<String> {
        val p = prompt.trim()
        if (p.isBlank()) return emptyFlow()

        val file = resolveModelFile()
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            throw ModelNotReadyException("Model file missing or empty: ${file.name}")
        }

        val model = buildModel(file)
        awaitInitializedModel(model)

        return if (enableTwoStepEval) {
            requestTwoStepEval(model = model, userPrompt = p)
        } else {
            requestOneStep(model = model, prompt = p)
        }
    }

    /**
     * One-step request: stream model output directly.
     */
    private suspend fun requestOneStep(model: Model, prompt: String): Flow<String> {
        if (resetConversationEachRequest) {
            resetConversationBestEffort(model, reason = "oneStepRequest")
        }
        return runInferenceAsFlow(model = model, input = prompt)
    }

    /**
     * Two-step request:
     * 1) Run main generation and collect it internally.
     * 2) Run eval generation that outputs exactly one JSON object, and stream that to callers.
     */
    private suspend fun requestTwoStepEval(model: Model, userPrompt: String): Flow<String> {
        // Step 1: main output (internal).
        if (resetConversationEachRequest) {
            resetConversationBestEffort(model, reason = "twoStepMain")
        }

        val mainFlow = runInferenceAsFlow(model = model, input = userPrompt)
        val mainResult = mainFlow.collectToTextResultWithBudget(
            timeoutBudgetMs = TWO_STEP_MAIN_TIMEOUT_MS,
            maxChars = 32_000,
            onChunk = null,
        )

        val questionId = extractQuestionId(userPrompt) ?: "AUTO-${UUID.randomUUID().toString().take(8)}"
        val evalPrompt = buildTwoStepEvalPrompt(
            questionId = questionId,
            userPrompt = userPrompt,
            mainAnswer = mainResult.text,
            mainStopReason = mainResult.reason.name,
            mainErrorToken = mainResult.errorToken,
        )

        // Step 2: eval output streamed to caller.
        // Always reset to isolate eval from any SDK conversation state.
        resetConversationBestEffort(model, reason = "twoStepEval")

        return runInferenceAsFlow(model = model, input = evalPrompt)
    }

    /**
     * Runs SLM inference and exposes deltas as a Flow<String>.
     *
     * Key fix:
     * - Do NOT call cancel() when the run ended normally (done/cleanup/error).
     * - Only call cancel() when the collector stopped early.
     */
    private fun runInferenceAsFlow(model: Model, input: String): Flow<String> {
        val p = input.trim()
        if (p.isBlank()) return emptyFlow()

        return callbackFlow {
            val closed = AtomicBoolean(false)

            // True when the underlying SDK run reached a terminal state.
            // If true, we must NOT call cancel() in awaitClose (prevents sticky cancel TTL).
            val terminal = AtomicBoolean(false)

            fun closeOnce() {
                if (closed.compareAndSet(false, true)) {
                    runCatching { close() }
                }
            }

            SLM.runInference(
                model = model,
                input = p,
                resultListener = { partialResult, done ->
                    if (partialResult.isNotEmpty()) {
                        trySend(partialResult)
                    }
                    if (done) {
                        terminal.set(true)
                        closeOnce()
                    }
                },
                cleanUpListener = {
                    terminal.set(true)
                    closeOnce()
                },
                onError = { msg ->
                    // "Cancelled" is treated as a non-error stop by policy.
                    if (msg.equals("Cancelled", ignoreCase = true)) {
                        terminal.set(true)
                        closeOnce()
                        return@runInference
                    }
                    terminal.set(true)
                    if (closed.compareAndSet(false, true)) close(IllegalStateException(msg))
                },
                images = emptyList(),
                audioClips = emptyList(),
                notifyCancelToOnError = false,
            )

            awaitClose {
                // Cancel only if the collector stopped early (i.e., run not terminal yet).
                if (!terminal.get()) {
                    runCatching { SLM.cancel(model) }
                }
            }
        }
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


    private fun getConfigBestEffort(): SurveyConfig? {
        // Prefer the process-installed config from SurveyAppRoot (single source of truth).
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

        // Use the centralized config normalization/clamping defined in Model.
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

    private fun buildTwoStepEvalPrompt(
        questionId: String,
        userPrompt: String,
        mainAnswer: String,
        mainStopReason: String,
        mainErrorToken: String?,
    ): String {
        val clippedUserPrompt = clipForEval(userPrompt, TWO_STEP_EVAL_USER_PROMPT_MAX_CHARS)
        val clippedMain = clipForEval(mainAnswer, TWO_STEP_EVAL_MAIN_ANSWER_MAX_CHARS)
        val err = mainErrorToken?.takeIf { it.isNotBlank() } ?: "-"

        return """
            Return exactly ONE JSON object and nothing else.
            - No markdown, no code fences, no backticks.
            - Output must start with "{" and end with "}".
            - Do not include extra keys beyond the valid shapes.

            Valid shapes:
            1) {"status":"ACCEPTED","assistantMessage":"..."}
            2) {"status":"NEED_FOLLOW_UP","assistantMessage":"...","followUpQuestion":"..."}

            Rules:
            - Judge whether MAIN_ANSWER is sufficient to satisfy USER_PROMPT.
            - If sufficient: return ACCEPTED with assistantMessage (concise).
            - If not sufficient: return NEED_FOLLOW_UP and ask exactly ONE concise follow-up question.
            - Do not repeat large chunks of USER_PROMPT or MAIN_ANSWER verbatim.

            QUESTION_ID: $questionId
            MAIN_STOP_REASON: $mainStopReason
            MAIN_ERROR_TOKEN: $err

            USER_PROMPT:
            $clippedUserPrompt

            MAIN_ANSWER:
            $clippedMain
        """.trimIndent()
    }

    private fun clipForEval(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text

        var end = maxChars.coerceAtMost(text.length)
        if (end <= 0) return ""

        // Avoid splitting a surrogate pair.
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

    // ---------------------------------------------------------------------
    // Warmup compile barrier helpers (kept here so the file has no top-level clutter).
    // ---------------------------------------------------------------------

    private fun SlmWarmup.CompileState.isTerminal(): Boolean {
        return this is SlmWarmup.CompileState.Compiled ||
                this is SlmWarmup.CompileState.Failed ||
                this is SlmWarmup.CompileState.Cancelled ||
                this is SlmWarmup.CompileState.SkippedNotConfigured
    }

    private fun logCompileBarrier(state: SlmWarmup.CompileState, timedOut: Boolean) {
        val suffix = if (timedOut) " (timedOut)" else ""
        when (state) {
            is SlmWarmup.CompileState.Compiled -> {
                AppLog.d(TAG, "compile barrier: compiled in ${state.elapsedMs}ms$suffix")
            }
            is SlmWarmup.CompileState.Failed -> {
                AppLog.d(TAG, "compile barrier: failed: ${state.message} elapsed=${state.elapsedMs}ms$suffix")
            }
            is SlmWarmup.CompileState.Cancelled -> {
                AppLog.d(TAG, "compile barrier: cancelled elapsed=${state.elapsedMs}ms$suffix")
            }
            is SlmWarmup.CompileState.SkippedNotConfigured -> {
                AppLog.d(TAG, "compile barrier: skipped: ${state.reason} elapsed=${state.elapsedMs}ms$suffix")
            }
            else -> {
                AppLog.d(
                    TAG,
                    "compile barrier: state=${state::class.java.simpleName} elapsed=${state.elapsedMs}ms$suffix",
                )
            }
        }
    }

    companion object {
        private const val TAG: String = "SlmRepository"

        /** Max time the repository will wait for warmup compile before proceeding. */
        private const val COMPILE_BARRIER_TIMEOUT_MS: Long = 1_500L

        /** Two-step: max time budget for the first (main) generation. */
        private const val TWO_STEP_MAIN_TIMEOUT_MS: Long = 120_000L

        /** Two-step: cap the amount of main output included in the eval prompt (avoid huge prompts). */
        private const val TWO_STEP_EVAL_MAIN_ANSWER_MAX_CHARS: Int = 8_000

        /** Two-step: cap the amount of original prompt included in the eval prompt. */
        private const val TWO_STEP_EVAL_USER_PROMPT_MAX_CHARS: Int = 4_000

        /** Best-effort reset timeout. */
        private const val RESET_CONVERSATION_TIMEOUT_MS: Long = 5_000L
    }
}