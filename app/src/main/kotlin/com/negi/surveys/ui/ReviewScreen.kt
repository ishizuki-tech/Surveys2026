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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.negi.surveys.BuildConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Review screen (frame-ready).
 *
 * Goals:
 * - Display all interactions (USER/AI/FOLLOW_UP/MODEL_RAW) deterministically.
 * - Provide lightweight debug metadata (count/lines/chars/sha256) to catch regressions.
 * - Keep it UI-only: navigation and persistence happen outside via callbacks.
 */
@Composable
fun ReviewScreen(
    logs: List<ReviewQuestionLog> = emptyList(),
    timeline: List<ReviewTimelineItem>? = null,
    onExport: () -> Unit,
    onBack: () -> Unit,
    onUploadLogs: (() -> Unit)? = null,
    uploadStatusLine: String? = null
) {
    val logsKey: List<Int> = logs.map { logFingerprint(it) }
    val timelineKey: List<Int>? = timeline?.let { buildTimelineFingerprint(it) }

    val sortedLogs: List<ReviewQuestionLog> = remember(logsKey) {
        sortLogsNaturally(logs)
    }

    val resolvedTimeline: List<ReviewTimelineItem> = remember(timelineKey, logsKey) {
        timeline ?: buildTimelineFromLogs(sortedLogs)
    }

    // English comments only.
    /** Debug: detect duplicate timeline keys early (should be impossible after index-based keys). */
    LaunchedEffect(timelineKey ?: logsKey) {
        if (!BuildConfig.DEBUG) return@LaunchedEffect

        val seen = HashSet<String>(resolvedTimeline.size * 2)
        val dupes = ArrayList<String>()
        resolvedTimeline.forEachIndexed { idx, item ->
            val k = timelineRowKey(idx, item)
            if (!seen.add(k)) dupes.add(k)
        }

        if (dupes.isNotEmpty()) {
            Log.e(TAG, "Timeline LazyColumn keys DUPLICATED. count=${dupes.size} sample=${dupes.take(5)}")
        } else {
            Log.d(TAG, "Timeline LazyColumn keys OK. size=${resolvedTimeline.size}")
        }
    }

    val metrics = produceState(
        initialValue = ReviewMetrics.computing(),
        key1 = timelineKey ?: logsKey
    ) {
        value = withContext(Dispatchers.Default) {
            computeTimelineMetrics(resolvedTimeline)
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

    val rawExpanded = remember { mutableStateMapOf<String, Boolean>() }

    var mode by remember { mutableStateOf(ReviewViewMode.TIMELINE) }

    LaunchedEffect(timelineKey ?: logsKey) {
        val alive = HashSet<String>(256)

        for (item in resolvedTimeline) {
            if (item is ReviewTimelineItem.Line) {
                val line = item.line
                if (line.kind == ReviewChatKind.MODEL_RAW && line.text.length > 260) {
                    alive.add(rawLineKey(item.questionId, line.text))
                }
            }
        }

        if (rawExpanded.isNotEmpty()) {
            val toRemove = rawExpanded.keys.filter { it !in alive }
            if (toRemove.isNotEmpty()) {
                val before = rawExpanded.size
                for (k in toRemove) rawExpanded.remove(k)
                Log.d(TAG, "rawExpanded pruned. before=$before after=${rawExpanded.size} removed=${toRemove.size}")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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

            /**
             * Debug-only crash trigger.
             *
             * Why:
             * - Provides a deterministic way to validate CrashCapture + "upload on next launch".
             * - Only visible in DEBUG builds so production UX is unaffected.
             */
            if (BuildConfig.DEBUG) {
                item {
                    DebugCrashCard(
                        onCrash = {
                            triggerTestCrash("ReviewScreen debug button")
                        }
                    )
                }
            }

            item {
                Text("Questions: ${metrics.questionCount}")
                Text("Transcript lines: ${metrics.totalLines}")
                Text("Total chars: ${metrics.totalChars}")
                Text("SHA-256: ${if (metrics.sha256 == SHA_COMPUTING) "(computing...)" else metrics.sha256}")

                if (!uploadStatusLine.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Upload: $uploadStatusLine",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }

            item {
                ModeToggleRow(
                    mode = mode,
                    onModeChange = { mode = it }
                )
            }

            if (sortedLogs.isEmpty()) {
                item { Text("No answers yet.") }
            } else {
                when (mode) {
                    ReviewViewMode.TIMELINE -> {
                        itemsIndexed(
                            items = resolvedTimeline,
                            // English comments only.
                            // Keys MUST be unique among siblings. Never use content-based keys for MODEL_RAW.
                            key = { idx, item -> timelineRowKey(idx, item) }
                        ) { idx, item ->
                            when (item) {
                                is ReviewTimelineItem.QuestionHeader -> TimelineQuestionHeaderCard(item)
                                is ReviewTimelineItem.Line -> TimelineLineBubble(index = idx, item = item, rawExpanded = rawExpanded)
                            }
                        }
                    }

                    ReviewViewMode.BY_QUESTION -> {
                        itemsIndexed(
                            items = sortedLogs,
                            // English comments only.
                            // Be robust even if questionId duplicates by mistake.
                            key = { idx, item -> "${item.questionId}:$idx" }
                        ) { _, log ->
                            QuestionTranscriptCard(
                                log = log,
                                rawExpanded = rawExpanded
                            )
                        }
                    }
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
                },
                modifier = Modifier.weight(1f)
            ) { Text("Back") }

            OutlinedButton(
                onClick = {
                    Log.d(TAG, "ReviewScreen: upload logs clicked")
                    onUploadLogs?.invoke()
                },
                enabled = onUploadLogs != null,
                modifier = Modifier.weight(1f)
            ) { Text("Upload Logs") }

            Button(
                onClick = {
                    Log.d(TAG, "ReviewScreen: export clicked")
                    onExport()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Go to Export") }
        }
    }
}

@Composable
private fun DebugCrashCard(
    onCrash: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Debug Tools",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "CrashCapture logging test: press the button to force a crash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onCrash,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Crash (Test)")
            }
        }
    }
}

/**
 * Triggers a deterministic crash for logging validation.
 *
 * Notes:
 * - This should only be reachable in DEBUG builds (guarded by UI).
 * - The goal is to test crash capture + pending upload flow on next launch.
 */
private fun triggerTestCrash(reason: String): Nothing {
    Log.e(TAG, "TEST_CRASH triggered: $reason")
    throw RuntimeException("TEST_CRASH: $reason")
}

private enum class ReviewViewMode {
    TIMELINE,
    BY_QUESTION
}

@Composable
private fun ModeToggleRow(
    mode: ReviewViewMode,
    onModeChange: (ReviewViewMode) -> Unit
) {
    val shape = RoundedCornerShape(999.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val isTimeline = mode == ReviewViewMode.TIMELINE
        val isByQuestion = mode == ReviewViewMode.BY_QUESTION

        Button(
            onClick = { onModeChange(ReviewViewMode.TIMELINE) },
            enabled = !isTimeline,
            modifier = Modifier.weight(1f)
        ) { Text("Timeline") }

        OutlinedButton(
            onClick = { onModeChange(ReviewViewMode.BY_QUESTION) },
            enabled = !isByQuestion,
            modifier = Modifier.weight(1f)
        ) { Text("By Question") }
    }
}

private fun buildTimelineFromLogs(sortedLogs: List<ReviewQuestionLog>): List<ReviewTimelineItem> {
    if (sortedLogs.isEmpty()) return emptyList()

    val totalLines = sortedLogs.sumOf { it.lines.size }
    val out = ArrayList<ReviewTimelineItem>(sortedLogs.size + totalLines)

    for (q in sortedLogs) {
        out.add(
            ReviewTimelineItem.QuestionHeader(
                questionId = q.questionId,
                prompt = q.prompt,
                isSkipped = q.isSkipped,
                completionPayload = q.completionPayload
            )
        )
        for (line in q.lines) {
            out.add(
                ReviewTimelineItem.Line(
                    questionId = q.questionId,
                    prompt = q.prompt,
                    isSkipped = q.isSkipped,
                    line = line
                )
            )
        }
    }
    return out
}

private fun buildTimelineFingerprint(timeline: List<ReviewTimelineItem>): List<Int> {
    val out = ArrayList<Int>(timeline.size)
    for (item in timeline) out.add(timelineItemFingerprint(item))
    return out
}

private fun timelineItemFingerprint(item: ReviewTimelineItem): Int {
    return when (item) {
        is ReviewTimelineItem.QuestionHeader -> {
            var h = item.questionId.hashCode()
            h = (h * 31) + item.prompt.hashCode()
            h = (h * 31) + item.completionPayload.hashCode()
            h = (h * 31) + if (item.isSkipped) 1 else 0
            h
        }

        is ReviewTimelineItem.Line -> {
            var h = item.questionId.hashCode()
            h = (h * 31) + item.line.kind.ordinal
            h = (h * 31) + item.line.text.hashCode()
            h = (h * 31) + item.line.text.length
            h
        }
    }
}

private fun timelineRowKey(index: Int, item: ReviewTimelineItem): String {
    // English comments only.
    // Compose requires unique keys among sibling items. Use index-based keys to guarantee uniqueness.
    return when (item) {
        is ReviewTimelineItem.QuestionHeader -> "H:${item.questionId}:$index"
        is ReviewTimelineItem.Line -> "L:${item.questionId}:${item.line.kind.name}:$index"
    }
}

@Composable
private fun TimelineQuestionHeaderCard(header: ReviewTimelineItem.QuestionHeader) {
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
                    text = "• ${header.questionId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                val badgeBg =
                    if (header.isSkipped) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer

                val badgeFg =
                    if (header.isSkipped) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onPrimaryContainer

                Surface(color = badgeBg, shape = RoundedCornerShape(999.dp)) {
                    Text(
                        text = if (header.isSkipped) "SKIPPED" else "OK",
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeFg,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Prompt",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            SelectionContainer {
                Text(text = header.prompt, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Payload",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            SelectionContainer {
                Text(text = header.completionPayload, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun TimelineLineBubble(
    index: Int,
    item: ReviewTimelineItem.Line,
    rawExpanded: MutableMap<String, Boolean>
) {
    val line = item.line
    val isRaw = line.kind == ReviewChatKind.MODEL_RAW
    val canToggle = isRaw && line.text.length > 260

    // English comments only.
    // Stable expansion key: prefer per-row key so identical raw text does not cross-toggle.
    val key = if (isRaw) rawLineKeyForRow(item.questionId, index, line.text) else null
    val expanded = if (key == null) false else (rawExpanded[key] ?: false)

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

    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    val bodyText = if (!isRaw) {
        line.text
    } else {
        if (expanded) line.text
        else line.text.take(260).let { if (line.text.length > 260) "$it …" else it }
    }

    val clickMod = if (canToggle && key != null) {
        Modifier.clickable { rawExpanded[key] = !expanded }
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
                text = "${item.questionId} • $header",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                modifier = Modifier.weight(1f)
            )
            if (canToggle) {
                Text(
                    text = if (expanded) "Collapse" else "Expand",
                    style = MaterialTheme.typography.labelSmall,
                    color = fg
                )
            }
        }

        Spacer(Modifier.height(4.dp))

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

                val badgeBg =
                    if (log.isSkipped) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer

                val badgeFg =
                    if (log.isSkipped) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onPrimaryContainer

                Surface(color = badgeBg, shape = RoundedCornerShape(999.dp)) {
                    Text(
                        text = if (log.isSkipped) "SKIPPED" else "OK",
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeFg,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(text = "Prompt", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(text = log.prompt, style = MaterialTheme.typography.bodyMedium) }

            Spacer(Modifier.height(10.dp))

            Text(text = "Payload", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(text = log.completionPayload, style = MaterialTheme.typography.bodySmall) }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(10.dp))

            Text(text = "Transcript", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))

            if (log.lines.isEmpty()) {
                Text("(no transcript)")
                return@Column
            }

            val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

            log.lines.forEachIndexed { index, line ->
                val isRaw = line.kind == ReviewChatKind.MODEL_RAW
                // English comments only.
                // Stable expansion key: keep per-row uniqueness to avoid cross-question collisions.
                val key = if (isRaw) rawLineKeyForRow(log.questionId, index, line.text) else "${log.questionId}:$index"
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
                    if (expanded) line.text
                    else line.text.take(260).let { if (line.text.length > 260) "$it …" else it }
                }

                val canToggle = isRaw && line.text.length > 260
                val clickMod = if (canToggle) {
                    Modifier.clickable { rawExpanded[key] = !expanded }
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
                        if (canToggle) {
                            Text(
                                text = if (expanded) "Collapse" else "Expand",
                                style = MaterialTheme.typography.labelSmall,
                                color = fg
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

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
        fun computing(): ReviewMetrics = ReviewMetrics(0, 0, 0, SHA_COMPUTING)
    }
}

private fun computeTimelineMetrics(timeline: List<ReviewTimelineItem>): ReviewMetrics {
    if (timeline.isEmpty()) {
        return ReviewMetrics(
            questionCount = 0,
            totalLines = 0,
            totalChars = 0,
            sha256 = sha256Hex("")
        )
    }

    data class Header(val prompt: String, val payload: String, val skipped: Boolean)

    val headers = LinkedHashMap<String, Header>(16)
    var totalLines = 0
    var totalChars = 0

    val sb = StringBuilder()

    for (item in timeline) {
        when (item) {
            is ReviewTimelineItem.QuestionHeader -> {
                sb.append("H=").append(item.questionId).append('\n')
                sb.append("P=").append(item.prompt).append('\n')
                sb.append("S=").append(item.isSkipped).append('\n')
                sb.append("X=").append(item.completionPayload).append('\n')

                if (!headers.containsKey(item.questionId)) {
                    headers[item.questionId] = Header(item.prompt, item.completionPayload, item.isSkipped)
                    totalChars += item.prompt.length
                    totalChars += item.completionPayload.length
                }
            }

            is ReviewTimelineItem.Line -> {
                totalLines += 1
                totalChars += item.line.text.length

                sb.append("L=").append(item.questionId).append(':')
                    .append(item.line.kind.name).append(':')
                    .append(item.line.text).append('\n')
            }
        }
        sb.append("---\n")
    }

    val sha = sha256Hex(sb.toString())

    return ReviewMetrics(
        questionCount = headers.keys.size.coerceAtLeast(
            timeline.asSequence()
                .filterIsInstance<ReviewTimelineItem.Line>()
                .map { it.questionId }
                .toSet()
                .size
        ),
        totalLines = totalLines,
        totalChars = totalChars,
        sha256 = sha
    )
}

private val HEX_CHARS: CharArray = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
)

/**
 * Returns a fixed-length (64 chars) lowercase hex SHA-256.
 *
 * This implementation avoids String indexing pitfalls and never throws on hex conversion.
 */
private fun sha256Hex(text: String): String {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)

    val out = CharArray(digest.size * 2)
    var i = 0
    for (b in digest) {
        val v = b.toInt() and 0xFF
        out[i++] = HEX_CHARS[v ushr 4]
        out[i++] = HEX_CHARS[v and 0x0F]
    }
    return String(out)
}

private fun logFingerprint(log: ReviewQuestionLog): Int {
    var h = log.questionId.hashCode()
    h = (h * 31) + log.prompt.hashCode()
    h = (h * 31) + log.completionPayload.hashCode()
    h = (h * 31) + if (log.isSkipped) 1 else 0
    for (l in log.lines) {
        h = (h * 31) + l.kind.ordinal
        h = (h * 31) + l.text.hashCode()
    }
    return h
}

private fun rawLineKey(questionId: String, rawText: String): String {
    // English comments only.
    // This key is ONLY for expansion-state tracking (not for LazyColumn item keys).
    // NOTE: This can collide across rows if text is identical. Prefer rawLineKeyForRow for per-row toggles.
    return "$questionId:raw:${rawText.length}:${rawText.hashCode()}"
}

private fun rawLineKeyForRow(questionId: String, index: Int, rawText: String): String {
    // English comments only.
    // Stable expansion key per row: avoids cross-toggle when identical raw text appears multiple times.
    return "$questionId:rawRow:$index:${rawText.length}:${rawText.hashCode()}"
}

private fun sortLogsNaturally(logs: List<ReviewQuestionLog>): List<ReviewQuestionLog> {
    return logs
        .map { it to questionIdKey(it.questionId) }
        .sortedWith(
            compareBy<Pair<ReviewQuestionLog, QuestionIdKey>>(
                { it.second.prefix },
                { it.second.number },
                { it.second.raw }
            )
        )
        .map { it.first }
}

private data class QuestionIdKey(
    val prefix: String,
    val number: Int,
    val raw: String
)

private fun questionIdKey(id: String): QuestionIdKey {
    val raw = id.trim()
    val m = ID_PATTERN.matchEntire(raw)
    if (m != null) {
        val prefix = m.groupValues[1]
        val number = m.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
        return QuestionIdKey(prefix = prefix, number = number, raw = raw)
    }
    return QuestionIdKey(prefix = raw, number = Int.MAX_VALUE, raw = raw)
}

private const val TAG = "ReviewScreen"
private const val SHA_COMPUTING = "__computing__"
private val ID_PATTERN = Regex("^([A-Za-z]+)(\\d+)$")