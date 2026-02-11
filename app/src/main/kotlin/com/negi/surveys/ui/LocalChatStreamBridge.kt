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
import com.negi.surveys.chat.ChatStreamBridge

/**
 * CompositionLocal for a session-shared [ChatStreamBridge].
 *
 * Notes:
 * - Default uses a process-stable singleton to keep previews/tests robust.
 * - In production, SurveyAppRoot should provide a session instance via CompositionLocalProvider.
 */
private object DefaultChatStreamBridgeHolder {
    val instance: ChatStreamBridge = ChatStreamBridge()
}

/** Session-shared ChatStreamBridge provider. */
val LocalChatStreamBridge = staticCompositionLocalOf<ChatStreamBridge> {
    DefaultChatStreamBridgeHolder.instance
}
