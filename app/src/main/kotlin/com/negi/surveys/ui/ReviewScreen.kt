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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Review screen (frame-ready).
 *
 * Goals:
 * - Display a full transcript for each question (USER/AI/FOLLOW_UP/MODEL_RAW).
 * - Provide lightweight debug metadata (count/lines/chars/sha256) to catch regressions.
 * - Keep it UI-only: navigation and persistence happen outside via callbacks.
 */
@Composable
fun ReviewScreen(
    logs: List<ReviewQuestionLog> = DEFAULT_LOGS_PLACEHOLDER,
    onExport: () -> Unit,
    onBack: () -> Unit
) {
    /**
     * IMPORTANT:
     * - Some callers may build logs from mutable sources.
     * - Use a content-based key for deterministic recomputation.
     */
    val logsKey: List<Int> = logs.map {
        var h = it.questionId.hashCode()
        h = (h * 31) + it.prompt.hashCode()
        h = (h * 31) + it.payload.hashCode()
        h = (h * 31) + it.lines.size
        h
    }

    val metrics = produceState(
        initialValue = ReviewMetrics.computing(),
        key1 = logsKey
    ) {
        value = withContext(Dispatchers.Default) {
            computeMetrics(logs)
        }
    }.value

    LaunchedEffect(metrics.questionCount, metrics.totalLines, metrics.totalChars, metrics.sha256) {
        if (metrics.sha256 != SHA_COMPUTING) {
            Log.d(
                TAG,
                "ReviewScreen: q=${metrics.questionCount} lines=${metrics.totalLines} chars=${metrics.totalChars} sha256=${metrics.sha256}"
            )
        } else {
            Log.d(
                TAG,
                "ReviewScreen: q=${metrics.questionCount} lines=${metrics.totalLines} chars=${metrics.totalChars} sha256=(computing)"
            )
        }
    }

    // NOTE:
    // - Collapse/expand state for MODEL_RAW lines, keyed by "qid:index".
    val rawExpanded = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            /**
             * IMPORTANT:
             * - App-level TopBar consumes TOP statusBars inset.
             * - Apply only Horizontal + Bottom safeDrawing here.
             */
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Review",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Text("Questions: ${metrics.questionCount}")
                Text("Transcript lines: ${metrics.totalLines}")
                Text("Total chars: ${metrics.totalChars}")
                Text("SHA-256: ${if (metrics.sha256 == SHA_COMPUTING) "(computing...)" else metrics.sha256}")
                Spacer(Modifier.padding(top = 6.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }

            if (logs.isEmpty()) {
                item { Text("No answers yet.") }
            } else {
                itemsIndexed(
                    items = logs,
                    key = { _, item -> item.questionId }
                ) { _, log ->
                    QuestionTranscriptCard(
                        log = log,
                        rawExpanded = rawExpanded
                    )
                }
            }

            item { Spacer(Modifier.padding(bottom = 8.dp)) }
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

/**
 * Renders one question section including:
 * - Prompt
 * - Payload
 * - Full transcript lines
 */
@Composable
private fun QuestionTranscriptCard(
    log: ReviewQuestionLog,
    rawExpanded: MutableMap<String, Boolean>
) {
    val shape = RoundedCornerShape(16.dp)

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "• ${log.questionId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                val badgeBg = if (log.isSkipped) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
                val badgeFg = if (log.isSkipped) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }

                Surface(
                    color = badgeBg,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = if (log.isSkipped) "SKIPPED" else "OK",
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeFg,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.padding(top = 8.dp))

            Text(
                text = "Prompt",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            SelectionContainer {
                Text(
                    text = log.prompt,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.padding(top = 10.dp))

            Text(
                text = "Payload",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            SelectionContainer {
                Text(
                    text = log.payload,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.padding(top = 10.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.padding(top = 10.dp))

            Text(
                text = "Transcript",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.padding(top = 6.dp))

            if (log.lines.isEmpty()) {
                Text("(no transcript)")
                return@Column
            }

            val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

            log.lines.forEachIndexed { index, line ->
                val key = "${log.questionId}:$index"
                val isRaw = line.kind == ReviewChatKind.MODEL_RAW
                val expanded = rawExpanded[key] ?: false

                val header = when (line.kind) {
                    ReviewChatKind.USER -> "USER"
                    ReviewChatKind.AI -> "AI"
                    ReviewChatKind.FOLLOW_UP -> "FOLLOW-UP"
                    ReviewChatKind.MODEL_RAW -> "MODEL RAW"
                }

                val bg = when (line.kind) {
                    ReviewChatKind.USER -> MaterialTheme.colorScheme.primaryContainer
                    ReviewChatKind.AI -> MaterialTheme.colorScheme.secondaryContainer
                    ReviewChatKind.FOLLOW_UP -> MaterialTheme.colorScheme.tertiaryContainer
                    ReviewChatKind.MODEL_RAW -> MaterialTheme.colorScheme.surfaceVariant
                }

                val fg = when (line.kind) {
                    ReviewChatKind.USER -> MaterialTheme.colorScheme.onPrimaryContainer
                    ReviewChatKind.AI -> MaterialTheme.colorScheme.onSecondaryContainer
                    ReviewChatKind.FOLLOW_UP -> MaterialTheme.colorScheme.onTertiaryContainer
                    ReviewChatKind.MODEL_RAW -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                val bodyText = if (!isRaw) {
                    line.text
                } else {
                    if (expanded) line.text else line.text.take(260).let { if (line.text.length > 260) "$it …" else it }
                }

                val clickMod = if (isRaw && line.text.length > 260) {
                    Modifier.clickable {
                        rawExpanded[key] = !expanded
                    }
                } else {
                    Modifier
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg)
                        .border(1.dp, outline, RoundedCornerShape(12.dp))
                        .then(clickMod)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = header,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = fg,
                            modifier = Modifier.weight(1f)
                        )
                        if (isRaw && line.text.length > 260) {
                            Text(
                                text = if (expanded) "Collapse" else "Expand",
                                style = MaterialTheme.typography.labelSmall,
                                color = fg
                            )
                        }
                    }

                    Spacer(Modifier.padding(top = 4.dp))

                    SelectionContainer {
                        Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg,
                            maxLines = if (isRaw && !expanded) 10 else Int.MAX_VALUE,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
        }
    }
}

private data class ReviewMetrics(
    val questionCount: Int,
    val totalLines: Int,
    val totalChars: Int,
    val sha256: String
) {
    companion object {
        fun computing(): ReviewMetrics = ReviewMetrics(
            questionCount = 0,
            totalLines = 0,
            totalChars = 0,
            sha256 = SHA_COMPUTING
        )
    }
}

private fun computeMetrics(logs: List<ReviewQuestionLog>): ReviewMetrics {
    val questionCount = logs.size
    val totalLines = logs.sumOf { it.lines.size }
    val totalChars = logs.sumOf { it.prompt.length + it.payload.length + it.lines.sumOf { l -> l.text.length } }
    val source = buildFingerprintSource(logs)
    val sha = sha256Hex(source)
    return ReviewMetrics(
        questionCount = questionCount,
        totalLines = totalLines,
        totalChars = totalChars,
        sha256 = sha
    )
}

private fun buildFingerprintSource(logs: List<ReviewQuestionLog>): String {
    val sb = StringBuilder()
    for (q in logs.sortedBy { it.questionId }) {
        sb.append("Q=").append(q.questionId).append('\n')
        sb.append("P=").append(q.prompt).append('\n')
        sb.append("S=").append(q.isSkipped).append('\n')
        sb.append("X=").append(q.payload).append('\n')
        for (l in q.lines) {
            sb.append("L=").append(l.kind.name).append(':').append(l.text).append('\n')
        }
        sb.append("---\n")
    }
    return sb.toString()
}

/**
 * Returns a fixed-length (64 chars) lowercase hex SHA-256.
 */
private fun sha256Hex(text: String): String {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)

    val hex = "0123456789abcdef"
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v ushr 4])
        sb.append(hex[v and 0x0F])
    }
    return sb.toString()
}

private const val TAG = "ReviewScreen"
private const val SHA_COMPUTING = "__computing__"

/**
 * Review model types.
 *
 * Notes:
 * - These are UI-facing snapshot models (immutable).
 * - Keep them small and deterministic for logging/export/debug.
 */
enum class ReviewChatKind {
    USER,
    AI,
    FOLLOW_UP,
    MODEL_RAW
}

data class ReviewChatLine(
    val kind: ReviewChatKind,
    val text: String
)

data class ReviewQuestionLog(
    val questionId: String,
    val prompt: String,
    val isSkipped: Boolean,
    val payload: String,
    val lines: List<ReviewChatLine>
)

private val DEFAULT_LOGS_PLACEHOLDER: List<ReviewQuestionLog> = listOf(
    ReviewQuestionLog(
        questionId = "Q1",
        prompt = "Example prompt for Q1.",
        isSkipped = false,
        payload = "Example payload for Q1.",
        lines = listOf(
            ReviewChatLine(ReviewChatKind.USER, "Example user answer."),
            ReviewChatLine(ReviewChatKind.AI, "Example AI response."),
            ReviewChatLine(ReviewChatKind.FOLLOW_UP, "Example follow-up question.")
        )
    )
)
