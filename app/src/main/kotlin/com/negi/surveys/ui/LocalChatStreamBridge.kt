/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Stream Local)
 *  ---------------------------------------------------------------------
 *  File: LocalChatStreamBridge.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.negi.surveys.BuildConfig
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.logging.AppLog

/**
 * CompositionLocal for a session-shared [ChatStreamBridge].
 *
 * Notes:
 * - In production, SurveyAppRoot should provide a session instance via CompositionLocalProvider.
 * - In debug builds, missing provider should be loud (fail-fast) to avoid silent coupling bugs.
 * - In release builds, we keep a safe fallback singleton to remain robust.
 */
private object DefaultChatStreamBridgeHolder {
    val instance: ChatStreamBridge = ChatStreamBridge(
        logger = { msg ->
            // Keep it lightweight; this is a fallback instance.
            AppLog.d("ChatStreamBridge", msg)
        }
    )
}

/** Session-shared ChatStreamBridge provider. */
val LocalChatStreamBridge = staticCompositionLocalOf<ChatStreamBridge> {
    if (BuildConfig.DEBUG) {
        error("LocalChatStreamBridge is not provided. Wrap your root with CompositionLocalProvider(LocalChatStreamBridge provides ChatStreamBridge(...)).")
    } else {
        DefaultChatStreamBridgeHolder.instance
    }
}