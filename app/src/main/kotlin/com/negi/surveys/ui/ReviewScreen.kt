/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: ReviewScreen.kt
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Review screen (frame-ready).
 *
 * Goals:
 * - Display a summary of answers before exporting.
 * - Provide lightweight debug metadata (count/size/sha256) to catch regressions.
 * - Keep it UI-only: navigation and persistence happen outside via callbacks.
 *
 * State design:
 * - The screen accepts [answers] as an input (state hoisting).
 * - In the next step, feed [answers] from a ViewModel (SurveySessionViewModel).
 */
@Composable
fun ReviewScreen(
    answers: Map<String, String> = DEFAULT_ANSWERS_PLACEHOLDER,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    /**
     * Compute metrics off the main thread to avoid jank for large answer sets.
     *
     * Notes:
     * - Keyed by answers so recomputation happens only when the input map changes.
     */
    val metrics = produceState(
        initialValue = ReviewMetrics.computing(),
        key1 = answers
    ) {
        value = withContext(Dispatchers.Default) {
            computeMetrics(answers)
        }
    }.value

    LaunchedEffect(metrics.count, metrics.totalChars, metrics.sha256) {
        if (metrics.sha256 != SHA_COMPUTING) {
            Log.d(TAG, "ReviewScreen: count=${metrics.count} totalChars=${metrics.totalChars} sha256=${metrics.sha256}")
        } else {
            Log.d(TAG, "ReviewScreen: count=${metrics.count} totalChars=${metrics.totalChars} sha256=(computing)")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Text("Review") }

            item {
                Text("Answers: ${metrics.count}")
                Text("Total chars: ${metrics.totalChars}")
                Text("SHA-256: ${if (metrics.sha256 == SHA_COMPUTING) "(computing...)" else metrics.sha256}")
                Spacer(Modifier.height(6.dp))
            }

            if (metrics.count == 0) {
                item { Text("No answers yet.") }
            } else {
                items(
                    items = metrics.sortedKeys,
                    key = { it }
                ) { qid ->
                    val value = answers[qid].orEmpty()
                    AnswerRow(questionId = qid, answer = value)
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    Log.d(TAG, "ReviewScreen: back clicked")
                    onBack()
                }
            ) { Text("Back") }

            Button(
                onClick = {
                    Log.d(TAG, "ReviewScreen: export clicked")
                    onExport()
                }
            ) { Text("Go to Export") }
        }
    }
}

@Composable
private fun AnswerRow(questionId: String, answer: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text("• $questionId")
        SelectionContainer {
            Text(answer.ifBlank { "(empty)" })
        }
    }
}

private data class ReviewMetrics(
    val sortedKeys: List<String>,
    val count: Int,
    val totalChars: Int,
    val sha256: String
) {
    companion object {
        fun computing(): ReviewMetrics = ReviewMetrics(
            sortedKeys = emptyList(),
            count = 0,
            totalChars = 0,
            sha256 = SHA_COMPUTING
        )
    }
}

private fun computeMetrics(answers: Map<String, String>): ReviewMetrics {
    val keys = answers.keys.sorted()
    val totalChars = answers.values.sumOf { it.length }
    val source = buildFingerprintSource(keys, answers)
    val sha = sha256Hex(source)
    return ReviewMetrics(
        sortedKeys = keys,
        count = keys.size,
        totalChars = totalChars,
        sha256 = sha
    )
}

private fun buildFingerprintSource(sortedKeys: List<String>, answers: Map<String, String>): String {
    val sb = StringBuilder()
    for (k in sortedKeys) {
        sb.append(k)
        sb.append('=')
        sb.append(answers[k].orEmpty())
        sb.append('\n')
    }
    return sb.toString()
}

private fun sha256Hex(text: String): String {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        sb.append(((b.toInt() shr 4) and 0xF).toString(16))
        sb.append((b.toInt() and 0xF).toString(16))
    }
    return sb.toString()
}

private const val TAG = "ReviewScreen"
private const val SHA_COMPUTING = "__computing__"

private val DEFAULT_ANSWERS_PLACEHOLDER: Map<String, String> = linkedMapOf(
    "Q1" to "Example answer for Q1.",
    "Q2" to "Example answer for Q2."
)
