/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Fake Warmup Engine)
 *  ---------------------------------------------------------------------
 *  File: FakeWarmupEngine.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.warmup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake engine for tests/demo mode.
 *
 * Behavior:
 * - Always reports SkippedNotReady("fake").
 * - All operations are no-ops.
 *
 * Notes:
 * - This engine is intentionally deterministic.
 * - It MUST NOT throw.
 */
class FakeWarmupEngine : WarmupController.Engine {

    private val _prefetchState =
        MutableStateFlow<WarmupController.PrefetchState>(
            WarmupController.PrefetchState.SkippedNotReady("fake"),
        )

    private val _compileState =
        MutableStateFlow<WarmupController.CompileState>(
            WarmupController.CompileState.SkippedNotReady("fake"),
        )

    override val prefetchState: StateFlow<WarmupController.PrefetchState> = _prefetchState.asStateFlow()
    override val compileState: StateFlow<WarmupController.CompileState> = _compileState.asStateFlow()

    /**
     * Updates warmup inputs.
     *
     * This fake engine ignores inputs to remain deterministic.
     */
    override fun updateInputs(inputs: WarmupController.Inputs?) {
        // Intentionally ignored.
        // Keep state stable to be deterministic in tests/demo.
    }

    /**
     * Starts prefetch.
     *
     * This fake engine always stays in SkippedNotReady("fake").
     */
    override fun startPrefetch(appContext: Context) {
        // No-op by design.
        // Keep state stable.
    }

    /**
     * Starts compile.
     *
     * This fake engine always stays in SkippedNotReady("fake").
     */
    override fun startCompile(appContext: Context) {
        // No-op by design.
        // Keep state stable.
    }

    /**
     * Requests compile after prefetch.
     *
     * This fake engine ignores requests.
     */
    override fun requestCompileAfterPrefetch(appContext: Context, reason: String) {
        // No-op by design.
    }

    /**
     * Cancels all warmup activities.
     *
     * This fake engine has nothing to cancel.
     */
    override fun cancelAll(reason: String) {
        // No-op by design.
    }

    /**
     * Resets the engine for retry.
     *
     * This fake engine remains deterministic and does nothing.
     */
    override fun resetForRetry(reason: String) {
        // No-op by design.
    }
}