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
import com.negi.surveys.slm.SlmWarmup
import com.negi.surveys.slm.Model
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
            return callbackFlow {
                close()
            }
        }

        val file = resolveModelFile()
        if (!file.exists() || file.length() <= 0L) {
            throw ModelNotReadyException("Model file missing or empty: ${file.name}")
        }

        val warmModel: Model? = runCatching {
            // Prefer the warmup model to keep runtimeKey and config stable across the app.
            SlmWarmup.getInitializedModelOrNull()
                ?: SlmWarmup.awaitInitializedModel(appContext)
        }.getOrNull()

        val model = if (warmModel != null && warmModel.taskPath == file.absolutePath) {
            warmModel
        } else {
            buildModel(file)
        }

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
        withContext(Dispatchers.IO) {
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
}