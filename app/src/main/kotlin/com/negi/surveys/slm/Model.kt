/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: Model.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package com.negi.surveys.slm

import com.negi.surveys.config.SurveyConfig
import java.util.Locale

data class Model(
    val name: String,
    val taskPath: String,
    val config: Map<ConfigKey, Any> = emptyMap(),
) {

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

    fun getPath(): String = taskPath

    fun getIntConfigValue(key: ConfigKey, default: Int): Int =
        config[key].toIntOrDefault(default)

    fun getFloatConfigValue(key: ConfigKey, default: Float): Float =
        config[key].toFloatOrDefault(default)

    fun getStringConfigValue(key: ConfigKey, default: String): String =
        (config[key] as? String) ?: default

    /**
     * Safely coerces a config value into Int.
     */
    private fun Any?.toIntOrDefault(default: Int): Int {
        return when (this) {
            null -> default
            is Int -> this
            is Long -> this.toInt()
            is Float -> this.toInt()
            is Double -> this.toInt()
            is Number -> this.toInt()
            is String -> {
                val t = trim()
                t.toIntOrNull()
                    ?: t.toLongOrNull()?.toInt()
                    ?: t.toDoubleOrNull()?.toInt()
                    ?: default
            }
            else -> default
        }
    }

    /**
     * Safely coerces a config value into Float.
     */
    private fun Any?.toFloatOrDefault(default: Float): Float {
        return when (this) {
            null -> default
            is Float -> this
            is Double -> this.toFloat()
            is Int -> this.toFloat()
            is Long -> this.toFloat()
            is Number -> this.toFloat()
            is String -> {
                val t = trim()
                t.toFloatOrNull()
                    ?: t.toDoubleOrNull()?.toFloat()
                    ?: default
            }
            else -> default
        }
    }

    companion object {

        private const val DEFAULT_MAX_TOKENS: Int = 4096
        private const val DEFAULT_TOP_K: Int = 40
        private const val DEFAULT_TOP_P: Float = 0.9f
        private const val DEFAULT_TEMPERATURE: Float = 0.7f

        /**
         * Absolute hard limits enforced by this app layer.
         *
         * Rationale:
         * - Prevent pathological configs from causing long stalls or memory pressure.
         * - Provide a stable contract regardless of what upstream runtime accepts.
         */
        private const val ABS_MAX_TOKENS: Int = 4096
        private const val ABS_MAX_TOP_K: Int = 2048
        private const val ABS_MAX_TEMPERATURE: Float = 2.0f

        /**
         * Builds a safe config map for model execution.
         *
         * Design choice:
         * - Always normalize + clamp at the end to guarantee invariants,
         *   even if upstream config builders change in the future.
         */
        @JvmStatic
        fun buildModelConfigSafe(slm: SurveyConfig.SlmMeta?): MutableMap<ConfigKey, Any> {
            val out: MutableMap<ConfigKey, Any> = if (slm == null) {
                defaultConfig()
            } else {
                buildModelConfigRaw(slm).toMutableMap()
            }

            normalizeTypes(out)
            clampRanges(out)

            return out
        }

        /**
         * Default config when [SurveyConfig.SlmMeta] is not present.
         */
        private fun defaultConfig(): MutableMap<ConfigKey, Any> {
            return mutableMapOf(
                ConfigKey.ACCELERATOR to Accelerator.GPU.label,
                ConfigKey.MAX_TOKENS to DEFAULT_MAX_TOKENS,
                ConfigKey.TOP_K to DEFAULT_TOP_K,
                ConfigKey.TOP_P to DEFAULT_TOP_P,
                ConfigKey.TEMPERATURE to DEFAULT_TEMPERATURE,
            )
        }

        /**
         * Builds config from [SurveyConfig.SlmMeta] WITHOUT normalization/clamping.
         *
         * Note:
         * - The caller must always normalize + clamp to guarantee invariants.
         */
        private fun buildModelConfigRaw(slm: SurveyConfig.SlmMeta): MutableMap<ConfigKey, Any> {
            return mutableMapOf(
                ConfigKey.ACCELERATOR to (slm.accelerator ?: Accelerator.GPU.label),
                ConfigKey.MAX_TOKENS to (slm.maxTokens ?: DEFAULT_MAX_TOKENS),
                ConfigKey.TOP_K to (slm.topK ?: DEFAULT_TOP_K),
                ConfigKey.TOP_P to (slm.topP ?: DEFAULT_TOP_P),
                ConfigKey.TEMPERATURE to (slm.temperature ?: DEFAULT_TEMPERATURE),
            )
        }

        /**
         * Normalizes accelerator labels.
         *
         * Policy:
         * - Default to GPU when empty/unknown to preserve legacy behavior.
         * - Keep exact labels "CPU"/"GPU" because downstream may compare by label.
         */
        private fun normalizeAcceleratorLabel(raw: Any?): String {
            val s = (raw as? String)?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return when (s) {
                Accelerator.CPU.label -> Accelerator.CPU.label
                Accelerator.GPU.label -> Accelerator.GPU.label
                "" -> Accelerator.GPU.label
                else -> Accelerator.GPU.label
            }
        }

        /**
         * Normalizes numeric config values to concrete Int/Float types.
         *
         * Post-condition:
         * - MAX_TOKENS, TOP_K are Int
         * - TOP_P, TEMPERATURE are Float
         * - ACCELERATOR is a valid label String
         */
        private fun normalizeTypes(m: MutableMap<ConfigKey, Any>) {
            m[ConfigKey.MAX_TOKENS] = m[ConfigKey.MAX_TOKENS].toIntOrDefault(DEFAULT_MAX_TOKENS)
            m[ConfigKey.TOP_K] = m[ConfigKey.TOP_K].toIntOrDefault(DEFAULT_TOP_K)
            m[ConfigKey.TOP_P] = m[ConfigKey.TOP_P].toFloatOrDefault(DEFAULT_TOP_P)
            m[ConfigKey.TEMPERATURE] = m[ConfigKey.TEMPERATURE].toFloatOrDefault(DEFAULT_TEMPERATURE)
            m[ConfigKey.ACCELERATOR] = normalizeAcceleratorLabel(m[ConfigKey.ACCELERATOR])
        }

        /**
         * Clamps normalized config values to safe runtime ranges.
         *
         * Safety note:
         * - Safe even if normalization was skipped, because conversions are defensive.
         */
        private fun clampRanges(m: MutableMap<ConfigKey, Any>) {
            val maxTokens = m[ConfigKey.MAX_TOKENS].toIntOrDefault(DEFAULT_MAX_TOKENS)
                .coerceIn(1, ABS_MAX_TOKENS)

            val topK = m[ConfigKey.TOP_K].toIntOrDefault(DEFAULT_TOP_K)
                .coerceIn(1, ABS_MAX_TOP_K)

            val topP = m[ConfigKey.TOP_P].toFloatOrDefault(DEFAULT_TOP_P)
                .finiteOr(DEFAULT_TOP_P)
                .coerceIn(0f, 1f)

            val temperature = m[ConfigKey.TEMPERATURE].toFloatOrDefault(DEFAULT_TEMPERATURE)
                .finiteOr(DEFAULT_TEMPERATURE)
                .coerceIn(0f, ABS_MAX_TEMPERATURE)

            m[ConfigKey.MAX_TOKENS] = maxTokens
            m[ConfigKey.TOP_K] = topK
            m[ConfigKey.TOP_P] = topP
            m[ConfigKey.TEMPERATURE] = temperature
        }

        /**
         * Replaces NaN/Infinity with a default value.
         */
        private fun Float.finiteOr(default: Float): Float =
            if (this.isFinite()) this else default

        /**
         * Safely coerces a config value into Int.
         *
         * Note:
         * - This is scoped to this file's [Model] type to avoid leaking utility extensions globally.
         */
        private fun Any?.toIntOrDefault(default: Int): Int {
            return when (this) {
                null -> default
                is Int -> this
                is Long -> this.toInt()
                is Float -> this.toInt()
                is Double -> this.toInt()
                is Number -> this.toInt()
                is String -> {
                    val t = trim()
                    t.toIntOrNull()
                        ?: t.toLongOrNull()?.toInt()
                        ?: t.toDoubleOrNull()?.toInt()
                        ?: default
                }
                else -> default
            }
        }

        /**
         * Safely coerces a config value into Float.
         *
         * Note:
         * - This is scoped to this file's [Model] type to avoid leaking utility extensions globally.
         */
        private fun Any?.toFloatOrDefault(default: Float): Float {
            return when (this) {
                null -> default
                is Float -> this
                is Double -> this.toFloat()
                is Int -> this.toFloat()
                is Long -> this.toFloat()
                is Number -> this.toFloat()
                is String -> {
                    val t = trim()
                    t.toFloatOrNull()
                        ?: t.toDoubleOrNull()?.toFloat()
                        ?: default
                }
                else -> default
            }
        }
    }
}