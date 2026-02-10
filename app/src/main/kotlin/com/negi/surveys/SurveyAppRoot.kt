/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav3 Frame)
 *  ---------------------------------------------------------------------
 *  File: SurveyAppRoot.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.negi.surveys.nav.AppNavigator
import com.negi.surveys.nav.Export
import com.negi.surveys.nav.Home
import com.negi.surveys.nav.Question
import com.negi.surveys.nav.Review
import com.negi.surveys.nav.SurveyStart
import com.negi.surveys.ui.ChatQuestionScreen
import com.negi.surveys.ui.ExportScreen
import com.negi.surveys.ui.HomeScreen
import com.negi.surveys.ui.ReviewScreen
import com.negi.surveys.ui.SurveyStartScreen

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyAppRoot(modifier: Modifier = Modifier) {
    val backStack: NavBackStack<NavKey> = rememberNavBackStack(Home)
    val nav = remember(backStack) { AppNavigator(backStack) }

    /**
     * Session-lifetime state holder.
     *
     * Notes:
     * - Owns Review/Export logs across navigation.
     * - Removes the need for remember() map-based storage in the root composable.
     */
    val sessionVm: SurveySessionViewModel = viewModel()

    /**
     * IMPORTANT:
     * - Drive UI (TopBar / BackHandler) directly from backStack so Compose can recompose reliably.
     * - Some wrapper properties (e.g., nav.current) may not be observable state.
     */
    val currentKey: NavKey by remember {
        derivedStateOf { backStack.lastOrNull() ?: Home }
    }
    val canPop: Boolean by remember {
        derivedStateOf { backStack.size > 1 }
    }

    BackHandler(enabled = canPop) {
        Log.d(TAG, "BackHandler: pop requested. stackSize=${backStack.size} current=${currentKey.javaClass.simpleName}")
        nav.pop()
    }

    LaunchedEffect(currentKey, canPop) {
        Log.d(TAG, "NavState: size=${backStack.size} canPop=$canPop current=${currentKey.javaClass.simpleName}")
    }

    /**
     * Stable prompts map for the current prototype.
     *
     * Notes:
     * - Keep stable across recompositions to avoid changing downstream vmKey/hash behaviors.
     */
    val prompts: Map<String, String> = remember {
        linkedMapOf(
            "Q1" to "Question prompt for Q1 (placeholder)",
            "Q2" to "Question prompt for Q2 (placeholder)"
        )
    }

    val entries: (NavKey) -> NavEntry<NavKey> = remember(nav, prompts, sessionVm) {
        entryProvider {
            entry<Home> {
                HomeScreen(
                    onStartSurvey = {
                        Log.d(TAG, "Home: startSurvey()")
                        nav.startSurvey()
                    },
                    onExport = {
                        Log.d(TAG, "Home: goExport()")
                        nav.goExport()
                    }
                )
            }

            entry<SurveyStart> {
                SurveyStartScreen(
                    onBegin = {
                        Log.d(TAG, "SurveyStart: beginQuestions(Q1)")
                        nav.beginQuestions("Q1")
                    },
                    onBack = {
                        Log.d(TAG, "SurveyStart: pop()")
                        nav.pop()
                    }
                )
            }

            entry<Question> { key ->
                val prompt = prompts[key.id] ?: "Question prompt for ${key.id} (placeholder)"

                ChatQuestionScreen(
                    questionId = key.id,
                    prompt = prompt,
                    onNext = { log ->
                        /**
                         * Persist full transcript to session VM.
                         *
                         * Notes:
                         * - Use log.questionId for safety (should match key.id).
                         * - Review/Export read from session VM (StateFlow) so they remain reactive.
                         */
                        sessionVm.upsertLog(log)

                        Log.d(
                            TAG,
                            "Saved ReviewLog -> sessionVm. qid=${log.questionId} keyId=${key.id} skipped=${log.isSkipped} lines=${log.lines.size}"
                        )

                        when (key.id) {
                            "Q1" -> {
                                Log.d(TAG, "Nav: Q1 -> Q2")
                                nav.goQuestion("Q2")
                            }

                            "Q2" -> {
                                Log.d(TAG, "Nav: Q2 -> Review")
                                nav.goReview()
                            }

                            else -> {
                                Log.d(TAG, "Nav: ${key.id} -> Review (default)")
                                nav.goReview()
                            }
                        }
                    },
                    onBack = {
                        Log.d(TAG, "Question(${key.id}): pop()")
                        nav.pop()
                    }
                )
            }

            entry<Review> {
                /**
                 * Reactive logs sourced from session VM.
                 *
                 * Notes:
                 * - This is the single source of truth.
                 * - If logs update while staying on Review, UI updates automatically.
                 */
                val logsState = sessionVm.logs.collectAsStateWithLifecycle()
                val logs = logsState.value

                LaunchedEffect(logs.size) {
                    Log.d(
                        TAG,
                        "Review: render logs.size=${logs.size} keys=${logs.joinToString { it.questionId }}"
                    )
                }

                ReviewScreen(
                    logs = logs,
                    onExport = {
                        Log.d(TAG, "Review: goExport()")
                        nav.goExport()
                    },
                    onBack = {
                        Log.d(TAG, "Review: pop()")
                        nav.pop()
                    }
                )
            }

            entry<Export> {
                /**
                 * Export screen should read from the same session VM.
                 *
                 * Notes:
                 * - Preferred pattern inside ExportScreen:
                 *   val vm: SurveySessionViewModel = viewModel()
                 *   val json by vm.exportJson.collectAsStateWithLifecycle()
                 */
                ExportScreen(
                    onBack = {
                        Log.d(TAG, "Export: pop()")
                        nav.pop()
                    }
                )
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),

        // NOTE:
        // - Prevent double-applying system insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),

        topBar = {
            CompactTopBar(
                title = titleFor(currentKey),
                canPop = canPop,
                onBack = {
                    Log.d(TAG, "TopBar: back clicked")
                    nav.pop()
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavDisplay(
                backStack = backStack,
                entryProvider = entries,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * A compact, deterministic top bar.
 *
 * Notes:
 * - Consumes statusBars inset.
 * - Screens should not add TOP safeDrawing again.
 */
@Composable
private fun CompactTopBar(
    title: String,
    canPop: Boolean,
    onBack: () -> Unit
) {
    val barHeight = 44.dp

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                if (canPop) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(40.dp))
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .weight(1f)
                )
            }
        }
    }
}

private fun titleFor(key: NavKey): String {
    return when (key) {
        is Home -> "Home"
        is SurveyStart -> "Survey Start"
        is Question -> "Question: ${key.id}"
        is Review -> "Review"
        is Export -> "Export"
        else -> "Survey App"
    }
}

private const val TAG = "SurveyAppRoot"
