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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.surveyapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.surveyapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        // Keep Java bytecode target consistent with Kotlin.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // NOTE:
    // - kotlinOptions { jvmTarget = "..." } is deprecated/error in Kotlin 2.3+
    // - Use the compilerOptions DSL via KotlinCompile tasks (see below).

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    ndkVersion = "29.0.14206865"
    buildToolsVersion = "36.1.0"
}

/**
 * Kotlin toolchain: ensures Gradle uses a consistent JDK for Kotlin compilation.
 */
kotlin {
    jvmToolchain(17)
}

/**
 * Kotlin compiler options: JVM target must match android.compileOptions.
 */
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Navigation3: choose ONE set (stable OR alpha). Do not mix.
    // Stable:
    // implementation(libs.androidx.navigation3.runtime)
    // implementation(libs.androidx.navigation3.ui)

    // Alpha (newest APIs):
    implementation(libs.androidx.navigation3.runtime.alpha)
    implementation(libs.androidx.navigation3.ui.alpha)

    // Kotlinx Serialization (JSON)
    implementation(libs.kotlinx.serialization.json)

    // Compose (BOM + modules)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // Needed for androidx.lifecycle.viewmodel.compose.viewModel()
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Recommended: Compose + Lifecycle integration (collectAsStateWithLifecycle etc.)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Tooling (preview/debug)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Optional (commonly used)
    // implementation(libs.androidx.compose.ui.graphics)
    // debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.compose.material.icons.extended)
}
