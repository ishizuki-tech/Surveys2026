/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Chat Question UI)
 *  ---------------------------------------------------------------------
 *  File: ChatQuestionComposer.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Bottom composer for the chat question screen.
 *
 * Notes:
 * - Input is clipped to a reasonable maximum to avoid huge allocations / OOM.
 * - This composable does not log raw text.
 */
@Composable
internal fun BottomComposerCard(
    input: String,
    isBusy: Boolean,
    hasCompletion: Boolean,
    canSubmit: Boolean,
    nextInFlight: Boolean,
    onInputChange: (String) -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onMeasuredHeightPx: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .onSizeChanged { onMeasuredHeightPx(it.height) }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { onInputChange(clipInputPreserveSurrogates(it)) },
                enabled = !isBusy,
                label = {
                    Text(
                        when {
                            isBusy -> "Validating..."
                            hasCompletion -> "Edit answer (will re-validate)"
                            else -> "Your answer (optional)"
                        }
                    )
                },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (canSubmit) onSubmit() }
                )
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    enabled = true
                ) { Text("Back") }

                Button(
                    onClick = onSubmit,
                    modifier = Modifier.weight(1f),
                    enabled = canSubmit
                ) { Text("Submit") }

                Button(
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                    enabled = !nextInFlight && !isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) { Text("Next") }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = when {
                    isBusy -> "Validation is running. Please wait before navigating."
                    hasCompletion -> "Next will use the accepted answer."
                    else -> "You can press Next to skip for now and answer later."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val MAX_INPUT_CHARS: Int = 8_000

/**
 * Clip user input to a safe maximum without splitting surrogate pairs.
 *
 * Notes:
 * - Avoids producing invalid UTF-16 by cutting between high/low surrogate.
 * - Returns empty when the limit is non-positive.
 */
private fun clipInputPreserveSurrogates(raw: String): String {
    val n = MAX_INPUT_CHARS.coerceAtLeast(0)
    if (n == 0) return ""
    if (raw.length <= n) return raw

    var end = min(n, raw.length)
    if (end > 0 && end < raw.length) {
        val last = raw[end - 1]
        val next = raw[end]
        if (Character.isHighSurrogate(last) && Character.isLowSurrogate(next)) {
            end -= 1
        }
    }

    if (end <= 0) return ""
    return raw.substring(0, end)
}