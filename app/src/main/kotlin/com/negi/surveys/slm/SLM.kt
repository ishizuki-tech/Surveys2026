/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Compatibility facade over LiteRtLM (NO MediaPipe).
 *
 *  Fix (2026-02-28):
 *   - Remove reflective invocation.
 *   - Call LiteRtLM APIs directly to avoid “API not present / signature mismatch” false negatives.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.surveys.slm

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Message
import com.negi.surveys.BuildConfig
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.logging.AppLog
import com.negi.surveys.logging.SafeLog
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

private const val TAG = "SLM"
private val DEBUG_SLM: Boolean = BuildConfig.DEBUG

private inline fun d(msg: () -> String) {
    if (DEBUG_SLM) AppLog.d(TAG, msg())
}

private inline fun w(t: Throwable? = null, msg: () -> String) {
    if (t != null) AppLog.w(TAG, msg(), t) else AppLog.w(TAG, msg())
}

/**
 * Wrap a callback so it can be invoked at most once.
 *
 * Why:
 * - The underlying runtime may call callbacks asynchronously.
 * - This facade may also invoke callbacks on synchronous failure paths.
 * - Without a guard, it is easy to end up with double delivery and UI/state corruption.
 *
 * Notes:
 * - This only guarantees "at most once" for this wrapper instance.
 * - It does not guarantee ordering relative to other callbacks.
 */
private inline fun once0(crossinline block: () -> Unit): () -> Unit {
    val fired = AtomicBoolean(false)
    return {
        if (fired.compareAndSet(false, true)) {
            block()
        }
    }
}

/** See [once0]. */
private inline fun <T> once1(crossinline block: (T) -> Unit): (T) -> Unit {
    val fired = AtomicBoolean(false)
    return { v ->
        if (fired.compareAndSet(false, true)) {
            block(v)
        }
    }
}

/**
 * Compute accelerator selection used by the model runtime.
 *
 * Implementation detail:
 * - The config stores accelerator as a label string for backward compatibility with existing
 *   config formats and any persisted data.
 */
enum class Accelerator(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
}

/**
 * Model configuration keys used by the app layer.
 *
 * Stability contract:
 * - Keep keys stable because configs may be persisted and/or supplied via remote config.
 * - Treat unknown/missing values as "use defaults".
 */
enum class ConfigKey {
    MAX_TOKENS,
    TOP_K,
    TOP_P,
    TEMPERATURE,
    ACCELERATOR,
}

/**
 * Streaming inference callback.
 *
 * @param partialResult Partial text accumulated so far (implementation-defined).
 * @param done True when generation is logically completed (end-of-turn).
 *
 * Important:
 * - Callers should treat this as a UI streaming signal, not as an exact token boundary guarantee.
 */
typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

/**
 * Cleanup callback invoked after an inference session ends (success/failure/cancel).
 *
 * Important:
 * - Callers commonly rely on this to release UI locks/spinners.
 * - We therefore guard for "at most once" delivery in this facade.
 */
typealias CleanUpListener = () -> Unit

private const val DEFAULT_MAX_TOKENS = 4096
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

/**
 * Absolute hard limits enforced by this app layer.
 *
 * Rationale:
 * - Prevent pathological configs from causing long stalls or memory pressure.
 * - Provide a stable contract regardless of what upstream runtime accepts.
 */
private const val ABS_MAX_TOKENS = 4096
private const val ABS_MAX_TEMPERATURE = 2.0f
private const val ABS_MAX_TOP_K = 2048

/**
 * Model descriptor that the app passes around.
 *
 * @property name Stable identifier used by the runtime.
 * @property taskPath Local path to the .task file.
 * @property config Optional per-model overrides.
 *
 * Config typing note:
 * - Values may originate from JSON / remote config / persisted storage,
 *   so they can be Number or String.
 */
data class Model(
    val name: String,
    val taskPath: String,
    val config: Map<ConfigKey, Any> = emptyMap(),
) {
    fun getPath(): String = taskPath

    fun getIntConfigValue(key: ConfigKey, default: Int): Int =
        config[key].toIntOrDefault(default)

    fun getFloatConfigValue(key: ConfigKey, default: Float): Float =
        config[key].toFloatOrDefault(default)

    fun getStringConfigValue(key: ConfigKey, default: String): String =
        (config[key] as? String) ?: default
}

/**
 * Convert a mixed-typed config value to Int safely.
 *
 * This intentionally does NOT throw. Any unexpected type becomes default.
 */
private fun Any?.toIntOrDefault(default: Int): Int = when (this) {
    is Number -> this.toInt()
    is String -> this.trim().toIntOrNull() ?: default
    else -> default
}

/**
 * Convert a mixed-typed config value to Float safely.
 *
 * This intentionally does NOT throw. Any unexpected type becomes default.
 */
private fun Any?.toFloatOrDefault(default: Float): Float = when (this) {
    is Number -> this.toFloat()
    is String -> this.trim().toFloatOrNull() ?: default
    else -> default
}

/**
 * Normalize accelerator label.
 *
 * Policy:
 * - Default to GPU when empty/unknown to preserve legacy behavior.
 * - Keep exact labels "CPU"/"GPU" because downstream may compare by label.
 */
private fun parseAcceleratorLabel(raw: String?): String {
    val s = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
    return when (s) {
        Accelerator.CPU.label -> Accelerator.CPU.label
        Accelerator.GPU.label -> Accelerator.GPU.label
        "" -> Accelerator.GPU.label
        else -> Accelerator.GPU.label
    }
}

/**
 * Normalize numeric config values to concrete Int/Float types.
 *
 * Why:
 * - Remote config / JSON / storage may surface numbers as String, Double, Long, etc.
 * - Downstream code must not depend on ambiguous runtime types.
 *
 * Post-condition:
 * - MAX_TOKENS, TOP_K are Int
 * - TOP_P, TEMPERATURE are Float
 * - ACCELERATOR is a valid label String
 */
private fun normalizeNumberTypes(m: MutableMap<ConfigKey, Any>) {
    m[ConfigKey.MAX_TOKENS] = m[ConfigKey.MAX_TOKENS].toIntOrDefault(DEFAULT_MAX_TOKENS)
    m[ConfigKey.TOP_K] = m[ConfigKey.TOP_K].toIntOrDefault(DEFAULT_TOP_K)
    m[ConfigKey.TOP_P] = m[ConfigKey.TOP_P].toFloatOrDefault(DEFAULT_TOP_P)
    m[ConfigKey.TEMPERATURE] = m[ConfigKey.TEMPERATURE].toFloatOrDefault(DEFAULT_TEMPERATURE)

    val accelRaw = m[ConfigKey.ACCELERATOR] as? String
    m[ConfigKey.ACCELERATOR] = parseAcceleratorLabel(accelRaw)
}

/**
 * Clamp normalized config values to safe runtime ranges.
 *
 * Safety note:
 * - This function is designed to be safe even if normalization was skipped,
 *   by using safe conversions again.
 */
private fun clampRanges(m: MutableMap<ConfigKey, Any>) {
    val maxTokens = m[ConfigKey.MAX_TOKENS].toIntOrDefault(DEFAULT_MAX_TOKENS)
        .coerceIn(1, ABS_MAX_TOKENS)
    val topK = m[ConfigKey.TOP_K].toIntOrDefault(DEFAULT_TOP_K)
        .coerceIn(1, ABS_MAX_TOP_K)
    val topP = m[ConfigKey.TOP_P].toFloatOrDefault(DEFAULT_TOP_P)
        .coerceIn(0f, 1f)
    val temp = m[ConfigKey.TEMPERATURE].toFloatOrDefault(DEFAULT_TEMPERATURE)
        .coerceIn(0f, ABS_MAX_TEMPERATURE)

    m[ConfigKey.MAX_TOKENS] = maxTokens
    m[ConfigKey.TOP_K] = topK
    m[ConfigKey.TOP_P] = topP
    m[ConfigKey.TEMPERATURE] = temp
}

/**
 * Build model config from [SurveyConfig.SlmMeta].
 *
 * Output invariants:
 * - MAX_TOKENS/TOP_K are Int
 * - TOP_P/TEMPERATURE are Float
 * - ACCELERATOR is a String label ("CPU"/"GPU")
 *
 * Logging:
 * - Logs normalized values for quick diagnosis of remote-config drift.
 */
fun buildModelConfig(slm: SurveyConfig.SlmMeta): MutableMap<ConfigKey, Any> {
    val out: MutableMap<ConfigKey, Any> = mutableMapOf(
        ConfigKey.ACCELERATOR to parseAcceleratorLabel(slm.accelerator ?: Accelerator.GPU.label),
        ConfigKey.MAX_TOKENS to (slm.maxTokens ?: DEFAULT_MAX_TOKENS),
        ConfigKey.TOP_K to (slm.topK ?: DEFAULT_TOP_K),
        ConfigKey.TOP_P to (slm.topP ?: DEFAULT_TOP_P),
        ConfigKey.TEMPERATURE to (slm.temperature ?: DEFAULT_TEMPERATURE),
    )

    normalizeNumberTypes(out)
    clampRanges(out)

    d {
        "buildModelConfig: accel=${out[ConfigKey.ACCELERATOR]} " +
                "maxTokens=${out[ConfigKey.MAX_TOKENS]} topK=${out[ConfigKey.TOP_K]} " +
                "topP=${out[ConfigKey.TOP_P]} temp=${out[ConfigKey.TEMPERATURE]}"
    }
    return out
}

/**
 * Build a safe config when [SurveyConfig.SlmMeta] is absent.
 *
 * Design choice:
 * - Even if [slm] is non-null, we still re-run normalization + clamping to guarantee invariants
 *   if the upstream builder changes in the future.
 */
internal fun buildModelConfigSafe(slm: SurveyConfig.SlmMeta?): MutableMap<ConfigKey, Any> {
    val out: MutableMap<ConfigKey, Any> = if (slm == null) {
        mutableMapOf(
            ConfigKey.ACCELERATOR to Accelerator.GPU.label,
            ConfigKey.MAX_TOKENS to DEFAULT_MAX_TOKENS,
            ConfigKey.TOP_K to DEFAULT_TOP_K,
            ConfigKey.TOP_P to DEFAULT_TOP_P,
            ConfigKey.TEMPERATURE to DEFAULT_TEMPERATURE,
        )
    } else {
        buildModelConfig(slm)
    }

    normalizeNumberTypes(out)
    clampRanges(out)
    return out
}

/**
 * Compatibility facade that forwards calls to LiteRtLM directly.
 *
 * Responsibilities of this facade:
 * - Keep call sites stable (SLM.* API surface).
 * - Avoid reflection to prevent signature drift false negatives.
 * - Provide consistent logging for synchronous failures.
 * - Guard callbacks to avoid double delivery in mixed sync/async failure modes.
 *
 * Non-goals:
 * - Do not re-implement LiteRtLM session management or concurrency control.
 * - Assume LiteRtLM enforces "single active stream per model key" and internal cancellation rules.
 */
object SLM {

    fun setApplicationContext(context: Context) {
        d { "setApplicationContext" }
        LiteRtLM.setApplicationContext(context)
    }

    fun isBusy(model: Model): Boolean = LiteRtLM.isBusy(model)

    fun isBusy(modelName: String): Boolean = LiteRtLM.isBusy(modelName)

    /**
     * Initialize a model runtime.
     *
     * Failure model:
     * - If LiteRtLM.initialize throws synchronously, we catch and notify [onDone] with an error string.
     * - If LiteRtLM reports errors asynchronously, it is expected to signal via its own callbacks.
     *
     * Callback safety:
     * - [onDone] is guarded to be invoked at most once by this facade.
     */
    fun initialize(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onDone: (String) -> Unit,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val onDoneOnce = once1(onDone)

        d {
            "initialize: model='${model.name}' path='${model.taskPath}' " +
                    "image=$supportImage audio=$supportAudio tools=${tools.size}"
        }

        runCatching {
            LiteRtLM.initialize(
                context = context,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                onDone = onDoneOnce,
                systemMessage = systemMessage,
                tools = tools,
            )
        }.onFailure { t ->
            w(t) { "initialize: failed err=${t.javaClass.simpleName}(${t.message})" }
            // NOTE: This string may surface to UI depending on call sites. Keep behavior,
            // but consider mapping to a user-friendly message at the UI boundary.
            onDoneOnce("error: ${t.javaClass.simpleName}(${t.message})")
        }
    }

    /**
     * Suspend initializer used when callers need structured cancellation/propagation.
     *
     * Cancellation:
     * - CancellationException is rethrown untouched to preserve coroutine semantics.
     */
    suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        d {
            "initializeIfNeeded: model='${model.name}' " +
                    "image=$supportImage audio=$supportAudio tools=${tools.size}"
        }

        try {
            LiteRtLM.initializeIfNeeded(
                context = context,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                systemMessage = systemMessage,
                tools = tools,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            w(t) { "initializeIfNeeded: failed err=${t.javaClass.simpleName}(${t.message})" }
            throw t
        }
    }

    /**
     * Fire-and-forget conversation reset.
     *
     * Ordering:
     * - This does not guarantee completion before subsequent calls.
     * - Use [resetConversationAndAwait] if strict sequencing is required.
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        d {
            "resetConversation: model='${model.name}' " +
                    "image=$supportImage audio=$supportAudio tools=${tools.size}"
        }

        runCatching {
            LiteRtLM.resetConversation(
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                systemMessage = systemMessage,
                tools = tools,
            )
        }.onFailure { t ->
            w(t) { "resetConversation: failed err=${t.javaClass.simpleName}(${t.message})" }
        }
    }

    /**
     * Reset conversation and await completion.
     *
     * Why:
     * - Some flows require "reset must finish BEFORE next user request is issued".
     *
     * @return true when reset completed successfully, false on skip/failure/timeout.
     */
    suspend fun resetConversationAndAwait(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
        timeoutMs: Long = 5_000L,
    ): Boolean {
        d {
            "resetConversationAndAwait: model='${model.name}' " +
                    "image=$supportImage audio=$supportAudio tools=${tools.size} timeoutMs=$timeoutMs"
        }

        return runCatching {
            LiteRtLM.resetConversationAndAwait(
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                systemMessage = systemMessage,
                tools = tools,
                timeoutMs = timeoutMs,
            )
        }.onFailure { t ->
            w(t) { "resetConversationAndAwait: failed err=${t.javaClass.simpleName}(${t.message})" }
        }.getOrDefault(false)
    }

    /**
     * Graceful cleanup.
     *
     * Callback safety:
     * - [onDone] is guarded to avoid double completion if LiteRtLM throws synchronously.
     */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val onDoneOnce = once0(onDone)

        d { "cleanUp: model='${model.name}'" }
        runCatching {
            LiteRtLM.cleanUp(model = model, onDone = onDoneOnce)
        }.onFailure { t ->
            w(t) { "cleanUp: failed err=${t.javaClass.simpleName}(${t.message})" }
            onDoneOnce()
        }
    }

    /**
     * Force cleanup.
     *
     * Fallback:
     * - If force cleanup fails, we fall back to graceful cleanup.
     *
     * Callback safety:
     * - [onDone] is guarded to avoid double completion across fallback paths.
     */
    fun forceCleanUp(model: Model, onDone: () -> Unit) {
        val onDoneOnce = once0(onDone)

        d { "forceCleanUp: model='${model.name}'" }
        runCatching {
            LiteRtLM.forceCleanUp(model = model, onDone = onDoneOnce)
        }.onFailure { t ->
            w(t) { "forceCleanUp: failed err=${t.javaClass.simpleName}(${t.message})" }
            cleanUp(model = model, onDone = onDoneOnce)
        }
    }

    /**
     * Start streaming inference.
     *
     * Failure model:
     * - If LiteRtLM.runInference throws synchronously, we:
     *   1) log
     *   2) call [onError]
     *   3) call [cleanUpListener]
     *
     * Callback safety:
     * - [cleanUpListener] is guarded to "at most once" to avoid double unlock/unblock bugs.
     *
     * Note:
     * - We intentionally do not guard [resultListener] here because it is expected to be invoked
     *   multiple times for streaming partials.
     */
    fun runInference(
        model: Model,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        notifyCancelToOnError: Boolean = false,
    ) {
        val cleanUpOnce = once0(cleanUpListener)

        d {
            "runInference: model='${model.name}' textLen=${input.length} " +
                    "images=${images.size} audio=${audioClips.size} notifyCancel=$notifyCancelToOnError"
        }

        SafeLog.i("SLM runInference", "input :: $input")

        runCatching {
            LiteRtLM.runInference(
                model = model,
                input = input,
                resultListener = { partialResult, done ->
                    SafeLog.i("SLM runInference", "done = $done | ResultListener::  $partialResult")
                    resultListener(partialResult,done)
                },
                cleanUpListener = {
                    SafeLog.i("SLM runInference", "cleanUpListener")
                    cleanUpOnce()
                },
                onError = { message ->
                    SafeLog.i("SLM runInference", "onError :: $message")
                    onError(message)
                },
                images = images,
                audioClips = audioClips,
                notifyCancelToOnError = notifyCancelToOnError,
            )
        }.onFailure { t ->
            w(t) { "runInference: failed err=${t.javaClass.simpleName}(${t.message})" }
            onError("runInference failed: ${t.javaClass.simpleName}(${t.message})")
            runCatching { cleanUpOnce() }
        }
    }

    /**
     * Convenience API for "generate full text" flows.
     *
     * Behavior:
     * - Delegates to LiteRtLM.generateText which is expected to handle streaming internally.
     *
     * Partial callback:
     * - [onPartial] may be called multiple times as text grows.
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
    ): String {
        d {
            "generateText: model='${model.name}' textLen=${input.length} " +
                    "images=${images.size} audio=${audioClips.size}"
        }

        return LiteRtLM.generateText(
            model = model,
            input = input,
            images = images,
            audioClips = audioClips,
            onPartial = onPartial,
        )
    }

    /**
     * Best-effort cancellation.
     *
     * Note:
     * - Cancellation is inherently racey. A late callback may still arrive depending on runtime.
     * - LiteRtLM is expected to suppress late callbacks using its own runId/token mechanism.
     */
    fun cancel(model: Model) {
        d { "cancel: model='${model.name}'" }
        runCatching { LiteRtLM.cancel(model) }
            .onFailure { t -> w(t) { "cancel: failed err=${t.javaClass.simpleName}(${t.message})" } }
    }
}