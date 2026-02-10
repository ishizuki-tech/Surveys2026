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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.negi.surveys.ui.ReviewQuestionLog
import com.negi.surveys.ui.ReviewScreen
import com.negi.surveys.ui.SurveyStartScreen

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyAppRoot(modifier: Modifier = Modifier) {
    val backStack: NavBackStack<NavKey> = rememberNavBackStack(Home)
    val nav = remember(backStack) { AppNavigator(backStack) }

    BackHandler(enabled = nav.canPop()) {
        nav.pop()
    }

    // NOTE:
    // - Stable prompts map for the current prototype.
    val prompts: Map<String, String> = remember {
        linkedMapOf(
            "Q1" to "Question prompt for Q1 (placeholder)",
            "Q2" to "Question prompt for Q2 (placeholder)"
        )
    }

    // NOTE:
    // - Session-lifetime review logs (questionId -> log).
    val reviewLogsState = remember { mutableStateMapOf<String, ReviewQuestionLog>() }

    val entries: (NavKey) -> NavEntry<NavKey> = remember {
        entryProvider {
            entry<Home> {
                HomeScreen(
                    onStartSurvey = { nav.startSurvey() },
                    onExport = { nav.goExport() }
                )
            }

            entry<SurveyStart> {
                SurveyStartScreen(
                    onBegin = { nav.beginQuestions("Q1") },
                    onBack = { nav.pop() }
                )
            }

            entry<Question> { key ->
                val prompt = prompts[key.id] ?: "Question prompt for ${key.id} (placeholder)"

                ChatQuestionScreen(
                    questionId = key.id,
                    prompt = prompt,
                    onNext = { log ->
                        // NOTE:
                        // - Persist full transcript for Review.
                        reviewLogsState[key.id] = log
                        Log.d(TAG, "Saved ReviewLog. qid=${key.id} skipped=${log.isSkipped} lines=${log.lines.size}")

                        when (key.id) {
                            "Q1" -> nav.goQuestion("Q2")
                            "Q2" -> nav.goReview()
                            else -> nav.goReview()
                        }
                    },
                    onBack = { nav.pop() }
                )
            }

            entry<Review> {
                // NOTE:
                // - Pass a stable snapshot list sorted by questionId.
                val logs = reviewLogsState.values.sortedBy { it.questionId }

                ReviewScreen(
                    logs = logs,
                    onExport = { nav.goExport() },
                    onBack = { nav.pop() }
                )
            }

            entry<Export> {
                ExportScreen(onBack = { nav.pop() })
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
                title = titleFor(nav.current),
                canPop = nav.canPop(),
                onBack = { nav.pop() }
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
