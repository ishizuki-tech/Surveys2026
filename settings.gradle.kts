/*
 * =====================================================================
 *  IshizukiTech LLC — Gradle Settings
 *  ---------------------------------------------------------------------
 *  File: settings.gradle.kts
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

pluginManagement {
    // Plugin repositories must be declared first in settings.gradle.kts.
    repositories {
        google {
            // Restrict plugin resolution to known groups (faster + less surprising).
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provides JVM toolchain download repository configuration via Foojay.
    // IMPORTANT:
    // - Do NOT also declare toolchainManagement { ... repository("foojay") ... } in this file.
    // - Otherwise you'll hit: "Duplicate configuration for repository 'foojay'."
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Enforce centralized repositories for reproducible builds.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Surveys"

include(":app")
// include(":nativelib")
