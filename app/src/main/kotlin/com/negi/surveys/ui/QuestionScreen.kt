/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: QuestionScreen.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/**
 * Question screen (frame-ready, text-answer variant).
 *
 * Goals:
 * - Provide a minimal, usable Q/A UI: show questionId, collect a free-text answer.
 * - Validate input so "Next" does not progress with an empty answer.
 * - Provide debug instrumentation without logging PII (no full answer in logs).
 *
 * Architectural note:
 * - This composable is UI-centric. It does not know navigation keys or graphs.
 * - It emits user intents via callbacks.
 * - Next step: state hoisting (answer owned by ViewModel / parent).
 */
@Composable
fun QuestionScreen(
    questionId: String,
    /** Display text for the question prompt (placeholder by default). */
    prompt: String = "Question prompt for $questionId (placeholder)",
    /** Optional initial answer (e.g., restored from ViewModel/state). */
    initialAnswer: String = "",
    /** Emits the sanitized answer when user proceeds. */
    onNext: (answer: String) -> Unit,
    /** Navigate back. */
    onBack: () -> Unit
) {
    /**
     * rememberSaveable keeps draft text across rotation and process recreation.
     * Keyed by questionId so each question has its own draft slot.
     */
    var answer by rememberSaveable(questionId) { mutableStateOf("") }

    /**
     * Apply initialAnswer only if the user hasn't typed yet, to avoid overwriting input.
     */
    LaunchedEffect(questionId, initialAnswer) {
        if (answer.isBlank() && initialAnswer.isNotBlank()) {
            answer = initialAnswer
            Log.d(TAG, "QuestionScreen: applied initialAnswer qid=$questionId len=${initialAnswer.length}")
        }
    }

    val trimmed by remember { derivedStateOf { answer.trim() } }
    val isValid by remember { derivedStateOf { trimmed.isNotEmpty() } }
    val answerLen by remember { derivedStateOf { answer.length } }

    LaunchedEffect(questionId) {
        Log.d(TAG, "QuestionScreen: composed qid=$questionId")
    }

    DisposableEffect(questionId) {
        onDispose {
            Log.d(TAG, "QuestionScreen: disposed qid=$questionId")
        }
    }

    fun submitIfValid() {
        if (!isValid) {
            Log.d(TAG, "QuestionScreen: submit blocked (invalid) qid=$questionId len=$answerLen")
            return
        }
        /**
         * Avoid logging full answer (potentially sensitive user input).
         * Log only length + short SHA-256 prefix.
         */
        val sha8 = sha256Hex(trimmed).take(8)
        Log.d(TAG, "QuestionScreen: next qid=$questionId len=${trimmed.length} sha8=$sha8")
        onNext(trimmed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            /**
             * App-level TopBar consumes TOP statusBars inset.
             * Apply only Horizontal + Bottom safeDrawing here to avoid double insets.
             */
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
            /**
             * Keep the buttons visible above the on-screen keyboard.
             */
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "Question: $questionId")
        Text(text = prompt)

        OutlinedTextField(
            value = answer,
            onValueChange = { newValue -> answer = newValue },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your answer") },
            singleLine = false,
            minLines = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { submitIfValid() }
            ),
            supportingText = {
                /**
                 * Show debug-friendly metadata without exposing the answer itself.
                 */
                Text("Length: $answerLen")
            }
        )

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    Log.d(TAG, "QuestionScreen: back clicked qid=$questionId")
                    onBack()
                }
            ) {
                Text("Back")
            }

            Button(
                onClick = { submitIfValid() },
                enabled = isValid
            ) {
                Text("Next")
            }
        }

        if (!isValid) {
            Text("Please enter an answer to continue.")
        }
    }
}

/**
 * Computes SHA-256 hex for debug purposes.
 *
 * Notes:
 * - Used ONLY for non-PII logging (short prefix).
 * - Do not log full input values.
 */
private fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        sb.append(((b.toInt() shr 4) and 0xF).toString(16))
        sb.append((b.toInt() and 0xF).toString(16))
    }
    return sb.toString()
}

private const val TAG = "QuestionScreen"
