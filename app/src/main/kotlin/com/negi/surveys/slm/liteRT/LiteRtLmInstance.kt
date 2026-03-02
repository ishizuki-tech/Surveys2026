/*
 * =====================================================================
 *  IshizukiTech LLC — LiteRtLM Integration
 *  ---------------------------------------------------------------------
 *  File: LiteRtLmInstance.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.surveys.slm.liteRT

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig

/**
 * Holds a LiteRT-LM [Engine] and its active [Conversation].
 *
 * Concurrency notes:
 * - [conversation] may be replaced during reset flows, so it is marked [Volatile].
 * - Closing [engine] or [conversation] while a native stream is active may crash or corrupt state.
 *   Callers must ensure stream termination (or defer cleanup) before closing.
 *
 * Snapshots:
 * - [engineConfigSnapshot] and [conversationConfigSnapshot] are stored for debugging and
 *   for detecting unexpected configuration drift across resets.
 */
internal data class LiteRtLmInstance(
    val engine: Engine,
    @Volatile var conversation: Conversation,
    val supportImage: Boolean,
    val supportAudio: Boolean,
    val engineConfigSnapshot: EngineConfig,
    @Volatile var conversationConfigSnapshot: ConversationConfig,
)