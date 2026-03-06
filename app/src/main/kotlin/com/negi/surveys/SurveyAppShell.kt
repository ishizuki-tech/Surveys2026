/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Nav Shell)
 *  ---------------------------------------------------------------------
 *  File: SurveyAppShell.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.negi.surveys.chat.ChatStreamBridge
import com.negi.surveys.chat.ChatValidation
import com.negi.surveys.ui.LocalChatStreamBridge
import com.negi.surveys.ui.chat.LocalRepositoryI

/**
 * Pure UI shell for the app root.
 *
 * Responsibilities:
 * - Scaffold and top bar
 * - Root-level blocking placeholder
 * - CompositionLocal providers
 * - NavDisplay host
 *
 * Non-responsibilities:
 * - Startup orchestration
 * - Retry logic
 * - Service acquisition
 */
object SurveyAppShell {

    @Composable
    operator fun invoke(
        modifier: Modifier = Modifier,
        title: String,
        canPop: Boolean,
        onBack: () -> Unit,
        backStack: NavBackStack<NavKey>,
        entryProvider: (NavKey) -> NavEntry<NavKey>,
        streamBridge: ChatStreamBridge,
        repository: ChatValidation.RepositoryI?,
        blockingTitle: String?,
        blockingDetail: String?,
        showBlockingSpinner: Boolean,
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                CompactTopBar(
                    title = title,
                    canPop = canPop,
                    onBack = onBack,
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                val showBlocking = repository == null || blockingTitle != null

                if (showBlocking) {
                    BlockingBody(
                        title = blockingTitle ?: "Preparing app services…",
                        detail = blockingDetail ?: "",
                        showSpinner = showBlockingSpinner,
                    )
                } else {
                    CompositionLocalProvider(
                        LocalChatStreamBridge provides streamBridge,
                        LocalRepositoryI provides repository,
                    ) {
                        NavDisplay(
                            backStack = backStack,
                            entryProvider = entryProvider,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    /**
     * Shared blocking body used by the shell and by root-level entry placeholders.
     */
    @Composable
    fun BlockingBody(
        title: String,
        detail: String,
        showSpinner: Boolean,
    ) {
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
                if (showSpinner) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )

                if (detail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    @Composable
    private fun CompactTopBar(
        title: String,
        canPop: Boolean,
        onBack: () -> Unit,
    ) {
        val barHeight = 44.dp

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    if (canPop) {
                        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
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
                            .weight(1f),
                    )
                }
            }
        }
    }
}