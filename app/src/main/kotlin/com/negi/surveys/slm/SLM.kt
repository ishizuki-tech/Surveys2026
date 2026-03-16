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
 *
 *  Update (2026-03-14):
 *   - Remove raw prompt/token logging from normal runtime paths.
 *   - Make release logging metadata-only.
 *   - Avoid exception.message in logs and callback error strings.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.surveys.slm

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Message
import com.negi.surveys.BuildConfig
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.slm.liteRT.LiteRtLM
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Compatibility facade that forwards calls to LiteRtLM directly.
 *
 * Responsibilities:
 * - Keep call sites stable (SLM.* API surface).
 * - Avoid reflection to prevent signature drift false negatives.
 * - Provide consistent metadata-only logging for synchronous failures.
 * - Guard callbacks to avoid double delivery in mixed sync/async failure modes.
 *
 * Privacy:
 * - Never log raw prompt text, raw user answers, raw partial tokens, or raw model output.
 * - Never log exception.message because runtime/library errors may contain sensitive content.
 *
 * Non-goals:
 * - Do not re-implement LiteRtLM session management or concurrency control.
 * - Assume LiteRtLM enforces "single active stream per model key" and internal cancellation rules.
 */
object SLM {

    private const val TAG: String = "SLM"

    /**
     * Debug gate for metadata-only diagnostic logs.
     *
     * Notes:
     * - This must never enable raw prompt or raw token logging.
     * - Keep release builds quiet and safe by default.
     */
    private val DEBUG_SLM: Boolean = BuildConfig.DEBUG

    // ---------------------------------------------------------------------
    // Logging helpers
    // ---------------------------------------------------------------------

    private inline fun i(msg: () -> String) {
        SafeLog.i(TAG, msg())
    }

    private inline fun d(msg: () -> String) {
        if (DEBUG_SLM) SafeLog.d(TAG, msg())
    }

    private inline fun w(t: Throwable? = null, msg: () -> String) {
        if (t != null) {
            SafeLog.w(TAG, "${msg()} ${t.safeTypeAndHint()}")
        } else {
            SafeLog.w(TAG, msg())
        }
    }

    private inline fun e(t: Throwable? = null, msg: () -> String) {
        if (t != null) {
            SafeLog.e(TAG, "${msg()} ${t.safeTypeAndHint()}")
        } else {
            SafeLog.e(TAG, msg())
        }
    }

    /**
     * Returns a safe throwable summary without using exception.message.
     */
    private fun Throwable.safeTypeAndHint(): String {
        val top = stackTrace.firstOrNull()
        val hint = if (top != null) {
            "${top.className}.${top.methodName}:${top.lineNumber}"
        } else {
            "unknown"
        }
        return "type=${this::class.java.simpleName} at=$hint"
    }

    // ---------------------------------------------------------------------
    // Callback guards
    // ---------------------------------------------------------------------

    /**
     * Wrap a callback so it can be invoked at most once.
     *
     * Why:
     * - The underlying runtime may call callbacks asynchronously.
     * - This facade may also invoke callbacks on synchronous failure paths.
     * - Without a guard, it is easy to end up with double delivery and UI/state corruption.
     */
    private inline fun once0(crossinline block: () -> Unit): () -> Unit {
        val fired = AtomicBoolean(false)
        return { if (fired.compareAndSet(false, true)) block() }
    }

    /** See [once0]. */
    private inline fun <T> once1(crossinline block: (T) -> Unit): (T) -> Unit {
        val fired = AtomicBoolean(false)
        return { v -> if (fired.compareAndSet(false, true)) block(v) }
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Streaming inference callback.
     *
     * @param partialResult Partial text accumulated so far (implementation-defined).
     * @param done True when generation is logically completed (end-of-turn).
     *
     * Important:
     * - Treat this as a UI streaming signal, not as an exact token boundary guarantee.
     */
    fun interface ResultListener {
        operator fun invoke(partialResult: String, done: Boolean)
    }

    /**
     * Cleanup callback invoked after an inference session ends (success/failure/cancel).
     *
     * Important:
     * - Callers commonly rely on this to release UI locks/spinners.
     * - We therefore guard for "at most once" delivery in this facade.
     */
    fun interface CleanUpListener {
        operator fun invoke()
    }

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
     * - If LiteRtLM.initialize throws synchronously, we catch and notify [onDone]
     *   with a stable metadata-only error token.
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
            "initialize: model='${model.name}' " +
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
            w(t) { "initialize: failed" }
            onDoneOnce("error:${t::class.java.simpleName}")
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
            w(t) { "initializeIfNeeded: failed" }
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
            w(t) { "resetConversation: failed" }
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
            w(t) { "resetConversationAndAwait: failed" }
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
            w(t) { "cleanUp: failed" }
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
            w(t) { "forceCleanUp: failed" }
            cleanUp(model = model, onDone = onDoneOnce)
        }
    }

    /**
     * Start streaming inference.
     *
     * Failure model:
     * - If LiteRtLM.runInference throws synchronously, we:
     *   1) log metadata only
     *   2) call [onError]
     *   3) call [cleanUpListener]
     *
     * Callback safety:
     * - [cleanUpListener] is guarded to "at most once" to avoid double unlock/unblock bugs.
     * - [onError] is guarded to avoid duplicate UI error surfaces across sync/async paths.
     *
     * Privacy:
     * - Never log raw input text or raw partial model output here.
     * - Only log metadata such as lengths/counts and lifecycle markers.
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
        val cleanUpOnce = once0 { cleanUpListener() }
        val onErrorOnce = once1(onError)

        d {
            "runInference: model='${model.name}' textLen=${input.length} " +
                    "images=${images.size} audio=${audioClips.size} notifyCancel=$notifyCancelToOnError"
        }

        i {
            "inference:start model='${model.name}' textLen=${input.length} " +
                    "images=${images.size} audio=${audioClips.size}"
        }

        runCatching {
            LiteRtLM.runInference(
                model = model,
                input = input,
                resultListener = { partialResult, done ->
                    if (DEBUG_SLM) {
                        d {
                            "inference:partial model='${model.name}' done=$done " +
                                    "partialLen=${partialResult.length}"
                        }
                    }
                    resultListener(partialResult, done)
                },
                cleanUpListener = {
                    i { "inference:cleanup model='${model.name}'" }
                    cleanUpOnce()
                },
                onError = { message ->
                    val safeToken = message.trim().ifBlank { "unknown" }
                        .take(64)
                        .replace(Regex("[^A-Za-z0-9_:\\-.]"), "_")
                    w { "inference:error model='${model.name}' token=$safeToken" }
                    onErrorOnce(message)
                },
                images = images,
                audioClips = audioClips,
                notifyCancelToOnError = notifyCancelToOnError,
            )
        }.onFailure { t ->
            e(t) { "runInference: failed model='${model.name}'" }
            onErrorOnce("runInference_failed:${t::class.java.simpleName}")
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
            .onFailure { t -> w(t) { "cancel: failed" } }
    }
}