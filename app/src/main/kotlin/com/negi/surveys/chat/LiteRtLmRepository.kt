/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (LiteRT-LM Repository)
 *  ---------------------------------------------------------------------
 *  File: LiteRtLmRepository.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.chat

import android.content.Context
import com.negi.surveys.config.SurveyConfigLoader
import com.negi.surveys.logging.AppLog
import com.negi.surveys.slm.Accelerator
import com.negi.surveys.slm.ConfigKey
import com.negi.surveys.slm.LiteRtLM
import com.negi.surveys.slm.Model
import com.negi.surveys.slm.SlmWarmup
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "LiteRtLmRepository"

/**
 * Repository implementation backed by LiteRtLM (streaming).
 *
 * Contract:
 * - buildPrompt(userPrompt): string composition only (no IO).
 * - request(prompt): suspend; ensures model is initialized then returns Flow<String> deltas.
 */
class LiteRtLmRepository(
    context: Context,
    private val configAssetName: String = "survey.yaml",
    private val fallbackModelName: String = "Gemma3n4B",
    private val fallbackModelFileName: String = "Gemma3n4B.litertlm",
    private val resetConversationEachRequest: Boolean = true
) : Repository {

    private val appContext: Context = context.applicationContext
    private val cachedConfig = AtomicReference<com.negi.surveys.config.SurveyConfig?>(null)

    /**
     * Model-not-ready error used to produce a stable errorToken in FlowTextCollector.
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
        if (!file.exists() || file.length() <= 0L) {
            throw ModelNotReadyException("Model file missing or empty: ${file.name}")
        }

        // Split warmup design:
        // - Prefetch may already be running/finished from Home/SurveyStart.
        // - Compile is expected right before Question usage; we enforce a barrier here as well.
        SlmWarmup.startCompileIfConfigured(appContext)
        awaitCompileBarrierBestEffort()

        val model = buildModel(file)
        awaitInitializedModel(model)

        if (resetConversationEachRequest) {
            LiteRtLM.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemMessage = null,
                tools = emptyList()
            )
        }

        return callbackFlow {
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)

            fun closeOnce() {
                if (closed.compareAndSet(false, true)) {
                    runCatching { close() }
                }
            }

            LiteRtLM.runInference(
                model = model,
                input = p,
                resultListener = { delta, done ->
                    // IMPORTANT:
                    // Some SDK builds may emit a final delta together with done=true.
                    // Emit delta first, then close.
                    if (delta.isNotEmpty()) {
                        trySend(delta).isSuccess
                    }
                    if (done) {
                        closeOnce()
                    }
                },
                cleanUpListener = {
                    // Native termination safe point.
                    closeOnce()
                },
                onError = { msg ->
                    if (closed.compareAndSet(false, true)) {
                        close(IllegalStateException(msg))
                    }
                },
                images = emptyList(),
                audioClips = emptyList(),
                notifyCancelToOnError = true
            )

            awaitClose {
                runCatching { LiteRtLM.cancel(model) }
            }
        }
    }

    private fun getConfigBestEffort(): com.negi.surveys.config.SurveyConfig? {
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
        val fileName = cfg?.modelDefaults?.defaultFileName?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackModelFileName
        return File(appContext.filesDir, fileName)
    }

    private fun buildModel(file: File): Model {
        val cfg = getConfigBestEffort()
        val modelName = cfg?.modelDefaults?.modelName?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackModelName

        val accel = cfg?.slm?.accelerator?.trim()
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

    private suspend fun awaitInitializedModel(model: Model) {
        withContext(Dispatchers.Default) {
            runCatching {
                LiteRtLM.setApplicationContext(appContext)
            }

            AppLog.d(TAG, "initializeIfNeeded: model='${model.name}' file='${File(model.taskPath).name}'")
            LiteRtLM.initializeIfNeeded(
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
     * Best-effort barrier: waits until compile state reaches a terminal state.
     *
     * Note:
     * - If compile warmup is skipped/failed, we still proceed because initializeIfNeeded()
     *   is the real source of truth (it may still succeed).
     */
    private suspend fun awaitCompileBarrierBestEffort() {
        val terminal = SlmWarmup.compileState.first { st ->
            st is SlmWarmup.CompileState.Compiled ||
                    st is SlmWarmup.CompileState.Failed ||
                    st is SlmWarmup.CompileState.Cancelled ||
                    st is SlmWarmup.CompileState.SkippedNotConfigured
        }

        when (terminal) {
            is SlmWarmup.CompileState.Compiled -> {
                AppLog.d(TAG, "compile barrier: compiled in ${terminal.elapsedMs}ms")
            }
            is SlmWarmup.CompileState.Failed -> {
                AppLog.d(TAG, "compile barrier: failed: ${terminal.message} elapsed=${terminal.elapsedMs}ms")
            }
            is SlmWarmup.CompileState.Cancelled -> {
                AppLog.d(TAG, "compile barrier: cancelled elapsed=${terminal.elapsedMs}ms")
            }
            is SlmWarmup.CompileState.SkippedNotConfigured -> {
                AppLog.d(TAG, "compile barrier: skipped: ${terminal.reason} elapsed=${terminal.elapsedMs}ms")
            }
            else -> Unit
        }
    }

    companion object {
        private const val DEFAULT_WARMUP_CONFIG_ASSET = "survey.yaml"
        private const val DEFAULT_WARMUP_MODEL_NAME = "Gemma3n4B"

        /**
         * Stable reflection entrypoint for [SlmWarmup].
         *
         * Design:
         * - This intentionally performs a best-effort initialization via LiteRtLM.initializeIfNeeded().
         * - The goal is to front-load delegate/shader/kernel initialization before the first real request.
         *
         * Notes:
         * - This method must be non-suspending to be callable from reflection.
         * - It is expected to run on a background thread (SlmWarmup does that).
         */
        @JvmStatic
        suspend fun runCompileWarmup(context: Context, file: File) {
            val appContext = context.applicationContext

            val cfg = runCatching {
                SurveyConfigLoader.fromAssetsValidated(appContext, DEFAULT_WARMUP_CONFIG_ASSET)
            }.getOrNull()

            val modelName = cfg?.modelDefaults?.modelName?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_WARMUP_MODEL_NAME

            val accel = cfg?.slm?.accelerator?.trim()
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

            val model = Model(
                name = modelName,
                taskPath = file.absolutePath,
                config = config
            )

            runCatching { LiteRtLM.setApplicationContext(appContext) }

            AppLog.d(TAG, "runCompileWarmup: initializeIfNeeded model='${model.name}' file='${file.name}'")
            runCatching {
                LiteRtLM.initializeIfNeeded(
                    context = appContext,
                    model = model,
                    supportImage = false,
                    supportAudio = false,
                    systemMessage = null,
                    tools = emptyList()
                )
            }.onFailure { t ->
                // Best-effort warmup must never crash the process.
                AppLog.d(TAG, "runCompileWarmup: failed: ${t.javaClass.simpleName}(${t.message})")
            }
        }

        /**
         * Compatibility alias for older reflection method lists.
         */
        @JvmStatic
        suspend fun compileWarmup(context: Context, file: File) {
            runCompileWarmup(context, file)
        }
    }
}