/*
 * =====================================================================
 *  IshizukiTech LLC — Survey App (EGL Pbuffer Thread)
 *  ---------------------------------------------------------------------
 *  File: EglPbufferThread.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.surveys.slm

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Process
import com.negi.surveys.logging.AppLog
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A dedicated single-thread executor that owns a minimal EGL context (pbuffer surface).
 *
 * Why:
 * - Some GPU / delegate stacks may touch OpenGL ES APIs during initialization.
 * - If that happens on a thread with no current EGL context, you may see:
 *   "call to OpenGL ES API with no current context"
 *
 * This class creates a tiny 1x1 pbuffer and makes the EGL context current on the worker thread.
 * You can then run initialization/warmup work inside [withEglContext].
 */
class EglPbufferThread(
    threadName: String = "slm-egl",
    private val glesVersion: Int = 2,
) : Closeable {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, threadName).apply { isDaemon = true }
    }

    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    val coroutineContext: CoroutineContext get() = dispatcher

    private val initGate = CompletableDeferred<Unit>()
    private val stateRef = AtomicReference<EglState?>(null)

    private val loggedInitFailure = AtomicBoolean(false)
    private val loggedNoContext = AtomicBoolean(false)

    init {
        executor.execute {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Throwable) {
                // Best effort.
            }

            try {
                stateRef.set(createEglState(glesVersion))
            } catch (t: Throwable) {
                stateRef.set(null)
                if (loggedInitFailure.compareAndSet(false, true)) {
                    AppLog.w(
                        TAG,
                        "EGL init failed (will run without current context): glesVersion=$glesVersion err=${t.javaClass.simpleName}(${t.message})"
                    )
                }
            } finally {
                initGate.complete(Unit)
            }
        }
    }

    /**
     * Runs [block] on the EGL worker thread after EGL is initialized.
     *
     * This method:
     * - Switches execution to the single EGL thread.
     * - Waits for EGL init to complete.
     * - Re-asserts eglMakeCurrent before running the block (defensive).
     *
     * If EGL setup failed, the block still runs on the worker thread, but without
     * a guaranteed current EGL context.
     */
    suspend fun <T> withEglContext(block: suspend () -> T): T {
        return withContext(dispatcher) {
            initGate.await()

            val state = stateRef.get()
            if (state == null) {
                if (loggedNoContext.compareAndSet(false, true)) {
                    AppLog.w(TAG, "EGL context unavailable; executing block without eglMakeCurrent.")
                }
                return@withContext block()
            }

            runCatching {
                EGL14.eglMakeCurrent(state.display, state.surface, state.surface, state.context)
            }.onFailure {
                if (loggedNoContext.compareAndSet(false, true)) {
                    AppLog.w(
                        TAG,
                        "eglMakeCurrent failed; executing block anyway: err=${it.javaClass.simpleName}(${it.message})"
                    )
                }
            }

            block()
        }
    }

    /**
     * Backward-compatible helper for non-suspending work.
     */
    suspend fun <T> runOnEglThread(block: () -> T): T {
        return withEglContext { block() }
    }

    override fun close() {
        executor.execute {
            runCatching { stateRef.getAndSet(null)?.close() }
        }
        executor.shutdown()
        runCatching { dispatcher.close() }
    }

    private data class EglState(
        val display: EGLDisplay,
        val surface: EGLSurface,
        val context: EGLContext,
    ) : Closeable {
        override fun close() {
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            runCatching { EGL14.eglDestroySurface(display, surface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    private fun createEglState(glesVersion: Int): EglState {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var context: EGLContext = EGL14.EGL_NO_CONTEXT

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            require(display != EGL14.EGL_NO_DISPLAY) { "EGL display not available." }

            val version = IntArray(2)
            require(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed." }

            runCatching { EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API) }

            val renderable = if (glesVersion >= 3) EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, renderable,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            require(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)) {
                "eglChooseConfig failed."
            }
            require(numConfigs[0] > 0) { "No EGLConfig found." }
            val config = requireNotNull(configs[0]) { "EGLConfig was null." }

            val ctxAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, glesVersion,
                EGL14.EGL_NONE
            )

            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT && glesVersion >= 3) {
                // Fallback to ES2 if ES3 context creation fails.
                val ctxAttribs2 = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
                )
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs2, 0)
            }
            require(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed." }

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
            require(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed." }

            require(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed." }

            return EglState(display, surface, context)
        } catch (t: Throwable) {
            // Best-effort cleanup for partial init.
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
            throw t
        }
    }

    private companion object {
        private const val TAG = "EglPbufferThread"

        // Khronos EGL_OPENGL_ES3_BIT_KHR = 0x00000040
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x00000040
    }
}