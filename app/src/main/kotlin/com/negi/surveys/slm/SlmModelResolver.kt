/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (SLM Model Resolver)
 *  ---------------------------------------------------------------------
 *  File: SlmModelResolver.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Centralized model file + model config resolution used by both:
 *   - SlmWarmup
 *   - SlmRepository
 *
 *  Goals:
 *   - Avoid divergent "which file do we load?" behaviors across components.
 *   - Provide strict vs best-effort fallback policy.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm

import android.content.Context
import com.negi.surveys.BuildConfig
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.logging.AppLog
import java.io.File

internal object SlmModelResolver {

    private const val TAG = "SlmModelResolver"
    private val DEBUG = BuildConfig.DEBUG

    data class Resolved(
        val file: File,
        val model: Model,
    )

    /**
     * Resolve the model file from config.
     *
     * @param strict If true: do not attempt fallback scanning when configured path is missing.
     *               If false: try to find the "best" *.litertlm in filesDir.
     */
    fun resolveModelFileOrNull(
        appContext: Context,
        config: SurveyConfig?,
        strict: Boolean,
        fallbackModelFileName: String? = null,
    ): File? {
        val filesDir = appContext.filesDir

        val configuredName = config?.modelDefaults?.defaultFileName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackModelFileName?.trim()?.takeIf { it.isNotEmpty() }

        val configuredFile = configuredName?.let { name ->
            val f = if (name.startsWith("/")) File(name) else File(filesDir, name)
            f.takeIf { it.exists() && it.isFile && it.length() > 0L }
        }

        if (configuredFile != null) {
            if (DEBUG) AppLog.d(TAG, "resolve: configured hit file='${configuredFile.absolutePath}'")
            return configuredFile
        }

        if (strict) {
            if (DEBUG) AppLog.w(TAG, "resolve: strict=true and configured file missing name='$configuredName'")
            return null
        }

        // Best-effort fallback: pick the largest *.litertlm in filesDir.
        val candidates = filesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".litertlm", ignoreCase = true) && it.length() > 0L }
            .orEmpty()

        if (candidates.isEmpty()) {
            if (DEBUG) AppLog.w(TAG, "resolve: no *.litertlm candidates in filesDir='${filesDir.absolutePath}'")
            return null
        }

        val best = candidates.maxByOrNull { it.length() }
        if (DEBUG) {
            AppLog.w(
                TAG,
                "resolve: fallback selected file='${best?.absolutePath}' candidates=${candidates.size}"
            )
        }
        return best
    }

    fun resolveModelName(
        config: SurveyConfig?,
        fallbackModelName: String,
    ): String {
        val name = config?.modelDefaults?.modelName?.trim().orEmpty()
        return if (name.isNotEmpty()) name else fallbackModelName
    }

    fun buildModel(
        file: File,
        config: SurveyConfig?,
        fallbackModelName: String = file.nameWithoutExtension,
    ): Model {
        val name = resolveModelName(config = config, fallbackModelName = fallbackModelName)

        val cfg = Model.buildModelConfigSafe(config?.slm)

        return Model(
            name = name,
            taskPath = file.absolutePath,
            config = cfg
        )
    }

    fun resolve(
        appContext: Context,
        config: SurveyConfig?,
        strict: Boolean,
        fallbackModelFileName: String? = null,
    ): Resolved? {
        val file = resolveModelFileOrNull(
            appContext = appContext,
            config = config,
            strict = strict,
            fallbackModelFileName = fallbackModelFileName
        ) ?: return null

        val model = buildModel(file = file, config = config, fallbackModelName = file.nameWithoutExtension)
        return Resolved(file = file, model = model)
    }
}