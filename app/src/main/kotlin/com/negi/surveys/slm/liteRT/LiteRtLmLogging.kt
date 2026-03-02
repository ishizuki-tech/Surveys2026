
/*
 * =====================================================================
 *  IshizukiTech LLC — LiteRtLM Integration
 *  ---------------------------------------------------------------------
 *  File: LiteRtLmLogging.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm.liteRT

import com.negi.surveys.BuildConfig
import java.util.Locale

/**
 * Centralized logging + debug toggles for LiteRT-LM integration.
 *
 * Security:
 * - Streaming logs must never be enabled in release builds to avoid leaking user data.
 */
internal object LiteRtLmLogging {

    internal const val TAG: String = "LiteRtLM"

    /** Upper bound for error strings rendered in UI/log aggregation. */
    internal const val ERROR_MAX_CHARS: Int = 280

    /** Streaming debug toggles. Never enable in release. */
    internal val DEBUG_STREAM: Boolean = BuildConfig.DEBUG
    internal const val DEBUG_STREAM_EVERY_N: Int = 1
    internal const val DEBUG_PREFIX_CHARS: Int = 24

    /** Text extraction debug toggles. */
    internal val DEBUG_EXTRACT: Boolean = BuildConfig.DEBUG
    internal const val DEBUG_EXTRACT_EVERY_N: Int = 64

    /** RunState snapshot logging. */
    internal val DEBUG_STATE: Boolean = BuildConfig.DEBUG
    internal const val DEBUG_STATE_EVERY_N: Int = 1

    /** Throwable debug toggles. */
    internal val DEBUG_ERROR_THROWABLE: Boolean = BuildConfig.DEBUG
    internal const val DEBUG_ERROR_STACK_LINES: Int = 18

    /**
     * Render a safe log preview. Avoid dumping raw prompts to logcat.
     *
     * Note:
     * - Intentionally conservative (short, single-line).
     */
    internal fun safeLogPreview(s: String, maxChars: Int = 48): String {
        val t = s
            .replace("\r", "")
            .replace("\n", "\\n")
            .trim()
        return if (t.length <= maxChars) t else t.take(maxChars) + "…"
    }

    /** Clean and compress error messages for UI/logging. */
    internal fun cleanError(msg: String?): String {
        return msg
            ?.replace("INTERNAL:", "", ignoreCase = true)
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            ?.take(ERROR_MAX_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown error"
    }

    /** Build a short stack string for logs. */
    internal fun shortStack(t: Throwable, maxLines: Int = DEBUG_ERROR_STACK_LINES): String {
        val lines = t.stackTrace.take(maxLines).joinToString(separator = "\n") { "  at $it" }
        val cause = t.cause
        val causeLine =
            if (cause != null) "\nCaused by: ${cause::class.java.name}: ${cause.message}" else ""
        return "${t::class.java.name}: ${t.message}\n$lines$causeLine"
    }

    /**
     * Try to extract a "status code" (or similar) from Throwable using reflection.
     *
     * Note:
     * - Defensive because SDK versions differ.
     */
    internal fun extractStatusCodeBestEffort(t: Throwable): Int? {
        val methodNames = listOf(
            "getStatusCode",
            "statusCode",
            "getCode",
            "code",
            "getErrorCode",
            "errorCode",
        )
        for (name in methodNames) {
            val m = runCatching {
                t.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 0 &&
                            (it.returnType == Int::class.javaPrimitiveType || it.returnType == Int::class.javaObjectType)
                }
            }.getOrNull() ?: continue

            val v = runCatching { m.invoke(t) as? Int }.getOrNull()
            if (v != null) return v
        }

        val fieldNames = listOf("statusCode", "code", "errorCode")
        for (fn in fieldNames) {
            val f = runCatching { t.javaClass.getDeclaredField(fn) }.getOrNull() ?: continue
            runCatching { f.isAccessible = true }
            val v = runCatching { f.get(t) }.getOrNull()
            if (v is Int) return v
        }

        val c = t.cause
        if (c != null && c !== t) return extractStatusCodeBestEffort(c)

        return null
    }

    /** Detect cancellation from throwable/message. */
    internal fun isCancellationThrowable(t: Throwable, msg: String): Boolean {
        if (t is kotlinx.coroutines.CancellationException) return true
        val lc = msg.lowercase(Locale.ROOT)
        if (lc.contains("cancel")) return true
        if (lc.contains("canceled")) return true
        if (lc.contains("cancelled")) return true
        if (lc.contains("aborted") && lc.contains("user")) return true
        return false
    }

    /** Detect "session already exists" class of errors (FAILED_PRECONDITION). */
    internal fun isSessionAlreadyExistsError(t: Throwable): Boolean {
        val m = (t.message ?: t.toString()).lowercase(Locale.ROOT)
        if (m.contains("a session already exists")) return true
        if (m.contains("only one session is supported")) return true
        if (m.contains("failed_precondition")) return true
        return false
    }

    /** Detect "Conversation is not alive" errors for recovery paths. */
    internal fun isConversationNotAliveError(t: Throwable): Boolean {
        val m = (t.message ?: t.toString()).lowercase(Locale.ROOT)
        return m.contains("conversation is not alive")
    }
}