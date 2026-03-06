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
import com.negi.surveys.chat.ChatDrafts
import com.negi.surveys.chat.ChatQuestionViewModel
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.chat.ChatValidation

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
    private val validator: ChatValidation.AnswerValidatorI,
    private val streamBridge: ChatStreamBridge,
    private val draftStore: ChatDrafts.ChatDraftStore,
    private val draftKey: ChatDrafts.DraftKey,
) : ViewModelProvider.Factory {

    companion object {
        /**
         * Process-wide draft store.
         *
         * Note:
         * - This is intentionally in-memory.
         * - If persistence is needed later, replace this from DI instead of creating ad-hoc globals.
         */
        internal val sharedDraftStore: ChatDrafts.ChatDraftStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            ChatDrafts.InMemoryChatDraftStore()
        }
    }

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
            draftKey = draftKey,
        ) as T
    }
}