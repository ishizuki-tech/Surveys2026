/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Question UI)
 *  ---------------------------------------------------------------------
 *  File: ChatQuestionVmFactory.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.negi.surveys.chat.AnswerValidatorI
import com.negi.surveys.chat.ChatDraftStore
import com.negi.surveys.chat.ChatQuestionViewModel
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.chat.DraftKey
import com.negi.surveys.chat.InMemoryChatDraftStore

/**
 * Factory for [ChatQuestionViewModel].
 *
 * Assumption:
 * - DI is not used here; factory is created at call site with explicit dependencies.
 * - Draft store is an in-memory singleton for the current app process.
 */
internal class ChatQuestionViewModelFactory(
    private val questionId: String,
    private val prompt: String,
    private val validator: AnswerValidatorI,
    private val streamBridge: ChatStreamBridge,
    private val draftStore: ChatDraftStore,
    private val draftKey: DraftKey
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatQuestionViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return ChatQuestionViewModel(
            questionId = questionId,
            prompt = prompt,
            validator = validator,
            streamBridge = streamBridge,
            draftStore = draftStore,
            draftKey = draftKey
        ) as T
    }
}

/**
 * Process-wide draft store.
 *
 * Note:
 * - This is intentionally in-memory. If you need persistence, swap this via DI (Hilt/Koin/manual).
 */
internal object ChatDraftStoreHolder {
    val store: ChatDraftStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { InMemoryChatDraftStore() }
}