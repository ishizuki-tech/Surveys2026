/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Root UI Shell)
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
        onBlockingRetry: (() -> Unit)? = null,
    ) {
        val blockingSpec =
            rememberShellBlockingSpec(
                repository = repository,
                blockingTitle = blockingTitle,
                blockingDetail = blockingDetail,
                showBlockingSpinner = showBlockingSpinner,
                retryAvailable = onBlockingRetry != null,
            )

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
            AppBody(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                backStack = backStack,
                entryProvider = entryProvider,
                streamBridge = streamBridge,
                repository = repository,
                blockingSpec = blockingSpec,
                onBlockingRetry = onBlockingRetry,
            )
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
        onRetry: (() -> Unit)? = null,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier =
                    Modifier
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

                if (onRetry != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onRetry) {
                        Text("Retry startup")
                    }
                }
            }
        }
    }

    @Composable
    private fun AppBody(
        modifier: Modifier,
        backStack: NavBackStack<NavKey>,
        entryProvider: (NavKey) -> NavEntry<NavKey>,
        streamBridge: ChatStreamBridge,
        repository: ChatValidation.RepositoryI?,
        blockingSpec: ShellBlockingSpec,
        onBlockingRetry: (() -> Unit)?,
    ) {
        Box(modifier = modifier) {
            val readyRepository = repository
            if (blockingSpec.visible || readyRepository == null) {
                BlockingBody(
                    title = blockingSpec.title,
                    detail = blockingSpec.detail,
                    showSpinner = blockingSpec.showSpinner,
                    onRetry = if (blockingSpec.showRetry) onBlockingRetry else null,
                )
            } else {
                ReadyNavHost(
                    backStack = backStack,
                    entryProvider = entryProvider,
                    streamBridge = streamBridge,
                    repository = readyRepository,
                )
            }
        }
    }

    /**
     * Hosts the navigation tree after startup/service blocking is cleared.
     */
    @Composable
    private fun ReadyNavHost(
        backStack: NavBackStack<NavKey>,
        entryProvider: (NavKey) -> NavEntry<NavKey>,
        streamBridge: ChatStreamBridge,
        repository: ChatValidation.RepositoryI,
    ) {
        CompositionLocalProvider(
            LocalChatStreamBridge provides streamBridge,
            LocalRepositoryI provides repository,
        ) {
            NavDisplay(
                backStack = backStack,
                entryProvider = entryProvider,
            )
        }
    }

    /**
     * Root top bar with an optional back button.
     */
    @Composable
    private fun CompactTopBar(
        title: String,
        canPop: Boolean,
        onBack: () -> Unit,
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(
                    modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars),
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canPop) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }

                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    /**
     * Resolves whether the shell should show a blocking placeholder.
     *
     * Design:
     * - Explicit blocking state from the caller wins.
     * - A missing repository without explicit blocking state is treated as a safe fallback state.
     * - Retry is shown only when it is meaningful for the current blocking mode.
     */
    @Composable
    private fun rememberShellBlockingSpec(
        repository: ChatValidation.RepositoryI?,
        blockingTitle: String?,
        blockingDetail: String?,
        showBlockingSpinner: Boolean,
        retryAvailable: Boolean,
    ): ShellBlockingSpec {
        return remember(
            repository,
            blockingTitle,
            blockingDetail,
            showBlockingSpinner,
            retryAvailable,
        ) {
            val explicitBlock =
                !blockingTitle.isNullOrBlank() ||
                        !blockingDetail.isNullOrBlank() ||
                        showBlockingSpinner

            when {
                explicitBlock -> {
                    val resolvedTitle =
                        when {
                            !blockingTitle.isNullOrBlank() -> blockingTitle
                            showBlockingSpinner -> "Preparing app services…"
                            else -> "Startup needs attention"
                        }

                    ShellBlockingSpec(
                        visible = true,
                        title = resolvedTitle,
                        detail = blockingDetail.orEmpty(),
                        showSpinner = showBlockingSpinner,
                        showRetry = retryAvailable && !showBlockingSpinner,
                    )
                }

                repository == null -> {
                    ShellBlockingSpec(
                        visible = true,
                        title = "Preparing app services…",
                        detail =
                            if (retryAvailable) {
                                "Startup is taking longer than expected. If this screen does not clear, retry startup."
                            } else {
                                "Repository is not ready yet."
                            },
                        showSpinner = true,
                        showRetry = retryAvailable,
                    )
                }

                else -> {
                    ShellBlockingSpec(
                        visible = false,
                        title = "",
                        detail = "",
                        showSpinner = false,
                        showRetry = false,
                    )
                }
            }
        }
    }
    /**
     * Shell blocking placeholder spec.
     */
    private data class ShellBlockingSpec(
        val visible: Boolean,
        val title: String,
        val detail: String,
        val showSpinner: Boolean,
        val showRetry: Boolean,
    )
}

