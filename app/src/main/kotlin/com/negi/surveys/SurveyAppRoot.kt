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
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    // NOTE:
    // - Do NOT provide type arguments to rememberNavBackStack in your current Nav3 version.
    // - Ensure Home implements NavKey (via AppNavKey : NavKey).
    val backStack: NavBackStack<NavKey> = rememberNavBackStack(Home)

    val nav = remember(backStack) { AppNavigator(backStack) }

    BackHandler(enabled = nav.canPop()) {
        nav.pop()
    }

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
//            entry<Question> { key ->
//                QuestionScreen(
//                    questionId = key.id,
//                    onNext = {
//                        when (key.id) {
//                            "Q1" -> nav.goQuestion("Q2")
//                            "Q2" -> nav.goReview()
//                            else -> nav.goReview()
//                        }
//                    },
//                    onBack = { nav.pop() }
//                )
//            }
            entry<Question> { key ->
                ChatQuestionScreen(
                    questionId = key.id,
                    prompt = "Question prompt for ${key.id} (placeholder)",
                    onNext = { _combinedAnswer ->
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
                ReviewScreen(
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
        topBar = {
            TopAppBar(
                title = { Text(titleFor(nav.current)) },
                navigationIcon = {
                    if (nav.canPop()) {
                        IconButton(onClick = { nav.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
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
