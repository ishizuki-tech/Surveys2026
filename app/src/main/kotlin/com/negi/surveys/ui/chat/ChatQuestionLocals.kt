/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Question UI)
 *  ---------------------------------------------------------------------
 *  File: ChatQuestionLocals.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf
import com.negi.surveys.chat.RepositoryI

/**
 * Required CompositionLocal for RepositoryI.
 *
 * NOTE:
 * - This is intentionally NON-nullable to prevent silent fallback creation.
 * - If you forget to provide it in the root, the app will crash fast with a clear error.
 */
val LocalRepositoryI = staticCompositionLocalOf<RepositoryI> {
    error("LocalRepositoryI is not provided. Provide RepositoryI in SurveyAppRoot via CompositionLocalProvider.")
}