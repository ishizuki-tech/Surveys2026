/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyConfigModelDownloadSpec.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Resolves model download parameters (URL, file name, token, timeouts, UI throttles)
 *  from SurveyConfig loaded by SurveyConfigLoader.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.config

import com.negi.surveys.BuildConfig
import java.util.Locale

/**
 * Immutable model download configuration resolved from [SurveyConfig].
 *
 * @property modelUrl Remote URL to download the model from (nullable).
 * @property fileName Local file name to store the model.
 * @property timeoutMs Download timeout in milliseconds (>= 1).
 * @property uiThrottleMs UI update throttle in milliseconds (>= 0).
 * @property uiMinDeltaBytes Minimum bytes delta required before pushing UI updates (>= 0).
 * @property hfToken Optional Hugging Face token (nullable).
 */
data class ModelDownloadSpec(
    val modelUrl: String?,
    val fileName: String,
    val timeoutMs: Long,
    val uiThrottleMs: Long,
    val uiMinDeltaBytes: Long,
    val hfToken: String?,
)

/**
 * Resolve model download parameters from [SurveyConfig.modelDefaults] + BuildConfig token fields.
 *
 * Notes:
 * - Token is resolved via reflection so builds that omit HF token fields won't crash.
 * - URL is lightly normalized; invalid URLs are treated as null (best-effort).
 * - File name is sanitized to avoid path traversal / separators.
 */
fun SurveyConfig.resolveModelDownloadSpec(
    defaultFileNameFallback: String = "model.litertlm",
    timeoutMsFallback: Long = 20 * 60 * 1000L,
    uiThrottleMsFallback: Long = 200L,
    uiMinDeltaBytesFallback: Long = 512L * 1024L,
): ModelDownloadSpec {
    val url = modelDefaults.defaultModelUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::normalizeModelUrlOrNull)

    val name = modelDefaults.defaultFileName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { sanitizeFileName(it) }
        ?: sanitizeFileName(defaultFileNameFallback)

    val timeout = (modelDefaults.timeoutMs ?: timeoutMsFallback).coerceAtLeast(1L)
    val uiThrottle = (modelDefaults.uiThrottleMs ?: uiThrottleMsFallback).coerceAtLeast(0L)
    val uiMinDelta = (modelDefaults.uiMinDeltaBytes ?: uiMinDeltaBytesFallback).coerceAtLeast(0L)

    val token = resolveHfTokenOrNull()

    return ModelDownloadSpec(
        modelUrl = url,
        fileName = name,
        timeoutMs = timeout,
        uiThrottleMs = uiThrottle,
        uiMinDeltaBytes = uiMinDelta,
        hfToken = token,
    )
}

/**
 * Best-effort URL normalization for model download.
 *
 * Rules:
 * - Accepts only http(s) URLs.
 * - Rejects whitespace-containing URLs.
 * - Leaves the string as-is otherwise (no heavy parsing to avoid surprises).
 */
private fun normalizeModelUrlOrNull(raw: String): String? {
    val s = raw.trim()
    if (s.isBlank()) return null
    if (s.any { it.isWhitespace() }) return null

    val lower = s.lowercase(Locale.US)
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null

    return s
}

/**
 * Sanitizes a file name to avoid path traversal and separators.
 *
 * Behavior:
 * - Replaces directory separators and ':' with '_'.
 * - Replaces ISO control characters with '_'.
 * - Truncates to a safe max length.
 * - Collapses to a safe fallback if result becomes blank.
 */
private fun sanitizeFileName(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return DEFAULT_SAFE_FILE_NAME

    val cleaned = buildString(trimmed.length) {
        for (c in trimmed) {
            when {
                c == '/' || c == '\\' || c == ':' || c == '\u0000' -> append('_')
                c.isISOControl() -> append('_')
                else -> append(c)
            }
        }
    }.trim()

    // Stronger traversal-ish suppression.
    val collapsed = cleaned
        .replace("..", "_")
        .replace("./", "_")
        .replace(".\\", "_")
        .trim('.')
        .trim()

    // Length cap to keep filesystem/zip/debug sane.
    val capped = if (collapsed.length <= MAX_FILE_NAME_CHARS) {
        collapsed
    } else {
        collapsed.take(MAX_FILE_NAME_CHARS).trim().trim('.')
    }

    return capped.ifBlank { DEFAULT_SAFE_FILE_NAME }
}

private fun Char.isISOControl(): Boolean = Character.isISOControl(this)

/**
 * Resolves Hugging Face token from BuildConfig via reflection.
 *
 * Supported field names (first hit wins):
 * - HF_TOKEN
 * - HUGGINGFACE_TOKEN
 * - HUGGING_FACE_TOKEN
 * - HUGGING_FACE_API_TOKEN
 *
 * Caching:
 * - Token is resolved once per process for efficiency.
 */
private fun resolveHfTokenOrNull(): String? {
    // Fast path.
    HfTokenCache.cachedOrNull()?.let { return it }

    val candidates = listOf(
        "HF_TOKEN",
        "HUGGINGFACE_TOKEN",
        "HUGGING_FACE_TOKEN",
        "HUGGING_FACE_API_TOKEN",
    )

    val resolved = candidates.asSequence()
        .mapNotNull { buildConfigStringOrNull(it) }
        .firstOrNull()

    HfTokenCache.set(resolved)
    return resolved
}

/**
 * Reads a String field from BuildConfig via reflection.
 *
 * Notes:
 * - Returns null if field does not exist, is not a String, or is blank.
 */
private fun buildConfigStringOrNull(fieldName: String): String? {
    val f = runCatching { BuildConfig::class.java.getField(fieldName) }.getOrNull() ?: return null
    val v = runCatching { f.get(null) as? String }.getOrNull() ?: return null
    val t = v.trim()
    return t.takeIf { it.isNotBlank() }
}

private object HfTokenCache {
    @Volatile private var resolved: Boolean = false
    @Volatile private var token: String? = null

    fun cachedOrNull(): String? {
        return if (resolved) token else null
    }

    fun set(value: String?) {
        token = value
        resolved = true
    }
}

private const val DEFAULT_SAFE_FILE_NAME: String = "model.litertlm"
private const val MAX_FILE_NAME_CHARS: Int = 200