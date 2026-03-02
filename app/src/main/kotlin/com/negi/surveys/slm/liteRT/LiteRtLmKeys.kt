/*
 * =====================================================================
 *  IshizukiTech LLC — LiteRtLM Integration
 *  ---------------------------------------------------------------------
 *  File: LiteRtLmKeys.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.surveys.slm.liteRT

import com.negi.surveys.slm.Model
import java.io.File

/**
 * Runtime key utilities for LiteRT-LM session caching.
 *
 * Notes:
 * - Keys must remain stable across app restarts because instances may be reused.
 * - We normalize file paths to avoid creating duplicate engines for the same physical file.
 */
internal object LiteRtLmKeys {

    private const val DATA_DATA_PREFIX = "/data/data/"
    private const val DATA_USER0_PREFIX = "/data/user/0/"

    /**
     * Normalize a model path to avoid duplicate Engine instances caused by alias paths.
     *
     * Why:
     * - Android may expose app-private storage as "/data/user/0/..." or "/data/data/...".
     * - Canonicalization reduces duplicates caused by symlinks or relative segments.
     *
     * @param rawPath Raw model path (possibly empty, relative, or aliased).
     * @return Normalized path suitable for stable runtime keys.
     */
    internal fun normalizeTaskPath(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return trimmed

        val canonical = runCatching { File(trimmed).canonicalPath }.getOrDefault(trimmed)
        return canonical.replace(DATA_DATA_PREFIX, DATA_USER0_PREFIX)
    }

    /**
     * Build a stable runtime key for a model instance.
     *
     * Important:
     * - Uses [Model.getPath] so the key matches EngineConfig.modelPath.
     */
    internal fun runtimeKey(model: Model): String {
        val normalizedPath = normalizeTaskPath(model.getPath())
        return "${model.name}|$normalizedPath"
    }
}