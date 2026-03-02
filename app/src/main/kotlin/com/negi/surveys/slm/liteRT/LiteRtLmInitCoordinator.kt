/*
 * =====================================================================
 *  IshizukiTech LLC — LiteRtLM Integration
 *  ---------------------------------------------------------------------
 *  File: LiteRtLmInitCoordinator.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm.liteRT

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.negi.surveys.logging.AppLog
import com.negi.surveys.slm.Model
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Initialization coordinator for LiteRT-LM engine + conversation.
 *
 * Owns:
 * - initSignals / initInFlight / InitWaitProfile
 * - adaptive init await timeout
 * - serialization dir selection
 * - init build + engine fallback logic
 */
internal object LiteRtLmInitCoordinator {

    private const val ABS_MAX_NUM_TOKENS = 4096
    private const val DEFAULT_TOPK = 40
    private const val DEFAULT_TOPP = 0.9f
    private const val DEFAULT_TEMPERATURE = 0.7f

    private const val INIT_AWAIT_TIMEOUT_MS_DEFAULT = 90_000L
    private const val INIT_AWAIT_TIMEOUT_MS_CPU_COLD = 240_000L
    private const val INIT_AWAIT_TIMEOUT_MS_GPU_COLD = 300_000L

    private const val CLOSE_GRACE_MS = 5_000L
    private const val RETIRED_CLOSE_GRACE_MS = 1_500L

    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Per-key init completion signal.
     *
     * Contract:
     * - Completes with "" on success
     * - Completes with non-empty string on failure
     */
    private val initSignals: ConcurrentHashMap<String, CompletableDeferred<String>> = ConcurrentHashMap()

    private data class InitWaitProfile(
        val backend: Backend,
        val serializationDir: String?,
        val createdAtMs: Long,
    )

    private val initWaitProfiles: ConcurrentHashMap<String, InitWaitProfile> = ConcurrentHashMap()

    /** One-shot guard for best-effort native library loading. */
    private val nativeLoadOnce: AtomicBoolean = AtomicBoolean(false)

    internal fun isInitInFlight(key: String): Boolean = initInFlight.contains(key)

    internal fun isAnyInitInFlightForModelName(modelName: String): Boolean {
        val prefix = "$modelName|"
        return initInFlight.any { it.startsWith(prefix) }
    }

    internal suspend fun awaitInitIfInFlight(key: String, reason: String) {
        if (!initInFlight.contains(key)) return
        AppLog.d(LiteRtLmLogging.TAG, "Awaiting init in flight: key='$key' reason='$reason'")
        val err = awaitInitSignalAdaptive(key = key, reason = "awaitInitIfInFlight:$reason")
        if (err.isNotEmpty()) throw IllegalStateException("LiteRT-LM init-in-flight failed: $err")
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * Best-effort native library load to avoid load-order races.
     *
     * Note:
     * - Preferred fix is to load in Application.onCreate()/attachBaseContext().
     * - This is a safety net and should not crash if missing.
     */
    private fun ensureNativeLoadedBestEffort() {
        if (!nativeLoadOnce.compareAndSet(false, true)) return
        runCatching {
            System.loadLibrary("litertlm_jni")
            AppLog.d(LiteRtLmLogging.TAG, "System.loadLibrary(litertlm_jni) ok")
        }.onFailure { t ->
            AppLog.w(LiteRtLmLogging.TAG, "System.loadLibrary(litertlm_jni) failed (non-fatal): ${t.message}", t)
        }
    }

    /** Normalize accelerator string for stable backend selection. */
    private fun normalizedAccelerator(model: Model): String {
        return model.getStringConfigValue(Model.ConfigKey.ACCELERATOR, Model.Accelerator.GPU.label)
            .trim()
            .uppercase(Locale.ROOT)
            .ifBlank { Model.Accelerator.GPU.label }
    }

    /** Resolve preferred backend from model config. */
    private fun preferredBackend(model: Model): Backend {
        return when (normalizedAccelerator(model)) {
            Model.Accelerator.CPU.label -> Backend.CPU
            Model.Accelerator.GPU.label -> Backend.GPU
            else -> Backend.GPU
        }
    }

    private fun sanitizeTopK(k: Int): Int = k.coerceAtLeast(1)
    private fun sanitizeTopP(p: Float): Float = p.takeIf { it in 0f..1f } ?: DEFAULT_TOPP
    private fun sanitizeTemperature(t: Float): Float = t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    /** Heuristic default max tokens by model name. */
    private fun defaultMaxTokensForModel(modelName: String): Int {
        val n = modelName.lowercase(Locale.ROOT)
        return if (n.contains("functiongemma") || n.contains("270m") || n.contains("tinygarden")) 1024 else 4096
    }

    /** Get or create a per-key init signal (never returns a completed one). */
    private fun getOrCreateInitSignal(key: String): CompletableDeferred<String> {
        while (true) {
            val existing = initSignals[key]
            if (existing != null && !existing.isCompleted) return existing

            val created = CompletableDeferred<String>()
            val prev = initSignals.putIfAbsent(key, created)
            if (prev == null) return created

            if (prev.isCompleted) {
                val replaced = initSignals.replace(key, prev, created)
                if (replaced) return created
            } else {
                return prev
            }
        }
    }

    private fun completeInitSignal(signal: CompletableDeferred<String>, error: String) {
        if (!signal.isCompleted) signal.complete(error)
    }

    /**
     * Returns true if the serialization directory already has artifacts.
     *
     * Note:
     * - Conservative: any file presence indicates "warm-ish".
     */
    private fun isSerializationWarmBestEffort(serializationDir: String?): Boolean {
        if (serializationDir.isNullOrBlank()) return false
        val dir = runCatching { File(serializationDir) }.getOrNull() ?: return false
        if (!dir.exists() || !dir.isDirectory) return false
        return runCatching { dir.listFiles()?.isNotEmpty() == true }.getOrDefault(false)
    }

    private fun recommendedInitAwaitTimeoutMs(backend: Backend, serializationDir: String?): Long {
        val warm = isSerializationWarmBestEffort(serializationDir)
        if (warm) return INIT_AWAIT_TIMEOUT_MS_DEFAULT

        return when (backend) {
            Backend.GPU -> INIT_AWAIT_TIMEOUT_MS_GPU_COLD
            Backend.CPU -> INIT_AWAIT_TIMEOUT_MS_CPU_COLD
            else -> INIT_AWAIT_TIMEOUT_MS_DEFAULT
        }
    }

    private fun initAwaitTimeoutMsForKey(
        key: String,
        backendHint: Backend? = null,
        serializationDirHint: String? = null,
    ): Long {
        val p = initWaitProfiles[key]
        val backend = backendHint ?: p?.backend ?: Backend.GPU
        val dir = serializationDirHint ?: p?.serializationDir
        return recommendedInitAwaitTimeoutMs(backend, dir)
    }

    private suspend fun awaitInitSignalAdaptive(
        key: String,
        reason: String,
        backendHint: Backend? = null,
        serializationDirHint: String? = null,
    ): String {
        val signal = getOrCreateInitSignal(key)
        val timeoutMs = initAwaitTimeoutMsForKey(key, backendHint, serializationDirHint)
        val warm = isSerializationWarmBestEffort(serializationDirHint ?: initWaitProfiles[key]?.serializationDir)

        AppLog.d(
            LiteRtLmLogging.TAG,
            "Awaiting init: key='$key' timeoutMs=$timeoutMs warm=$warm backend=${backendHint ?: initWaitProfiles[key]?.backend} reason='$reason'"
        )

        return withTimeoutOrNull(timeoutMs) { signal.await() }
            ?: "Initialization timed out after ${timeoutMs}ms."
    }

    /**
     * Build conversation config for sampler + optional system/tools.
     */
    private fun buildConversationConfig(
        model: Model,
        systemMessage: Message?,
        tools: List<Any>,
    ): ConversationConfig {
        val topK = sanitizeTopK(model.getIntConfigValue(Model.ConfigKey.TOP_K, DEFAULT_TOPK))
        val topP = sanitizeTopP(model.getFloatConfigValue(Model.ConfigKey.TOP_P, DEFAULT_TOPP))
        val temperature = sanitizeTemperature(model.getFloatConfigValue(Model.ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

        return ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temperature.toDouble(),
            ),
            systemMessage = systemMessage,
            tools = tools,
        )
    }

    private fun sha256HexShort(input: String, chars: Int = 16): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString().take(chars.coerceIn(8, 64))
    }

    /**
     * Returns a persistent directory path for LiteRT-LM serialized/compiled artifacts.
     */
    private fun persistentLiteRtSerializationDir(
        context: Context,
        modelName: String,
        normalizedModelPath: String,
        backend: Backend,
        visionBackend: Backend?,
        audioBackend: Backend?,
    ): String {
        val base: File = runCatching {
            File(context.noBackupFilesDir, "litertlm_serialized")
        }.getOrElse {
            File(context.filesDir, "litertlm_serialized")
        }

        val modelFile = runCatching { File(normalizedModelPath) }.getOrNull()
        val size = runCatching { modelFile?.length() ?: 0L }.getOrDefault(0L)
        val mtime = runCatching { modelFile?.lastModified() ?: 0L }.getOrDefault(0L)

        val v = visionBackend?.name ?: "NONE"
        val a = audioBackend?.name ?: "NONE"

        val keyMaterial = "$modelName|$normalizedModelPath|$size|$mtime|backend=${backend.name}|vision=$v|audio=$a"
        val key = "${modelName}_${sha256HexShort(keyMaterial)}"
        val dir = File(base, key)

        if (!dir.exists()) {
            if (!dir.mkdirs()) return context.cacheDir.absolutePath
        }
        if (!dir.isDirectory) return context.cacheDir.absolutePath

        return dir.absolutePath
    }

    /**
     * Internal init used by auto-init paths.
     *
     * Note:
     * - Does not take LiteRtLM.apiMutex to stay deadlock-safe.
     */
    internal suspend fun awaitInitializedInternal(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = LiteRtLmKeys.runtimeKey(model)
        if (LiteRtLmSessionManager.hasInstance(key)) return

        val signal = getOrCreateInitSignal(key)

        initialize(
            context = context,
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            onDone = { /* ignored */ },
            systemMessage = systemMessage,
            tools = tools,
        )

        val backendHint = preferredBackend(model)
        val serializationHint = initWaitProfiles[key]?.serializationDir
        val err = withTimeoutOrNull(initAwaitTimeoutMsForKey(key, backendHint, serializationHint)) { signal.await() }
            ?: "Initialization timed out after ${initAwaitTimeoutMsForKey(key, backendHint, serializationHint)}ms."

        if (err.isNotEmpty()) throw IllegalStateException("LiteRT-LM initialization failed: $err")
    }

    /**
     * Upgrade (reinitialize) runtime capabilities if needed.
     */
    internal suspend fun upgradeCapabilitiesIfNeeded(
        context: Context,
        model: Model,
        wantImage: Boolean,
        wantAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        if (!wantImage && !wantAudio) return

        val key = LiteRtLmKeys.runtimeKey(model)
        val inst = LiteRtLmSessionManager.getInstance(key) ?: return

        val needImage = wantImage && !inst.supportImage
        val needAudio = wantAudio && !inst.supportAudio
        if (!needImage && !needAudio) return

        val nextImage = inst.supportImage || wantImage
        val nextAudio = inst.supportAudio || wantAudio

        AppLog.w(
            LiteRtLmLogging.TAG,
            "Capability upgrade requested: key='$key' -> image=$nextImage audio=$nextAudio"
        )

        initialize(
            context = context,
            model = model,
            supportImage = nextImage,
            supportAudio = nextAudio,
            onDone = { /* ignored */ },
            systemMessage = systemMessage,
            tools = tools,
        )

        val err = awaitInitSignalAdaptive(key = key, reason = "upgradeCapabilitiesIfNeeded")
        if (err.isNotEmpty()) throw IllegalStateException("LiteRT-LM capability upgrade failed: $err")
    }

    /**
     * Public async initializer (fire-and-forget).
     */
    internal fun initialize(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onDone: (String) -> Unit,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = LiteRtLmKeys.runtimeKey(model)

        LiteRtLmRunController.markUsed(key)
        LiteRtLmSessionManager.cancelScheduledCleanup(key, "initialize")

        val signal = getOrCreateInitSignal(key)

        val backendHint = preferredBackend(model)
        val normalizedModelPath = LiteRtLmKeys.normalizeTaskPath(model.getPath())
        val visionHint = if (supportImage) Backend.GPU else null
        val audioHint = if (supportAudio) Backend.CPU else null
        val serializationDirHint = runCatching {
            persistentLiteRtSerializationDir(
                context = context,
                modelName = model.name,
                normalizedModelPath = normalizedModelPath,
                backend = backendHint,
                visionBackend = visionHint,
                audioBackend = audioHint,
            )
        }.getOrNull()

        initWaitProfiles.putIfAbsent(
            key,
            InitWaitProfile(
                backend = backendHint,
                serializationDir = serializationDirHint,
                createdAtMs = SystemClock.elapsedRealtime(),
            )
        )

        val accepted = initInFlight.add(key)
        if (!accepted) {
            ioScope.launch {
                val err = awaitInitSignalAdaptive(
                    key = key,
                    reason = "initialize(join)",
                    backendHint = backendHint,
                    serializationDirHint = serializationDirHint,
                )
                postToMain { onDone(err) }
            }
            return
        }

        ioScope.launch {
            var engineToCloseOnFailure: Engine? = null
            var completed = false

            try {
                LiteRtLmSessionManager.withSessionLock(key, reason = "initialize") {

                    ensureNativeLoadedBestEffort()

                    // Reject ONLY when streaming/recovery is running (preparing is allowed).
                    if (LiteRtLmRunController.isRunActiveOrRecoveringKey(key)) {
                        throw IllegalStateException("Initialization rejected: active/recovering run in progress for key='$key'.")
                    }

                    val existing = LiteRtLmSessionManager.removeInstance(key)
                    if (existing != null) {
                        AppLog.w(LiteRtLmLogging.TAG, "initialize: closing existing instance before re-init: key='$key'")
                        runCatching { existing.conversation.close() }.onFailure { AppLog.w(LiteRtLmLogging.TAG, "close conversation failed: ${it.message}", it) }
                        runCatching { existing.engine.close() }.onFailure { AppLog.w(LiteRtLmLogging.TAG, "close engine failed: ${it.message}", it) }
                        delay(RETIRED_CLOSE_GRACE_MS)
                    }

                    val defaultMax = defaultMaxTokensForModel(model.name)
                    val maxTokensRaw = model.getIntConfigValue(Model.ConfigKey.MAX_TOKENS, defaultMax).coerceAtLeast(1)
                    val maxTokens = maxTokensRaw.coerceIn(1, ABS_MAX_NUM_TOKENS)

                    val topK = sanitizeTopK(model.getIntConfigValue(Model.ConfigKey.TOP_K, DEFAULT_TOPK))
                    val topP = sanitizeTopP(model.getFloatConfigValue(Model.ConfigKey.TOP_P, DEFAULT_TOPP))
                    val temperature = sanitizeTemperature(model.getFloatConfigValue(Model.ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

                    val backend = preferredBackend(model)

                    val rawModelPath = model.getPath()
                    val normalizedPath = LiteRtLmKeys.normalizeTaskPath(rawModelPath)

                    val visionPreferred = if (supportImage) Backend.GPU else null
                    val audioPreferred = if (supportAudio) Backend.CPU else null

                    fun resolveCacheDir(forBackend: Backend, v: Backend?, a: Backend?): String? {
                        return runCatching {
                            persistentLiteRtSerializationDir(
                                context = context,
                                modelName = model.name,
                                normalizedModelPath = normalizedPath,
                                backend = forBackend,
                                visionBackend = v,
                                audioBackend = a,
                            )
                        }.getOrNull()
                    }

                    fun buildConfig(forBackend: Backend, v: Backend?, a: Backend?): EngineConfig {
                        val cacheDirPath = resolveCacheDir(forBackend, v, a)
                        return EngineConfig(
                            modelPath = normalizedPath,
                            backend = forBackend,
                            visionBackend = v,
                            audioBackend = a,
                            maxNumTokens = maxTokens,
                            cacheDir = cacheDirPath,
                        )
                    }

                    runCatching {
                        val dirNow = resolveCacheDir(backend, visionPreferred, audioPreferred)
                        initWaitProfiles[key] = InitWaitProfile(
                            backend = backend,
                            serializationDir = dirNow,
                            createdAtMs = SystemClock.elapsedRealtime(),
                        )
                        val warm = isSerializationWarmBestEffort(dirNow)
                        val t = recommendedInitAwaitTimeoutMs(backend, dirNow)
                        AppLog.i(LiteRtLmLogging.TAG, "Init profile: key='$key' backend=$backend warm=$warm awaitTimeoutMs=$t serializationDir='$dirNow'")
                    }

                    var engineConfig = buildConfig(backend, visionPreferred, audioPreferred)

                    AppLog.d(LiteRtLmLogging.TAG, "Initializing LiteRT-LM: model='${model.name}', key='$key'")
                    AppLog.d(LiteRtLmLogging.TAG, "Capabilities: image=$supportImage audio=$supportAudio")
                    AppLog.d(LiteRtLmLogging.TAG, "Backend=$backend maxNumTokens=$maxTokens (raw=$maxTokensRaw) topK=$topK topP=$topP temp=$temperature")
                    AppLog.d(LiteRtLmLogging.TAG, "ModelPath: raw='$rawModelPath' normalized='$normalizedPath'")

                    val engine = runCatching {
                        Engine(engineConfig).also {
                            engineToCloseOnFailure = it
                            it.initialize()
                        }
                    }.getOrElse { first ->
                        if (backend == Backend.GPU) {
                            AppLog.w(LiteRtLmLogging.TAG, "GPU init failed; trying CPU fallback: ${first.message}")
                            val v = if (supportImage) Backend.CPU else null
                            val a = if (supportAudio) Backend.CPU else null
                            engineConfig = buildConfig(Backend.CPU, v, a)
                            runCatching {
                                initWaitProfiles[key] = InitWaitProfile(
                                    backend = Backend.CPU,
                                    serializationDir = engineConfig.cacheDir,
                                    createdAtMs = SystemClock.elapsedRealtime(),
                                )
                            }
                            Engine(engineConfig).also {
                                engineToCloseOnFailure = it
                                it.initialize()
                            }
                        } else {
                            throw first
                        }
                    }

                    val conversationConfig = buildConversationConfig(model, systemMessage, tools)

                    val conversation: Conversation = LiteRtLmSessionManager.createConversationWithRetry(
                        engine = engine,
                        cfg = conversationConfig,
                        key = key,
                        reason = "initialize",
                        timeoutMs = CLOSE_GRACE_MS + RETIRED_CLOSE_GRACE_MS,
                    )

                    LiteRtLmSessionManager.setInstance(
                        key,
                        LiteRtLmInstance(
                            engine = engine,
                            conversation = conversation,
                            supportImage = supportImage,
                            supportAudio = supportAudio,
                            engineConfigSnapshot = engineConfig,
                            conversationConfigSnapshot = conversationConfig,
                        )
                    )

                    AppLog.d(LiteRtLmLogging.TAG, "LiteRT-LM initialization succeeded: model='${model.name}', key='$key'")
                    postToMain { onDone("") }
                    completeInitSignal(signal, "")
                    completed = true
                }
            } catch (e: Exception) {
                val err = LiteRtLmLogging.cleanError(e.message)
                AppLog.e(LiteRtLmLogging.TAG, "LiteRT-LM initialization failed: $err", e)
                runCatching { engineToCloseOnFailure?.close() }.onFailure { AppLog.w(LiteRtLmLogging.TAG, "close engine after init failure failed: ${it.message}", it) }

                postToMain { onDone(err) }
                completeInitSignal(signal, err)
                completed = true
            } finally {
                initInFlight.remove(key)
                initWaitProfiles.remove(key)
                if (!completed) completeInitSignal(signal, "Initialization aborted unexpectedly.")
            }
        }
    }

    /**
     * Suspend-style initializer.
     */
    internal suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = LiteRtLmKeys.runtimeKey(model)

        LiteRtLmRunController.markUsed(key)
        LiteRtLmSessionManager.cancelScheduledCleanup(key, "initializeIfNeeded")

        if (LiteRtLmSessionManager.hasInstance(key)) return

        initialize(
            context = context,
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            onDone = { /* ignored */ },
            systemMessage = systemMessage,
            tools = tools,
        )

        val backendHint = preferredBackend(model)
        val serializationHint = initWaitProfiles[key]?.serializationDir

        val err = awaitInitSignalAdaptive(key = key, reason = "initializeIfNeeded", backendHint = backendHint, serializationDirHint = serializationHint)
        if (err.isNotEmpty()) throw IllegalStateException("LiteRT-LM initialization failed: $err")
    }
}