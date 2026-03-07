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
    // - Do NOT apply org.jetbrains.kotlin.android (kotlin-android) here.

    // Compose compiler Gradle plugin.
    alias(libs.plugins.kotlin.compose)

    // Kotlinx Serialization compiler plugin.
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
 * It is used for safe overrides such as appId, local tokens, and version overrides.
 */
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Resolve a property from the following priority order:
 * 1) Gradle project property (-Pname=value or ~/.gradle/gradle.properties)
 * 2) local.properties
 * 3) default
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
 * buildConfigField expects a raw Java literal string, not a Kotlin string.
 */
fun quote(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Sanitize versionName for Android and Git tags.
 *
 * Rules:
 * - Remove spaces
 * - Keep it short and predictable for log aggregation
 */
fun sanitizeVersionName(raw: String): String =
    raw.trim()
        .replace("\\s+".toRegex(), "")
        .take(64)

/**
 * Resolve versionName with explicit precedence:
 * 1) -Papp.versionName=...
 * 2) env CI_APP_VERSION_NAME
 * 3) local.properties app.versionName=...
 * 4) fallback
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
 * 1) -Papp.versionCode=...
 * 2) env CI_VERSION_CODE
 * 3) env GITHUB_RUN_NUMBER
 * 4) fallback
 */
fun resolveVersionCode(): Int {
    val fromGradle = (project.findProperty("app.versionCode") as String?)?.toIntOrNull()
    val fromEnv = System.getenv("CI_VERSION_CODE")?.toIntOrNull()
    val fromRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    return fromGradle ?: fromEnv ?: fromRunNumber ?: 1
}

/**
 * Read a Gradle property as a non-blank string, or null.
 *
 * Resolution order:
 * 1) Standard Gradle properties
 * 2) <repoRoot>/gradle.properties.local
 * 3) <repoRoot>/local.properties
 *
 * Notes:
 * - Keep this helper tiny and dependency-free.
 * - Never log returned values because they may contain secrets.
 */
fun gradleProp(name: String): String? {
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

    readFromFile("gradle.properties.local")?.let { return it }
    readFromFile("local.properties")?.let { return it }

    return null
}

/**
 * Read the first non-blank property value from multiple keys.
 *
 * Useful during naming migration such as github.* -> gh.*.
 */
fun gradlePropAny(vararg names: String): String? =
    names.asSequence().mapNotNull { gradleProp(it) }.firstOrNull()

/**
 * Returns true if this project looks like a Git checkout.
 *
 * `.git` can be a directory or a file, so exists() is sufficient.
 */
fun Project.hasGitRepo(): Boolean = rootProject.file(".git").exists()

/**
 * Execute a command and return stdout as a trimmed string.
 *
 * Notes:
 * - Best-effort only. Failures return an empty string.
 * - Do NOT use this for sensitive or user-provided content.
 * - Working directory is forced to the repo root for stability.
 * - ProcessBuilder is used to avoid Gradle exec overload/deprecation issues.
 */
fun Project.execAndGetStdout(vararg args: String): String {
    return runCatching {
        if (args.isEmpty()) return@runCatching ""

        val pb = ProcessBuilder(args.toList())
            .directory(rootProject.rootDir)
            .redirectErrorStream(true)

        val p = pb.start()
        val text = p.inputStream.bufferedReader().use { it.readText() }

        val finished = p.waitFor(8, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            return@runCatching ""
        }

        text.trim()
    }.getOrElse { t ->
        logger.info("execAndGetStdout failed: cmd=${args.firstOrNull() ?: "?"} err=${t.javaClass.simpleName}")
        ""
    }
}

/** Returns short Git SHA (8 chars) or "nogit" if unavailable. */
fun Project.gitShaShort(): String {
    if (!hasGitRepo()) return "nogit"
    val s = execAndGetStdout("git", "rev-parse", "--short=8", "HEAD")
    return if (s.isNotBlank()) s else "nogit"
}

/** Returns true if the Git working tree has uncommitted changes. */
fun Project.gitDirty(): Boolean {
    if (!hasGitRepo()) return false
    val s = execAndGetStdout("git", "status", "--porcelain")
    return s.isNotBlank()
}

/**
 * Resolve a deterministic-ish build time string.
 *
 * Priority:
 * 1) Gradle property: build.timeUtc
 * 2) Environment variable: BUILD_TIME_UTC
 * 3) Current time
 */
fun buildTimeUtc(): String {
    val p = gradleProp("build.timeUtc")
    if (!p.isNullOrBlank()) return p

    val env = System.getenv("BUILD_TIME_UTC")?.trim()
    if (!env.isNullOrBlank()) return env

    return Instant.now().toString()
}

android {
    namespace = "com.negi.surveys"
    compileSdk = 36

    // Config resolution supports both "github.*" and legacy "gh.*" keys.
    val ghOwner = gradlePropAny("github.owner", "gh.owner") ?: ""
    val ghRepo = gradlePropAny("github.repo", "gh.repo") ?: "SurveyExports"
    val ghBranch = gradlePropAny("github.branch", "gh.branch") ?: "main"
    val ghPathPrefix = gradlePropAny("github.pathPrefix", "gh.pathPrefix") ?: ""
    val ghLogPrefix = gradlePropAny("github.logPrefix", "gh.logPrefix") ?: "surveyapp"
    val ghToken = gradlePropAny("github.token", "gh.token") ?: ""
    val hfToken = gradlePropAny("hf.token", "HF_TOKEN") ?: ""

    /*
     * Runtime token policy for this project:
     * - Tokens are exposed through BuildConfig for both debug and release variants.
     * - Missing values resolve to empty strings so clean checkouts still compile.
     * - The current release build is treated as an internal distribution build.
     * - Public/app-store distribution should use a separate build type and signing policy.
     */

    defaultConfig {
        applicationId = "com.negi.surveys"

        minSdk = 26
        targetSdk = 36
        versionCode = resolveVersionCode()
        versionName = resolveVersionName()

        val sha = rootProject.gitShaShort()
        val dirty = rootProject.gitDirty()
        val timeUtc = buildTimeUtc()

        buildConfigField("String", "GIT_SHA", quote(sha))
        buildConfigField("boolean", "GIT_DIRTY", dirty.toString())
        buildConfigField("String", "BUILD_TIME_UTC", quote(timeUtc))

        // GitHub upload config.
        // Keep secret values out of Git and inject them from local/CI properties.
        buildConfigField("String", "GH_OWNER", quote(ghOwner))
        buildConfigField("String", "GH_REPO", quote(ghRepo))
        buildConfigField("String", "GH_BRANCH", quote(ghBranch))
        buildConfigField("String", "GH_PATH_PREFIX", quote(ghPathPrefix))
        buildConfigField("String", "GH_LOG_PREFIX", quote(ghLogPrefix))

        // Backward-compatible aliases.
        buildConfigField("String", "GITHUB_OWNER", quote(ghOwner))
        buildConfigField("String", "GITHUB_REPO", quote(ghRepo))
        buildConfigField("String", "GITHUB_BRANCH", quote(ghBranch))
        buildConfigField("String", "GITHUB_LOG_PREFIX", quote(ghLogPrefix))

        // Token fields must exist for all variants at compile time.
        // Variant-specific build types replace these empty defaults.
        buildConfigField("String", "GH_TOKEN", quote(""))
        buildConfigField("String", "GITHUB_TOKEN", quote(""))
        buildConfigField("String", "HF_TOKEN", quote(""))
    }

    buildTypes {
        debug {
            // Debug embeds runtime tokens when configured.
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

            /*
             * This release build is for internal distribution and intentionally
             * embeds the same runtime tokens as debug when they are provided.
             *
             * For public distribution, create a separate release policy with:
             * - proper release signing
             * - no embedded secrets
             * - distribution-specific hardening
             */
            signingConfig = signingConfigs.getByName("debug")

            buildConfigField("String", "GH_TOKEN", quote(ghToken))
            buildConfigField("String", "GITHUB_TOKEN", quote(ghToken))
            buildConfigField("String", "HF_TOKEN", quote(hfToken))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    /**
     * Optional version pins for local machines and CI.
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
    // Navigation3 (alpha).
    implementation(libs.androidx.navigation3.runtime.alpha)
    implementation(libs.androidx.navigation3.ui.alpha)

    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)

    // LiteRT-LM.
    implementation(libs.litertlm)

    // Compose.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // Lifecycle Compose.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Tooling.
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.datastore.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Icons.
    implementation(libs.androidx.compose.material.icons.extended)
}