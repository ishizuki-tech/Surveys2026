/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Root Support UI)
 *  ---------------------------------------------------------------------
 *  File: SurveyRootSupport.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.WarmupController
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal data class WarmupUiFormat(
    val idleLabel: String,
    val prefetchRunningPrefix: String,
    val compileWaitingPrefix: String,
    val compileCompilingPrefix: String,
)

internal val WARMUP_UI_FORMAT_HOME = WarmupUiFormat(
    idleLabel = "Idle",
    prefetchRunningPrefix = "Prefetch",
    compileWaitingPrefix = "WaitingForPrefetch",
    compileCompilingPrefix = "Compiling",
)

internal val WARMUP_UI_FORMAT_GATE = WarmupUiFormat(
    idleLabel = "Preparing…",
    prefetchRunningPrefix = "Prefetch",
    compileWaitingPrefix = "Compile waiting…",
    compileCompilingPrefix = "Compiling",
)

internal enum class GatePolicy {
    MODEL_ONLY,
    MODEL_PREFETCH_COMPILE,
}

@Composable
internal fun GateOrContent(
    enabled: Boolean,
    policy: GatePolicy,
    modelState: ModelDownloadController.ModelState,
    prefetchState: WarmupController.PrefetchState,
    compileState: WarmupController.CompileState,
    onBack: () -> Unit,
    onRetryAll: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val isBlocking = shouldGateBlock(
        policy = policy,
        modelState = modelState,
        prefetchState = prefetchState,
        compileState = compileState,
    )

    if (isBlocking) {
        SlmGateScreen(
            modelState = modelState,
            prefetchState = prefetchState,
            compileState = compileState,
            onBack = onBack,
            onRetryAll = onRetryAll,
        )
    } else {
        content()
    }
}

/**
 * Latches the content open after the first non-blocking render for a given route instance.
 *
 * Why:
 * - Question screens may hold in-progress two-step state and active streaming.
 * - If late warmup/prefetch/compile transitions temporarily become busy again,
 *   swapping the screen back to the gate can destroy that state.
 * - This gate blocks entry, but does not tear down the content after it has opened once.
 */
@Composable
internal fun GateOrLatchedContent(
    enabled: Boolean,
    policy: GatePolicy,
    latchKey: Any?,
    modelState: ModelDownloadController.ModelState,
    prefetchState: WarmupController.PrefetchState,
    compileState: WarmupController.CompileState,
    onBack: () -> Unit,
    onRetryAll: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val isBlocking = shouldGateBlock(
        policy = policy,
        modelState = modelState,
        prefetchState = prefetchState,
        compileState = compileState,
    )

    var openedOnce by remember(latchKey) { mutableStateOf(!isBlocking) }
    val showContent = openedOnce || !isBlocking

    if (showContent && !openedOnce) {
        SideEffect {
            openedOnce = true
        }
    }

    if (showContent) {
        content()
    } else {
        SlmGateScreen(
            modelState = modelState,
            prefetchState = prefetchState,
            compileState = compileState,
            onBack = onBack,
            onRetryAll = onRetryAll,
        )
    }
}

private fun shouldGateBlock(
    policy: GatePolicy,
    modelState: ModelDownloadController.ModelState,
    prefetchState: WarmupController.PrefetchState,
    compileState: WarmupController.CompileState,
): Boolean {
    val modelBlocking = modelState !is ModelDownloadController.ModelState.Ready
    val prefetchBusy = isPrefetchInProgress(prefetchState)
    val compileBusy =
        compileState is WarmupController.CompileState.WaitingForPrefetch ||
                compileState is WarmupController.CompileState.Compiling

    return when (policy) {
        GatePolicy.MODEL_ONLY -> modelBlocking
        GatePolicy.MODEL_PREFETCH_COMPILE -> modelBlocking || prefetchBusy || compileBusy
    }
}

@Composable
internal fun SlmGateScreen(
    modelState: ModelDownloadController.ModelState,
    prefetchState: WarmupController.PrefetchState,
    compileState: WarmupController.CompileState,
    onBack: () -> Unit,
    onRetryAll: () -> Unit,
) {
    val prefetchInProgress = isPrefetchInProgress(prefetchState)
    val compileInProgress =
        compileState is WarmupController.CompileState.WaitingForPrefetch ||
                compileState is WarmupController.CompileState.Compiling
    val modelInProgress =
        modelState is ModelDownloadController.ModelState.Checking ||
                modelState is ModelDownloadController.ModelState.Downloading

    val nowMs = rememberWarmupUiNowMs(
        inProgress = modelInProgress || prefetchInProgress || compileInProgress,
        tickIntervalMs = 50L,
    )

    val prefetchElapsedMs = rememberDynamicElapsedMs(
        reportedElapsedMs = prefetchState.elapsedMs,
        inProgress = prefetchInProgress,
        nowMs = nowMs,
    )
    val compileElapsedMs = rememberDynamicElapsedMs(
        reportedElapsedMs = compileState.elapsedMs,
        inProgress = compileInProgress,
        nowMs = nowMs,
    )

    val primary = remember(
        modelState,
        prefetchState,
        compileState,
        prefetchElapsedMs,
        compileElapsedMs,
    ) {
        when {
            modelState !is ModelDownloadController.ModelState.Ready -> modelLabelForUi(modelState)
            prefetchInProgress ->
                prefetchLabelForUi(
                    state = prefetchState,
                    elapsedMs = prefetchElapsedMs,
                    format = WARMUP_UI_FORMAT_GATE,
                )

            compileState is WarmupController.CompileState.WaitingForPrefetch ->
                compileLabelForUi(
                    state = compileState,
                    elapsedMs = compileElapsedMs,
                    format = WARMUP_UI_FORMAT_GATE,
                )

            compileState is WarmupController.CompileState.Compiling ->
                compileLabelForUi(
                    state = compileState,
                    elapsedMs = compileElapsedMs,
                    format = WARMUP_UI_FORMAT_GATE,
                )

            else -> WARMUP_UI_FORMAT_GATE.idleLabel
        }
    }

    val showRetryAll = remember(modelState, compileState) {
        modelState is ModelDownloadController.ModelState.Failed ||
                modelState is ModelDownloadController.ModelState.Cancelled ||
                compileState is WarmupController.CompileState.Failed ||
                compileState is WarmupController.CompileState.Cancelled
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Preparing the on-device model…",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = primary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(18.dp))

            val hint = remember(modelState, compileState) {
                when (modelState) {
                    is ModelDownloadController.ModelState.Downloading ->
                        "Downloading the model file. Keep the app open (first run may take a while)."

                    is ModelDownloadController.ModelState.Checking ->
                        "Checking local model file…"

                    is ModelDownloadController.ModelState.NotConfigured ->
                        "Model URL is not configured in SurveyConfig."

                    is ModelDownloadController.ModelState.Failed ->
                        "Download failed. Check connectivity / credentials / configuration."

                    is ModelDownloadController.ModelState.Cancelled ->
                        "Download cancelled."

                    else -> when (compileState) {
                        is WarmupController.CompileState.WaitingForPrefetch ->
                            "Waiting for prefetch to complete before compilation."

                        is WarmupController.CompileState.Compiling ->
                            "Compiling/initializing the model. UI may stutter briefly."

                        is WarmupController.CompileState.Failed ->
                            "Warmup compile failed. Try retry."

                        is WarmupController.CompileState.Cancelled ->
                            "Warmup compile cancelled. Try retry."

                        else -> ""
                    }
                }
            }

            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(18.dp))
            }

            if (showRetryAll) {
                OutlinedButton(onClick = onRetryAll) {
                    Text("Retry (model + warmup)")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}

@Composable
internal fun LogStateTransitions(
    modelState: ModelDownloadController.ModelState,
    prefetchState: WarmupController.PrefetchState,
    compileState: WarmupController.CompileState,
) {
    var prevModel by remember { mutableStateOf<String?>(null) }
    var prevPrefetch by remember { mutableStateOf<String?>(null) }
    var prevCompile by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(modelState) {
        val now = modelState.javaClass.simpleName
        if (prevModel != now) {
            SafeLog.d(SurveyAppRoot.TAG, "State: model=$now")
            prevModel = now
        }
    }

    LaunchedEffect(prefetchState) {
        val now = prefetchState.javaClass.simpleName
        if (prevPrefetch != now) {
            SafeLog.d(SurveyAppRoot.TAG, "State: prefetch=$now elapsedMs=${prefetchState.elapsedMs}")
            prevPrefetch = now
        }
    }

    LaunchedEffect(compileState) {
        val now = compileState.javaClass.simpleName
        if (prevCompile != now) {
            SafeLog.d(SurveyAppRoot.TAG, "State: compile=$now elapsedMs=${compileState.elapsedMs}")
            prevCompile = now
        }
    }
}

internal fun isPrefetchInProgress(state: WarmupController.PrefetchState): Boolean {
    return state is WarmupController.PrefetchState.Running
}

internal fun modelLabelForUi(state: ModelDownloadController.ModelState): String {
    return when (state) {
        is ModelDownloadController.ModelState.NotConfigured -> "Model not configured"
        is ModelDownloadController.ModelState.Idle -> "Idle"
        is ModelDownloadController.ModelState.Checking -> "Checking… ${formatElapsed(state.elapsedMs)}"
        is ModelDownloadController.ModelState.Downloading -> {
            val total = state.total
            if (total != null && total > 0L) {
                val pct = ((state.downloaded.toDouble() / total.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
                "Downloading ${pct}% ${formatElapsed(state.elapsedMs)}"
            } else {
                "Downloading ${formatElapsed(state.elapsedMs)}"
            }
        }

        is ModelDownloadController.ModelState.Ready -> "Ready"
        is ModelDownloadController.ModelState.Failed -> "Failed"
        is ModelDownloadController.ModelState.Cancelled -> "Cancelled"
    }
}

internal fun prefetchLabelForUi(
    state: WarmupController.PrefetchState,
    elapsedMs: Long,
    format: WarmupUiFormat,
): String {
    val name = state.javaClass.simpleName
    val elapsed = formatElapsed(elapsedMs)
    return when {
        isPrefetchInProgress(state) -> "${format.prefetchRunningPrefix} ($name) $elapsed"
        name.equals("Idle", ignoreCase = true) -> format.idleLabel
        else -> "$name $elapsed"
    }
}

internal fun compileLabelForUi(
    state: WarmupController.CompileState,
    elapsedMs: Long,
    format: WarmupUiFormat,
): String {
    val elapsed = formatElapsed(elapsedMs)
    return when (state) {
        is WarmupController.CompileState.WaitingForPrefetch -> "${format.compileWaitingPrefix} $elapsed"
        is WarmupController.CompileState.Compiling -> "${format.compileCompilingPrefix} $elapsed"
        is WarmupController.CompileState.Idle -> format.idleLabel
        else -> "${state.javaClass.simpleName} $elapsed"
    }
}

@Composable
internal fun rememberWarmupUiNowMs(
    inProgress: Boolean,
    tickIntervalMs: Long,
): Long {
    var uiNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(inProgress, tickIntervalMs) {
        if (!inProgress) return@LaunchedEffect
        while (isActive) {
            uiNowMs = SystemClock.elapsedRealtime()
            delay(tickIntervalMs)
        }
    }

    return uiNowMs
}

/**
 * Produces a smoothly increasing elapsedMs for UI even if the underlying state updates are sparse.
 */
@Composable
internal fun rememberDynamicElapsedMs(
    reportedElapsedMs: Long,
    inProgress: Boolean,
    nowMs: Long,
): Long {
    var baseElapsedMs by remember { mutableLongStateOf(reportedElapsedMs) }
    var baseNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(reportedElapsedMs, inProgress) {
        baseElapsedMs = reportedElapsedMs
        baseNowMs = SystemClock.elapsedRealtime()
    }

    return if (!inProgress) {
        reportedElapsedMs
    } else {
        val live = baseElapsedMs + (nowMs - baseNowMs)
        if (live < 0L) 0L else live
    }
}

@Composable
internal fun rememberWarmupUiLabels(
    prefetchState: WarmupController.PrefetchState,
    compileState: WarmupController.CompileState,
    tickIntervalMs: Long,
    format: WarmupUiFormat,
): Pair<String, String> {
    val prefetchInProgress = isPrefetchInProgress(prefetchState)
    val compileInProgress =
        compileState is WarmupController.CompileState.WaitingForPrefetch ||
                compileState is WarmupController.CompileState.Compiling

    val inProgress = prefetchInProgress || compileInProgress
    val nowMs = rememberWarmupUiNowMs(
        inProgress = inProgress,
        tickIntervalMs = tickIntervalMs,
    )

    val prefetchElapsedMs = rememberDynamicElapsedMs(
        reportedElapsedMs = prefetchState.elapsedMs,
        inProgress = prefetchInProgress,
        nowMs = nowMs,
    )
    val compileElapsedMs = rememberDynamicElapsedMs(
        reportedElapsedMs = compileState.elapsedMs,
        inProgress = compileInProgress,
        nowMs = nowMs,
    )

    val prefetchLabel = remember(prefetchState, prefetchElapsedMs, format) {
        prefetchLabelForUi(
            state = prefetchState,
            elapsedMs = prefetchElapsedMs,
            format = format,
        )
    }
    val compileLabel = remember(compileState, compileElapsedMs, format) {
        compileLabelForUi(
            state = compileState,
            elapsedMs = compileElapsedMs,
            format = format,
        )
    }

    return prefetchLabel to compileLabel
}

internal fun formatElapsed(ms: Long): String {
    if (ms < 1_000L) return "${ms}ms"
    val sec = ms / 1_000.0
    if (sec < 60.0) return String.format(Locale.US, "%.1fs", sec)
    val m = ms / 60_000L
    val s = (ms / 1_000L) % 60
    return String.format(Locale.US, "%dm %02ds", m, s)
}

internal fun sanitizeLabel(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "unknown"

    val safe = buildString {
        for (c in trimmed) {
            if (c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.') {
                append(c)
            }
            if (length >= 32) break
        }
    }

    return safe.ifBlank { "unknown" }
}