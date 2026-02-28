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

data class ModelDownloadSpec(
    val modelUrl: String?,
    val fileName: String,
    val timeoutMs: Long,
    val uiThrottleMs: Long,
    val uiMinDeltaBytes: Long,
    val hfToken: String?,
)

fun SurveyConfig.resolveModelDownloadSpec(
    defaultFileNameFallback: String = "model.litertlm",
    timeoutMsFallback: Long = 20 * 60 * 1000L,
    uiThrottleMsFallback: Long = 200L,
    uiMinDeltaBytesFallback: Long = 512L * 1024L,
): ModelDownloadSpec {
    val url = modelDefaults.defaultModelUrl?.trim()?.takeIf { it.isNotBlank() }

    val name = modelDefaults.defaultFileName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: defaultFileNameFallback

    val timeout = (modelDefaults.timeoutMs ?: timeoutMsFallback).coerceAtLeast(1L)
    val uiThrottle = (modelDefaults.uiThrottleMs ?: uiThrottleMsFallback).coerceAtLeast(0L)
    val uiMinDelta = (modelDefaults.uiMinDeltaBytes ?: uiMinDeltaBytesFallback).coerceAtLeast(0L)

    return ModelDownloadSpec(
        modelUrl = url,
        fileName = name,
        timeoutMs = timeout,
        uiThrottleMs = uiThrottle,
        uiMinDeltaBytes = uiMinDelta,
        hfToken = BuildConfig.HF_TOKEN,
    )
}

private fun buildConfigStringOrNull(fieldName: String): String? {
    val f = runCatching { BuildConfig::class.java.getField(fieldName) }.getOrNull() ?: return null
    val v = runCatching { f.get(null) as? String }.getOrNull() ?: return null
    val t = v.trim()
    return t.takeIf { it.isNotBlank() }
}