/*
 * =====================================================================
 *  IshizukiTech LLC — LiteRtLM Integration
 *  ---------------------------------------------------------------------
 *  File: LiteRtLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.surveys.slm.liteRT

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Message
import com.negi.surveys.slm.Model
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LiteRT-LM integration facade.
 *
 * Public surface:
 * - initialize / initializeIfNeeded
 * - runInference / generateText / cancel
 * - resetConversation / resetConversationAndAwait
 * - cleanUp / forceCleanUp
 *
 * Internals are delegated to:
 * - LiteRtLmInitCoordinator
 * - LiteRtLmSessionManager
 * - LiteRtLmRunController
 * - LiteRtLmTextDelta
 * - LiteRtLmLogging
 */
object LiteRtLM {

    /**
     * Serialize initializeIfNeeded() and generateText().
     *
     * Rationale:
     * - These APIs commonly run "setup + inference" sequences that should not overlap to avoid
     *   surprising resets/cancels across unrelated call sites.
     */
    private val apiMutex: Mutex = Mutex()

    /**
     * Internal IO scope used for fire-and-forget tasks (reset / deferred work).
     *
     * Note:
     * - This scope is intentionally process-lifetime; tasks are best-effort and should be small.
     * - Using SupervisorJob prevents one failing child from cancelling unrelated children.
     */
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Hard timeout for generateText() await to prevent indefinite hangs when the underlying
     * streaming layer fails to deliver done/cleanup/error callbacks.
     */
    private const val DEFAULT_GENERATE_TIMEOUT_MS: Long = 60_000L

    /** Busy flag used only by generateText() (suspend API). */
    private val busy: AtomicBoolean = AtomicBoolean(false)

    /** Best-effort tracking of which key currently owns generateText(). */
    private val busyKey: AtomicReference<String?> = AtomicReference(null)

    /** Stored application context for best-effort auto re-init inside runInference(). */
    private val appContextRef: AtomicReference<Context?> = AtomicReference(null)

    /** Allow host app to set context early. */
    fun setApplicationContext(context: Context) {
        appContextRef.set(context.applicationContext)
    }

    /** Returns true when a generateText call is currently in progress. */
    fun isBusy(): Boolean = busy.get()

    /**
     * Returns true when the given model is currently "busy":
     * - streaming is active for the model key, OR
     * - initialization is in flight for the model key, OR
     * - generateText() is in progress for the same model key
     */
    fun isBusy(model: Model): Boolean {
        val key = LiteRtLmKeys.runtimeKey(model)
        val genBusyForKey = busy.get() && busyKey.get() == key
        val streamBusyForKey = LiteRtLmRunController.isRunOccupiedKey(key)
        val initBusyForKey = LiteRtLmInitCoordinator.isInitInFlight(key)
        return genBusyForKey || streamBusyForKey || initBusyForKey
    }

    /**
     * Returns true when any runtime key with the given model name is active.
     *
     * Warning:
     * - Model name alone is not a unique identifier (path is part of the runtime key).
     */
    fun isBusy(modelName: String): Boolean {
        val n = modelName.trim()
        if (n.isEmpty()) {
            return busy.get() || LiteRtLmRunController.anyRunOccupied()
        }

        val prefix = "$n|"
        val genBusyForName = busy.get() && (busyKey.get()?.startsWith(prefix) == true)
        val streamBusyForName = LiteRtLmRunController.anyRunOccupiedForPrefix(prefix)
        val initBusyForName = LiteRtLmInitCoordinator.isAnyInitInFlightForModelName(n)
        return genBusyForName || streamBusyForName || initBusyForName
    }

    /**
     * Initialize LiteRT-LM Engine + Conversation (async).
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
        setApplicationContext(context)
        LiteRtLmInitCoordinator.initialize(
            context = context,
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            onDone = onDone,
            systemMessage = systemMessage,
            tools = tools,
        )
    }

    /**
     * Suspend-style initializer.
     */
    suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        setApplicationContext(context)

        apiMutex.withLock {
            LiteRtLmInitCoordinator.initializeIfNeeded(
                context = context,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                systemMessage = systemMessage,
                tools = tools,
            )
        }
    }

    /**
     * Low-level callback-based streaming API.
     */
    fun runInference(
        model: Model,
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        notifyCancelToOnError: Boolean = false,
    ) {
        LiteRtLmRunController.runInference(
            model = model,
            input = input,
            resultListener = resultListener,
            cleanUpListener = cleanUpListener,
            onError = onError,
            images = images,
            audioClips = audioClips,
            notifyCancelToOnError = notifyCancelToOnError,
            appContextProvider = { appContextRef.get() },
        )
    }

    /**
     * High-level suspend API:
     * - Serializes calls via apiMutex.
     * - Uses runInference internally and returns full aggregated text.
     *
     * Notes:
     * - `partial` is treated as a delta chunk (append-only). If the underlying implementation
     *   ever switches to "full snapshot", this method must be updated accordingly.
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
    ): String = apiMutex.withLock {
        val key = LiteRtLmKeys.runtimeKey(model)

        LiteRtLmRunController.markUsed(key)
        LiteRtLmSessionManager.cancelScheduledCleanup(key, "generateText")

        if (!busy.compareAndSet(false, true)) {
            throw IllegalStateException("LiteRT-LM is already busy with another request.")
        }

        busyKey.set(key)

        try {
            val buffer = StringBuilder()
            val doneSignal = CompletableDeferred<String>()

            fun completeIfNeeded(value: String) {
                if (!doneSignal.isCompleted) {
                    doneSignal.complete(value)
                }
            }

            fun failIfNeeded(t: Throwable) {
                if (!doneSignal.isCompleted) {
                    doneSignal.completeExceptionally(t)
                }
            }

            runInference(
                model = model,
                input = input,
                images = images,
                audioClips = audioClips,
                resultListener = { partial, done ->
                    if (partial.isNotEmpty()) {
                        buffer.append(partial)
                        runCatching { onPartial(partial) }
                    }
                    if (done) completeIfNeeded(buffer.toString())
                },
                cleanUpListener = {
                    // Some runtimes may signal cleanup even when "done" was not delivered reliably.
                    // Complete with the best-effort buffered content to avoid hanging awaiters.
                    completeIfNeeded(buffer.toString())
                },
                onError = { message ->
                    if (isCancelledMessage(message)) {
                        failIfNeeded(CancellationException("Cancelled"))
                    } else {
                        failIfNeeded(IllegalStateException("LiteRT-LM generation error: $message"))
                    }
                },
                notifyCancelToOnError = true,
            )

            val result: String? = try {
                withTimeoutOrNull(DEFAULT_GENERATE_TIMEOUT_MS) { doneSignal.await() }
            } catch (e: CancellationException) {
                cancel(model)
                throw e
            }

            if (result == null) {
                cancel(model)
                throw IllegalStateException(
                    "LiteRT-LM generateText timed out after ${DEFAULT_GENERATE_TIMEOUT_MS}ms (key=$key)."
                )
            }

            result
        } finally {
            busyKey.set(null)
            busy.set(false)
        }
    }

    /**
     * Best-effort cancellation.
     */
    fun cancel(model: Model) {
        val key = LiteRtLmKeys.runtimeKey(model)
        LiteRtLmRunController.cancel(key)
    }

    /**
     * Request a deferred idle cleanup.
     */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val key = LiteRtLmKeys.runtimeKey(model)
        LiteRtLmRunController.markUsed(key)
        LiteRtLmSessionManager.cleanUp(key) { onDone() }
    }

    /**
     * Force immediate teardown (best-effort).
     */
    fun forceCleanUp(model: Model, onDone: () -> Unit) {
        val key = LiteRtLmKeys.runtimeKey(model)
        LiteRtLmRunController.markUsed(key)
        LiteRtLmSessionManager.forceCleanUp(key) { onDone() }
    }

    /**
     * Reset conversation while reusing the existing Engine.
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = LiteRtLmKeys.runtimeKey(model)
        LiteRtLmRunController.markUsed(key)
        LiteRtLmSessionManager.cancelScheduledCleanup(key, "resetConversation")

        val task: suspend () -> Unit = {
            runCatching {
                LiteRtLmSessionManager.resetConversationInternal(
                    key = key,
                    model = model,
                    supportImage = supportImage,
                    supportAudio = supportAudio,
                    systemMessage = systemMessage,
                    tools = tools,
                    reason = "resetConversation",
                    appContext = appContextRef.get(),
                )
            }
        }

        // Defer if occupied, else run now.
        if (LiteRtLmRunController.isRunOccupiedKey(key)) {
            LiteRtLmRunController.deferAfterStream(key) {
                ioScope.launch { task() }
            }
            return
        }

        ioScope.launch { task() }
    }

    /**
     * Reset conversation and await completion.
     */
    suspend fun resetConversationAndAwait(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
        timeoutMs: Long = 5_000L,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = LiteRtLmKeys.runtimeKey(model)

        LiteRtLmRunController.markUsed(key)
        LiteRtLmSessionManager.cancelScheduledCleanup(key, "resetConversationAndAwait")

        runCatching { LiteRtLmInitCoordinator.awaitInitIfInFlight(key, reason = "resetConversationAndAwait") }
            .onFailure { return@withContext false }

        if (!LiteRtLmSessionManager.hasInstance(key)) return@withContext false

        val done = CompletableDeferred<Boolean>()

        suspend fun runReset(): Boolean {
            return runCatching {
                LiteRtLmSessionManager.resetConversationInternal(
                    key = key,
                    model = model,
                    supportImage = supportImage,
                    supportAudio = supportAudio,
                    systemMessage = systemMessage,
                    tools = tools,
                    reason = "resetConversationAndAwait",
                    appContext = appContextRef.get(),
                )
                true
            }.getOrDefault(false)
        }

        if (LiteRtLmRunController.isRunOccupiedKey(key)) {
            LiteRtLmRunController.deferAfterStream(key) {
                ioScope.launch { done.complete(runReset()) }
            }
        } else {
            done.complete(runReset())
        }

        withTimeoutOrNull(timeoutMs) { done.await() } ?: false
    }

    /**
     * Returns true when the given message likely indicates cancellation.
     *
     * Notes:
     * - Different runtimes may use "Canceled" or "Cancelled" and may append details.
     */
    private fun isCancelledMessage(message: String): Boolean {
        val m = message.trim()
        if (m.isEmpty()) return false
        val lower = m.lowercase()
        return lower == "cancelled" ||
                lower == "canceled" ||
                lower.startsWith("cancelled") ||
                lower.startsWith("canceled") ||
                lower.contains("cancel")
    }
}