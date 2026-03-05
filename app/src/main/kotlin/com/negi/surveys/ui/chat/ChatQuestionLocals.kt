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
import com.negi.surveys.chat.ChatValidation

/**
 * Required CompositionLocal for [RepositoryI].
 *
 * Design:
 * - Non-nullable to prevent silent fallback creation.
 * - Fails fast with a clear error if not provided.
 *
 * Usage:
 * - Provide in your app root:
 *   CompositionLocalProvider(LocalRepositoryI provides repository) { ... }
 */
val LocalRepositoryI = staticCompositionLocalOf<ChatValidation.RepositoryI> {
    error("LocalRepositoryI is not provided. Provide RepositoryI in SurveyAppRoot via CompositionLocalProvider.")
}