/*
 * =====================================================================
 *  IshizukiTech LLC — SurveyApp
 *  ---------------------------------------------------------------------
 *  File: app/build.gradle.kts
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)

    // NOTE:
    // - AGP 9+ has built-in Kotlin support.
    // - Do NOT apply org.jetbrains.kotlin.android (kotlin-android) here. :contentReference[oaicite:1]{index=1}

    // Compose compiler Gradle plugin (should map to id "org.jetbrains.kotlin.plugin.compose")
    // See Compose compiler plugin setup docs. :contentReference[oaicite:2]{index=2}
    alias(libs.plugins.kotlin.compose)

    // Kotlinx Serialization compiler plugin
    alias(libs.plugins.kotlin.serialization)
}

/* ============================================================================
 * Shared helpers (Properties / env / quoting)
 * ========================================================================== */

/** True when running under CI (GitHub Actions sets CI=true). */
val isCi: Boolean = System.getenv("CI")?.equals("true", ignoreCase = true) == true

/**
 * Load local.properties once.
 *
 * This file is developer-local and should NOT be committed.
 * We use it for safe overrides (appId, local tokens, version overrides, etc.).
 */
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Resolve a property from (highest priority first):
 *  1) Gradle project property: -Pname=value  OR  ~/.gradle/gradle.properties
 *  2) local.properties (developer-local)
 *  3) default
 *
 * This keeps CI reproducible while allowing local convenience overrides.
 */
fun prop(name: String, default: String = ""): String =
    (project.findProperty(name) as String?)
        ?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)
            ?.takeIf { it.isNotBlank() }
        ?: default

/**
 * Escape a string literal for BuildConfig fields.
 *
 * This is required because buildConfigField takes a raw Java literal string,
 * not a Kotlin string.
 */
fun quote(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Sanitize versionName for Android + Git tags:
 * - Must not contain spaces
 * - Keep it short and predictable for log aggregation
 */
fun sanitizeVersionName(raw: String): String =
    raw.trim()
        .replace("\\s+".toRegex(), "") // versionName must be tag-safe
        .take(64) // defensive bound to avoid insane strings

/**
 * Resolve versionName with explicit precedence:
 *  1) -Papp.versionName=...
 *  2) env CI_APP_VERSION_NAME
 *  3) local.properties app.versionName=...
 *  4) fallback
 */
fun resolveVersionName(): String {
    val fromGradle = (project.findProperty("app.versionName") as String?)?.trim()
    val fromEnv = System.getenv("CI_APP_VERSION_NAME")?.trim()
    val fromLocal = prop("app.versionName").trim()

    val raw = when {
        !fromGradle.isNullOrBlank() -> fromGradle
        !fromEnv.isNullOrBlank() -> fromEnv
        fromLocal.isNotBlank() -> fromLocal
        else -> "0.0.1"
    }
    return sanitizeVersionName(raw)
}

/**
 * Resolve versionCode with explicit precedence:
 *  1) -Papp.versionCode=...
 *  2) env CI_VERSION_CODE
 *  3) env GITHUB_RUN_NUMBER (nice CI default)
 *  4) fallback
 */
fun resolveVersionCode(): Int {
    val fromGradle = (project.findProperty("app.versionCode") as String?)?.toIntOrNull()
    val fromEnv = System.getenv("CI_VERSION_CODE")?.toIntOrNull()
    val fromRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    return fromGradle ?: fromEnv ?: fromRunNumber ?: 1
}

/**
 * Reads a Gradle property as a non-blank string (or null).
 *
 * Resolution order:
 * 1) Standard Gradle properties (gradle.properties, ~/.gradle/gradle.properties, -Pname=..., ORG_GRADLE_PROJECT_*)
 * 2) <repoRoot>/gradle.properties.local (gitignored)
 * 3) <repoRoot>/local.properties (gitignored; common in Android)
 *
 * Notes:
 * - Keep this helper tiny and dependency-free.
 * - Never log returned values (may contain secrets).
 */
fun gradleProp(name: String): String? {
    // 1) Standard Gradle properties (includes ~/.gradle/gradle.properties)
    val fromGradle = providers.gradleProperty(name).orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (fromGradle != null) return fromGradle

    fun readFromFile(fileName: String): String? {
        val f = rootProject.file(fileName)
        if (!f.exists() || !f.isFile) return null
        val prefix = "$name="
        val line = runCatching { f.readLines() }.getOrNull()
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it.startsWith(prefix) }
            ?: return null
        return line.substringAfter("=", "").trim().takeIf { it.isNotEmpty() }
    }

    // 2) Local gitignored override
    readFromFile("gradle.properties.local")?.let { return it }

    // 3) Android standard local file
    readFromFile("local.properties")?.let { return it }

    return null
}

/**
 * Read the first non-blank property value from multiple keys.
 *
 * Notes:
 * - Useful during migration (e.g., github.* -> gh.*).
 */
fun gradlePropAny(vararg names: String): String? =
    names.asSequence().mapNotNull { gradleProp(it) }.firstOrNull()

/**
 * Returns true if this project looks like a git checkout.
 *
 * Notes:
 * - `.git` can be a directory or a file (worktrees); exists() covers both.
 */
fun Project.hasGitRepo(): Boolean = rootProject.file(".git").exists()

/**
 * Executes a command and returns stdout as a trimmed string.
 *
 * Notes:
 * - Best-effort and intentionally ignores failures by returning empty string.
 * - Do NOT use this to capture any sensitive/user content.
 * - Forces workingDir to the repo root for stability.
 * - Uses ProcessBuilder to avoid Gradle exec overload/deprecation issues.
 */
fun Project.execAndGetStdout(vararg args: String): String {
    return runCatching {
        if (args.isEmpty()) return@runCatching ""

        val pb = ProcessBuilder(args.toList())
            .directory(rootProject.rootDir)
            .redirectErrorStream(true)

        val p = pb.start()
        val text = p.inputStream.bufferedReader().use { it.readText() }

        // Avoid hanging Gradle configuration forever.
        val finished = p.waitFor(8, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            return@runCatching ""
        }

        text.trim()
    }.getOrElse { t ->
        // Keep logs minimal and non-sensitive.
        logger.info("execAndGetStdout failed: cmd=${args.firstOrNull() ?: "?"} err=${t.javaClass.simpleName}")
        ""
    }
}

/**
 * Returns short git SHA (8 chars) or "nogit" if unavailable.
 */
fun Project.gitShaShort(): String {
    if (!hasGitRepo()) return "nogit"
    val s = execAndGetStdout("git", "rev-parse", "--short=8", "HEAD")
    return if (s.isNotBlank()) s else "nogit"
}

/**
 * Returns true if git working tree has uncommitted changes.
 */
fun Project.gitDirty(): Boolean {
    if (!hasGitRepo()) return false
    val s = execAndGetStdout("git", "status", "--porcelain")
    return s.isNotBlank()
}

/**
 * Resolves a deterministic-ish build time string.
 *
 * Priority:
 * 1) Gradle property: build.timeUtc
 * 2) Environment variable: BUILD_TIME_UTC
 * 3) Now (Instant)
 */
fun buildTimeUtc(): String {
    val p = gradleProp("build.timeUtc")
    if (!p.isNullOrBlank()) return p

    val env = System.getenv("BUILD_TIME_UTC")?.trim()
    if (!env.isNullOrBlank()) return env

    return Instant.now().toString()
}

android {
    // BuildConfig is generated under this namespace.
    namespace = "com.negi.surveys"

    compileSdk = 36

    // ---- Config resolution (supports both "github.*" and legacy "gh.*") ----
    val ghOwner = gradlePropAny("github.owner", "gh.owner") ?: ""
    val ghRepo = gradlePropAny("github.repo", "gh.repo") ?: "SurveyExports"
    val ghBranch = gradlePropAny("github.branch", "gh.branch") ?: "main"
    val ghPathPrefix = gradlePropAny("github.pathPrefix", "gh.pathPrefix") ?: ""
    val ghLogPrefix = gradlePropAny("github.logPrefix", "gh.logPrefix") ?: "surveyapp"
    val ghToken = gradlePropAny("github.token", "gh.token") ?: ""

    val hfToken = gradlePropAny("hf.token", "HF_TOKEN") ?: ""

    val supabaseUrl = gradleProp("supabase.url") ?: ""
    val supabaseAnonKey = gradleProp("supabase.anonKey") ?: ""
    val supabaseLogBucket = gradleProp("supabase.logBucket") ?: "logs"
    val supabaseLogPrefix = gradleProp("supabase.logPrefix") ?: "surveyapp"

    /**
     * By default, secrets are NOT embedded in release builds.
     * To explicitly allow (internal builds only), set:
     *   -Prelease.allowSecrets=true
     * or in gradle.properties.local:
     *   release.allowSecrets=true
     */
    val allowSecretsInRelease = (gradlePropAny("release.allowSecrets", "release.allowTokens") ?: "")
        .equals("true", ignoreCase = true)

    defaultConfig {
        applicationId = "com.negi.surveys"

        minSdk = 26
        targetSdk = 36
        versionCode = resolveVersionCode()
        versionName = resolveVersionName()

        // Build fingerprint (PII-safe, deterministic enough for debugging).
        val sha = rootProject.gitShaShort()
        val dirty = rootProject.gitDirty()
        val timeUtc = buildTimeUtc()

        buildConfigField("String", "GIT_SHA", quote(sha))
        buildConfigField("boolean", "GIT_DIRTY", dirty.toString())
        buildConfigField("String", "BUILD_TIME_UTC", quote(timeUtc))

        // Optional Supabase config (anon key is "public-ish" but still keep it out of logs).
        buildConfigField("String", "SUPABASE_URL", quote(supabaseUrl))
        buildConfigField("String", "SUPABASE_ANON_KEY", quote(supabaseAnonKey))
        buildConfigField("String", "SUPABASE_LOG_BUCKET", quote(supabaseLogBucket))
        buildConfigField("String", "SUPABASE_LOG_PREFIX", quote(supabaseLogPrefix))

        // GitHub upload config (KEEP TOKENS OUT OF GIT).
        // Provide both GH_* and GITHUB_* for backward compatibility.
        buildConfigField("String", "GH_OWNER", quote(ghOwner))
        buildConfigField("String", "GH_REPO", quote(ghRepo))
        buildConfigField("String", "GH_BRANCH", quote(ghBranch))
        buildConfigField("String", "GH_PATH_PREFIX", quote(ghPathPrefix))
        buildConfigField("String", "GH_LOG_PREFIX", quote(ghLogPrefix))

        buildConfigField("String", "GITHUB_OWNER", quote(ghOwner))
        buildConfigField("String", "GITHUB_REPO", quote(ghRepo))
        buildConfigField("String", "GITHUB_BRANCH", quote(ghBranch))
        buildConfigField("String", "GITHUB_LOG_PREFIX", quote(ghLogPrefix))

        // Tokens must exist on all variants (compile-time), but default to empty.
        buildConfigField("String", "GH_TOKEN", quote(""))
        buildConfigField("String", "GITHUB_TOKEN", quote(""))
        buildConfigField("String", "HF_TOKEN", quote(""))
    }

    buildTypes {
        debug {
            // IMPORTANT: Debug builds only. Never include tokens in production releases.
            buildConfigField("String", "GH_TOKEN", quote(ghToken))
            buildConfigField("String", "GITHUB_TOKEN", quote(ghToken))
            buildConfigField("String", "HF_TOKEN", quote(hfToken))
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            /**
             * Debug signing for CI/dev convenience.
             * For production distribution, switch to a proper release keystore.
             */
            signingConfig = signingConfigs.getByName("debug")

            val ghTokenRelease = if (allowSecretsInRelease) ghToken else ""
            val hfTokenRelease = if (allowSecretsInRelease) hfToken else ""

            buildConfigField("String", "GH_TOKEN", quote(ghTokenRelease))
            buildConfigField("String", "GITHUB_TOKEN", quote(ghTokenRelease))
            buildConfigField("String", "HF_TOKEN", quote(hfTokenRelease))
        }
    }

    buildFeatures {
        compose = true
        // IMPORTANT: Ensure BuildConfig is generated.
        buildConfig = true
    }

    compileOptions {
        // Keep Java bytecode target consistent with Kotlin.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    /**
     * Optional pins (avoid breaking clean machines / CI):
     *
     * Usage:
     * - gradle.properties:
     *     android.buildToolsVersion=35.0.0
     *     android.ndkVersion=27.0.12077973
     */
    val pinnedBuildTools = gradleProp("android.buildToolsVersion")
    if (pinnedBuildTools != null) {
        buildToolsVersion = pinnedBuildTools
    }

    val pinnedNdk = gradleProp("android.ndkVersion")
    if (pinnedNdk != null) {
        ndkVersion = pinnedNdk
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Navigation3 (alpha) - keep consistent within this module.
    implementation(libs.androidx.navigation3.runtime.alpha)
    implementation(libs.androidx.navigation3.ui.alpha)

    // Kotlinx Serialization (JSON)
    implementation(libs.kotlinx.serialization.json)

    // Compose (BOM + modules)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // Lifecycle Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Tooling (preview/debug)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Icons
    implementation(libs.androidx.compose.material.icons.extended)
}