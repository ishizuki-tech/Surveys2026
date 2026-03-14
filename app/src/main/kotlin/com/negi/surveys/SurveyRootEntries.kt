/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (Root Nav Entries)
 *  ---------------------------------------------------------------------
 *  File: SurveyRootEntries.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.surveys

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.negi.surveys.config.NodeDTO
import com.negi.surveys.config.NodeType
import com.negi.surveys.config.SurveyConfig
import com.negi.surveys.logging.SafeLog
import com.negi.surveys.nav.AppNavigator
import com.negi.surveys.nav.Export
import com.negi.surveys.nav.Home
import com.negi.surveys.nav.Question
import com.negi.surveys.nav.Review
import com.negi.surveys.nav.SurveyStart
import com.negi.surveys.ui.DebugInfo
import com.negi.surveys.ui.DebugRow
import com.negi.surveys.ui.ExportScreen
import com.negi.surveys.ui.HomeScreen
import com.negi.surveys.ui.ReviewQuestionLog
import com.negi.surveys.ui.ReviewScreen
import com.negi.surveys.ui.SurveyStartScreen
import com.negi.surveys.ui.chat.ChatQuestionScreen
import com.negi.surveys.utils.ModelDownloadController
import com.negi.surveys.warmup.WarmupController

/**
 * Builds the root navigation entries without storing extra flow collectors inside destinations.
 *
 * Important:
 * - Root owns all startup / warmup collectors.
 * - Destination screens receive snapshot State values only.
 * - Warmup satisfied hint must be threaded into SurveyStart so the screen can
 *   skip redundant compile when startup already accepted a reusable stamp.
 */
@Composable
internal fun rememberSurveyNavEntries(
    nav: AppNavigator,
    surveyConfig: SurveyConfig?,
    sessionVm: SurveySessionViewModel,
    logsState: State<List<ReviewQuestionLog>>,
    exportTextState: State<String>,
    startManualUpload: (String) -> Unit,
    debugInfoState: State<DebugInfo>,
    warmup: WarmupController?,
    launchRetryAll: (String) -> Unit,
    uploadStatusState: State<String?>,
    onDeviceEnabled: Boolean,
    warmupSatisfiedHintState: State<Boolean>,
    rootModelStateState: State<ModelDownloadController.ModelState>,
    rootPrefetchStateState: State<WarmupController.PrefetchState>,
    rootCompileStateState: State<WarmupController.CompileState>,
): (NavKey) -> NavEntry<NavKey> {
    val surveyFlow = remember(surveyConfig) {
        SurveyRootEntriesSupport.SurveyFlowPlan.from(surveyConfig)
    }

    return remember(
        nav,
        surveyFlow,
        sessionVm,
        logsState,
        exportTextState,
        startManualUpload,
        debugInfoState,
        warmup,
        launchRetryAll,
        uploadStatusState,
        onDeviceEnabled,
        warmupSatisfiedHintState,
        rootModelStateState,
        rootPrefetchStateState,
        rootCompileStateState,
    ) {
        entryProvider {
            entry<Home> {
                HomeScreen(
                    onStartSurvey = { nav.startSurvey() },
                    onExport = { nav.goExport() },
                    debugInfo = debugInfoState.value,
                )
            }

            entry<SurveyStart> {
                val hasQuestionFlow = surveyFlow.questionCount > 0
                val warmupSatisfiedHint = warmupSatisfiedHintState.value

                if (!hasQuestionFlow) {
                    SurveyStartScreen(
                        onBegin = {
                            SafeLog.i(
                                SurveyRootEntriesSupport.TAG,
                                "beginSurvey: no question nodes in graph -> review",
                            )
                            nav.goReview()
                        },
                        onBack = { nav.pop() },
                        warmupController = null,
                        requireWarmup = false,
                        warmupSatisfiedHint = true,
                        debugInfo = debugInfoState.value,
                        descriptionText = "This survey graph has no question nodes. Continuing will open the Review screen.",
                        beginLabel = "Open Review",
                    )
                } else {
                    val readyWarmup = warmup
                    if (readyWarmup == null) {
                        SurveyAppShell.BlockingBody(
                            title = "Preparing app services…",
                            detail = "Warmup service is not ready yet.",
                            showSpinner = true,
                        )
                    } else {
                        SafeLog.d(
                            SurveyRootEntriesSupport.TAG,
                            "surveyStartEntry: requireWarmup=true hint=$warmupSatisfiedHint " +
                                    "model=${rootModelStateState.value.javaClass.simpleName} " +
                                    "prefetch=${rootPrefetchStateState.value.javaClass.simpleName} " +
                                    "compile=${rootCompileStateState.value.javaClass.simpleName}",
                        )

                        GateOrContent(
                            enabled = onDeviceEnabled,
                            policy = GatePolicy.MODEL_ONLY,
                            modelState = rootModelStateState.value,
                            prefetchState = rootPrefetchStateState.value,
                            compileState = rootCompileStateState.value,
                            onBack = { nav.pop() },
                            onRetryAll = { launchRetryAll("surveyStartGate") },
                        ) {
                            SurveyStartScreen(
                                onBegin = {
                                    val firstQuestionId = surveyFlow.startQuestionId
                                    if (firstQuestionId != null) {
                                        nav.beginQuestions(firstQuestionId)
                                    } else {
                                        SafeLog.w(
                                            SurveyRootEntriesSupport.TAG,
                                            "beginSurvey: question flow expected but unresolved -> review",
                                        )
                                        nav.goReview()
                                    }
                                },
                                onBack = { nav.pop() },
                                warmupController = readyWarmup,
                                requireWarmup = true,
                                warmupSatisfiedHint = warmupSatisfiedHint,
                                debugInfo = debugInfoState.value,
                                descriptionText = "This step prepares the survey session before opening the first question.",
                                beginLabel = "Begin Survey",
                            )
                        }
                    }
                }
            }

            entry<Question> { key ->
                val prompt = surveyFlow.promptFor(key.id)

                GateOrLatchedContent(
                    enabled = onDeviceEnabled,
                    policy = GatePolicy.MODEL_PREFETCH_COMPILE,
                    latchKey = key.id,
                    modelState = rootModelStateState.value,
                    prefetchState = rootPrefetchStateState.value,
                    compileState = rootCompileStateState.value,
                    onBack = { nav.pop() },
                    onRetryAll = { launchRetryAll("questionGate") },
                ) {
                    ChatQuestionScreen(
                        questionId = key.id,
                        prompt = prompt,
                        onNext = { log ->
                            sessionVm.upsertLog(log)
                            val nextQuestionId = surveyFlow.nextQuestionAfter(key.id)
                            if (nextQuestionId != null) {
                                nav.goQuestion(nextQuestionId)
                            } else {
                                nav.goReview()
                            }
                        },
                        onBack = { nav.pop() },
                    )
                }
            }

            entry<Review> {
                ReviewScreen(
                    logs = logsState.value,
                    onExport = { nav.goExport() },
                    onBack = { nav.pop() },
                    onUploadLogs = { startManualUpload("review") },
                    uploadStatusLine = uploadStatusState.value,
                )
            }

            entry<Export> {
                ExportScreen(
                    exportText = exportTextState.value,
                    onBack = { nav.pop() },
                )
            }
        }
    }
}

/**
 * File-local support types and helpers for root navigation entry construction.
 */
private object SurveyRootEntriesSupport {
    const val TAG: String = "SurveyRootEntries"

    data class SurveyFlowPlan(
        val startQuestionId: String?,
        val questionCount: Int,
        private val nodeById: Map<String, NodeDTO>,
        private val nextQuestionIdByQuestionId: Map<String, String?>,
    ) {
        fun promptFor(questionIdRaw: String): String {
            val questionId = questionIdRaw.trim()
            val node = nodeById[questionId]
            return if (node != null) {
                buildQuestionPrompt(node)
            } else {
                "Question: $questionId"
            }
        }

        fun nextQuestionAfter(questionIdRaw: String): String? {
            val questionId = questionIdRaw.trim()
            val cached = nextQuestionIdByQuestionId[questionId]
            if (questionId in nextQuestionIdByQuestionId) return cached

            val nextId = nodeById[questionId]?.nextId
            return resolveFirstQuestionId(nextId, nodeById)
        }

        companion object {
            const val MAX_GRAPH_HOPS: Int = 256

            fun from(surveyConfig: SurveyConfig?): SurveyFlowPlan {
                if (surveyConfig == null) {
                    return SurveyFlowPlan(
                        startQuestionId = null,
                        questionCount = 0,
                        nodeById = emptyMap(),
                        nextQuestionIdByQuestionId = emptyMap(),
                    )
                }

                val nodes = LinkedHashMap<String, NodeDTO>()
                surveyConfig.graph.nodes.forEach { node ->
                    val nodeId = node.id.trim()
                    if (nodeId.isNotBlank() && !nodes.containsKey(nodeId)) {
                        nodes[nodeId] = node
                    }
                }

                val resolvedStartId =
                    surveyConfig.graph.startId
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?: nodes.values.firstOrNull { node ->
                            node.nodeType() == NodeType.START
                        }?.id?.trim()

                val questionIds = nodes
                    .filterValues { node -> isQuestionNode(node) }
                    .keys

                val nextQuestionIdByQuestionId = LinkedHashMap<String, String?>()
                nodes.forEach { (nodeId, node) ->
                    if (isQuestionNode(node)) {
                        nextQuestionIdByQuestionId[nodeId] =
                            resolveFirstQuestionId(node.nextId, nodes)
                    }
                }

                val startQuestionId = resolveFirstQuestionId(resolvedStartId, nodes)
                SafeLog.i(
                    TAG,
                    "surveyFlow: nodes=${nodes.size} questions=${questionIds.size} startQuestionId=${startQuestionId ?: "none"}",
                )

                return SurveyFlowPlan(
                    startQuestionId = startQuestionId,
                    questionCount = questionIds.size,
                    nodeById = nodes,
                    nextQuestionIdByQuestionId = nextQuestionIdByQuestionId,
                )
            }
        }
    }

    private fun resolveFirstQuestionId(
        startIdRaw: String?,
        nodeById: Map<String, NodeDTO>,
    ): String? {
        var cursor = startIdRaw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val visited = LinkedHashSet<String>()
        var hops = 0

        while (hops < SurveyFlowPlan.MAX_GRAPH_HOPS && visited.add(cursor)) {
            val node = nodeById[cursor] ?: return null
            if (isQuestionNode(node)) {
                return cursor
            }
            cursor = node.nextId?.trim()?.takeIf { it.isNotBlank() } ?: return null
            hops += 1
        }

        return null
    }

    private fun isQuestionNode(node: NodeDTO): Boolean {
        return when (node.nodeType()) {
            NodeType.START,
            NodeType.REVIEW,
            NodeType.DONE,
            NodeType.UNKNOWN -> false

            NodeType.TEXT,
            NodeType.SINGLE_CHOICE,
            NodeType.MULTI_CHOICE,
            NodeType.AI -> true
        }
    }

    private fun buildQuestionPrompt(node: NodeDTO): String {
        val body = node.question.trim()
            .ifBlank { node.title.trim() }
            .ifBlank { "Question: ${node.id.trim()}" }

        val options = node.options
            .mapNotNull { option -> option.trim().takeIf { it.isNotBlank() } }

        if (options.isEmpty()) return body

        val renderedOptions = options
            .mapIndexed { index, option -> "${index + 1}. $option" }
            .joinToString(separator = "\n")

        return "$body\n$renderedOptions"
    }
}