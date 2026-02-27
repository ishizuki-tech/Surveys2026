/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Warmup Effects)
 *  ---------------------------------------------------------------------
 *  File: WarmupEffects.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/**
 * Triggers IO-only prefetch once, but only after at least one frame is rendered.
 *
 * Rationale:
 * - Even "just requesting" prefetch can do work (thread hops, file checks),
 *   so we ensure at least one frame is drawn first.
 *
 * Typical use:
 * - Put this near your app root composable so the first frame is not blocked.
 */
@Composable
fun StartupPrefetchEffect(
    prefetch: suspend () -> Unit,
    delayMsAfterFirstFrame: Long = 150L,
    onRequested: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        // Ensure at least one UI frame has been produced.
        withFrameNanos { /* first frame */ }

        if (delayMsAfterFirstFrame > 0L) delay(delayMsAfterFirstFrame)

        onRequested?.invoke()
        prefetch()
    }
}

/**
 * Triggers compile warmup once per [warmupKey], but only after at least one frame is rendered.
 *
 * Rationale:
 * - Compilation / GPU init can be heavy; do not fight the initial frame/layout.
 *
 * Typical use:
 * - Put this inside Chat screen (or right before the user needs the model).
 */
@Composable
fun CompileWarmupOnFirstNeedEffect(
    warmupKey: Any,
    compileWarmup: suspend () -> Unit,
    delayMsAfterFirstFrame: Long = 150L,
    onRequested: (() -> Unit)? = null,
) {
    LaunchedEffect(warmupKey) {
        // Ensure at least one UI frame has been produced.
        withFrameNanos { /* first frame */ }

        if (delayMsAfterFirstFrame > 0L) delay(delayMsAfterFirstFrame)

        onRequested?.invoke()
        compileWarmup()
    }
}