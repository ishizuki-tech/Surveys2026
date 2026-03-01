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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

private const val TAG = "SlmRepository"

/** Max time the repository will wait for warmup compile before proceeding. */
private const val COMPILE_BARRIER_TIMEOUT_MS: Long = 1_500L

/**
 * Repository implementation backed by SLM (streaming).
 *
 * Contract:
 * - buildPrompt(userPrompt): string composition only (no IO).
 * - request(prompt): suspend; ensures model is initialized then returns Flow<String> deltas.
 *
 * Notes:
 * - Warmup compile is treated as a best-effort optimization.
 * - initializeIfNeeded() remains the source of truth for readiness.
 */
class SlmRepository(
    context: Context,
    private val configAssetName: String = "survey.yaml",
    private val fallbackModelName: String = "Gemma3n4B",
    private val fallbackModelFileName: String = "Gemma3n4B.litertlm",
    private val resetConversationEachRequest: Boolean = true,
) : RepositoryI {

    private val appContext: Context = context.applicationContext
    private val cachedConfig = AtomicReference<SurveyConfig?>(null)

    /**
     * Model-not-ready error used to produce a stable error token in upstream collectors.
     */
    class ModelNotReadyException(message: String) : IllegalStateException(message)

    override fun buildPrompt(userPrompt: String): String {
        val u = userPrompt.trim()
        if (u.isBlank()) return ""

        val cfg = getConfigBestEffort()
        val sys = runCatching { cfg?.composeSystemPrompt() }.getOrNull().orEmpty().trim()

        // If no system prompt is configured, pass through.
        if (sys.isBlank()) return u

        return """
            $sys

            $u
        """.trimIndent()
    }

    override suspend fun request(prompt: String): Flow<String> {
        val p = prompt.trim()
        if (p.isBlank()) {
            return callbackFlow { close() }
        }

        val file = resolveModelFile()
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            throw ModelNotReadyException("Model file missing or empty: ${file.name}")
        }

        val model = buildModel(file)
        awaitInitializedModel(model)


        if (resetConversationEachRequest) {
            // Ensures request isolation if the underlying SDK keeps conversation state.
            val ok = SLM.resetConversationAndAwait(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemMessage = null,
                tools = emptyList(),
                timeoutMs = 5_000L
            )
            if (!ok) {
                AppLog.w(TAG, "resetConversationAndAwait failed or timed out; continuing: model='${model.name}'")
            }
        }


        return callbackFlow {
            val closed = AtomicBoolean(false)

            fun closeOnce() {
                if (closed.compareAndSet(false, true)) {
                    runCatching { close() }
                }
            }

            SLM.runInference(
                model = model,
                input = p,
                resultListener = { delta, done ->
                    if (delta.isNotEmpty()) trySend(delta)
                    if (done) closeOnce()
                },
                cleanUpListener = { closeOnce() },
                onError = { msg ->
                    if (msg.equals("Cancelled", ignoreCase = true)) return@runInference
                    if (closed.compareAndSet(false, true)) close(IllegalStateException(msg))
                },
                images = emptyList(),
                audioClips = emptyList(),
                notifyCancelToOnError = false
            )

            awaitClose {
                // Best effort cancellation when collector stops early.
                runCatching { SLM.cancel(model) }
            }
        }
    }

    private fun getConfigBestEffort(): SurveyConfig? {
        val existing = cachedConfig.get()
        if (existing != null) return existing

        val loaded = runCatching {
            SurveyConfigLoader.fromAssetsValidated(appContext, configAssetName)
        }.getOrNull()

        if (loaded != null) cachedConfig.compareAndSet(null, loaded)
        return cachedConfig.get()
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

        val accel = cfg?.slm?.accelerator
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: Accelerator.GPU.label

        val maxTokens = cfg?.slm?.maxTokens ?: 4096
        val topK = cfg?.slm?.topK ?: 40
        val topP = (cfg?.slm?.topP ?: 0.9).toFloat()
        val temp = (cfg?.slm?.temperature ?: 0.7).toFloat()

        val config: Map<ConfigKey, Any> = mapOf(
            ConfigKey.ACCELERATOR to accel,
            ConfigKey.MAX_TOKENS to maxTokens,
            ConfigKey.TOP_K to topK,
            ConfigKey.TOP_P to topP,
            ConfigKey.TEMPERATURE to temp
        )

        return Model(
            name = modelName,
            taskPath = file.absolutePath,
            config = config
        )
    }

    /**
     * Ensures SLM is initialized for the given [model].
     *
     * Important:
     * - initializeIfNeeded() is treated as the authoritative readiness gate.
     * - Warmup compile may reduce latency, but is not required for correctness.
     */
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
                tools = emptyList()
            )
        }
    }

    /**
     * Returns true if compile state is terminal (no further progress without a new request/reset).
     */
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
                    "compile barrier: state=${state::class.java.simpleName} elapsed=${state.elapsedMs}ms$suffix"
                )
            }
        }
    }
}