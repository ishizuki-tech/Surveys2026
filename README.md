# Surveys2026

Surveys2026 is an Android survey runner built with Jetpack Compose that combines a YAML-driven survey definition with an on-device Small Language Model (SLM) workflow based on Google AI Edge LiteRT-LM.

The `main` branch currently focuses on four things:

* process-scoped configuration installation
* local model resolution, download, and warmup orchestration
* structured two-step answer validation and follow-up generation
* chat-style UI, review, and export flows

This README is written for the **current `main` branch**, not for the older `LiteRT` branch notes.

---

## What the app does

At a high level, the app:

1. installs a validated survey configuration at process startup
2. resolves a local model file from the installed config
3. warms the model so first-use latency is lower
4. runs a chat-style question flow
5. validates answers with a structured two-step pipeline
6. asks follow-up questions when the answer is not yet sufficient
7. preserves transcript state and supports review/export flows

The project is designed around a **single source of truth** for survey configuration and a **process-scoped** runtime setup.

---

## Main branch architecture

### 1. Application bootstrap

`SurveyApplication.kt` is the process entry point.

Its responsibilities include:

* initializing bootstrap helpers
* installing `SurveyConfig` once per process
* skipping redundant work in non-main processes
* attempting best-effort early warmup input injection

The important design rule is simple:

> the app should install configuration once at process level, and the rest of the app should read that installed config instead of reparsing YAML in random places.

### 2. UI orchestration

The app uses a Compose-based UI root that coordinates:

* configuration readiness
* model availability
* warmup state
* chat / survey flow
* review / export flow

### 3. Model runtime

The project uses a repository-centered model runtime.

`SlmRepository.kt` is the core orchestration layer for:

* prompt building
* streaming generation
* structured two-step validation
* phase-separated conversation resets
* model gating so sync and streaming requests do not trample each other

### 4. Validation pipeline

The validation flow is intentionally structured:

* **Step 1**: score and classify the answer
* **Step 2**: generate the next follow-up question only when needed

The final accept / follow-up decision is owned by **Step 1**, not by free-form model text.

### 5. Streaming bridge and ViewModel

The chat pipeline is split cleanly:

* `ChatStreamBridge.kt` forwards low-level phase-tagged stream events
* `AnswerValidator.kt` maps repository results into UI-facing outcomes
* `ChatQuestionViewModel.kt` manages transcript state, rollback, follow-up turns, draft persistence, and ephemeral vs stable model bubbles

---

## Core runtime flow

```text
SurveyApplication
  -> install process config
  -> bootstrap app services
  -> best-effort warmup input injection

UI root
  -> wait for installed config
  -> resolve model spec / local model file
  -> ensure model availability
  -> trigger warmup
  -> enter chat / survey flow

ChatQuestionViewModel
  -> submit answer
  -> open stream window
  -> call AnswerValidator
  -> repository runs two-step assessment
  -> Step 1 JSON result
  -> optional Step 2 follow-up JSON
  -> UI stores stable model messages + assistant outcome
```

---

## Repository layout

This is a single-module Android project centered on `:app`.

```text
Surveys2026/
  .github/
    workflows/
      AndroidBuild.yml
  app/
    src/main/
      AndroidManifest.xml
      assets/
        survey.yaml
      kotlin/com/negi/surveys/
        SurveyApplication.kt
        MainActivity.kt
        AppProcessServices.kt
        SurveyAppRoot.kt
        chat/
          AnswerValidator.kt
          ChatQuestionViewModel.kt
          ChatStreamBridge.kt
          ChatModels.kt
          ChatDrafts.kt
        config/
          ...
        slm/
          SLM.kt
          SlmRepository.kt
          SlmWarmup.kt
          SlmWarmupController.kt
        ui/
          ...
        utils/
          ModelDownloadController.kt
          WarmupController.kt
  README.md
  build.gradle.kts
  settings.gradle.kts
```

---

## Main branch highlights

### YAML-driven survey definition

The survey graph lives in:

* `app/src/main/assets/survey.yaml`

This configuration is expected to provide survey structure plus model defaults used by the runtime.

### Process-scoped config installation

The installed config is intended to be the app-wide source of truth during runtime.

### On-device LiteRT-LM integration

The app depends on `com.google.ai.edge.litertlm` and is built around an on-device model workflow.

### Warmup support

Warmup is split conceptually into phases such as:

* model availability
* prefetch / I/O warming
* compile / runtime initialization

### Structured follow-up logic

The follow-up system is not a single raw generation call. It uses a two-step path so the app can separate:

* evaluation logic
* assistant-facing follow-up wording

### Chat persistence

Drafts and chat transcript state are designed to survive navigation and restoration.

---

## Build targets and stack

### Android targets

From the current Gradle configuration:

* `compileSdk = 36`
* `minSdk = 26`
* `targetSdk = 36`
* application id: `com.negi.surveys`

### Main technologies

* Kotlin
* Android Gradle Plugin
* Jetpack Compose
* Material 3
* Navigation 3 alpha
* Kotlinx Serialization
* KAML
* Google AI Edge LiteRT-LM
* Android DataStore

---

## Configuration

### Survey configuration

Main survey configuration lives in:

* `app/src/main/assets/survey.yaml`

This is expected to define the survey graph and model defaults used by the app.

### Model resolution

The main branch resolves the runtime model from installed configuration and local storage through app services rather than hardcoding a fixed file path in the UI layer.

### Optional secrets / local settings

The Gradle configuration supports optional values such as:

* `HF_TOKEN`
* `GH_TOKEN`
* `GITHUB_TOKEN`
* GitHub owner / repo / branch related fields
* version name / version code overrides

These are intended for local development or CI use and should not be committed into the repository.

A practical setup is to keep local-only values in one of these places:

* `~/.gradle/gradle.properties`
* `local.properties`
* CI secrets / environment variables

---

## Build and run

### Prerequisites

* Android Studio with modern AGP support
* JDK 17
* Android SDK for API 36
* an Android 8.0+ device or emulator

### Open in Android Studio

```bash
git clone https://github.com/ishizuki-tech/Surveys2026.git
cd Surveys2026
```

Open the project and run the `app` configuration.

### Build from the command line

Debug build:

```bash
./gradlew :app:assembleDebug
```

Release artifacts:

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

### Version overrides

The build supports overriding app version values via Gradle properties, for example:

```bash
./gradlew :app:assembleRelease -Papp.versionName=v0.0.1-test -Papp.versionCode=123
```

---

## GitHub Actions

The repository includes a manual workflow:

* `.github/workflows/AndroidBuild.yml`

It is currently set up as an **Android CI & Release** workflow with `workflow_dispatch` inputs for things like:

* module
* release publishing toggle
* base version
* version name override
* version code override
* tag prefix / suffix format

In other words, the current workflow is oriented toward **manual build / release runs**, not just push-triggered CI.

---

## Important implementation notes

### Validation streaming is phase-based

The app distinguishes streaming phases instead of treating all model output as one undifferentiated blob.

Key phases include:

* `STEP1_EVAL`
* `STEP2_FOLLOW_UP`

### Stable vs ephemeral model messages

The UI keeps a distinction between:

* transient streaming model bubbles
* persisted final model messages

That split is important for both user experience and draft restoration.

### Step 1 owns the decision

The repository and validator are designed so that acceptance or follow-up status is determined from structured Step 1 evaluation, not from whatever Step 2 happens to say.

### Main branch is still an actively evolving prototype

This branch is clearly being used as an active development branch, not as a frozen product release. Expect implementation details, build workflow details, and runtime boundaries to continue evolving.

---

## Suggested reading order

If you are trying to understand the app quickly, read files in roughly this order:

1. `app/src/main/kotlin/com/negi/surveys/SurveyApplication.kt`
2. `app/src/main/kotlin/com/negi/surveys/SurveyAppRoot.kt`
3. `app/src/main/kotlin/com/negi/surveys/AppProcessServices.kt`
4. `app/src/main/kotlin/com/negi/surveys/slm/SlmRepository.kt`
5. `app/src/main/kotlin/com/negi/surveys/chat/AnswerValidator.kt`
6. `app/src/main/kotlin/com/negi/surveys/chat/ChatStreamBridge.kt`
7. `app/src/main/kotlin/com/negi/surveys/chat/ChatQuestionViewModel.kt`
8. `app/src/main/kotlin/com/negi/surveys/utils/WarmupController.kt`
9. `app/src/main/kotlin/com/negi/surveys/slm/SlmWarmupController.kt`
10. `app/src/main/assets/survey.yaml`

---

## Troubleshooting

### The app cannot find a model

Check:

* installed configuration is present
* model resolution points to a usable local file
* warmup and runtime services were created successfully

### Validation is stuck or inconsistent

Check:

* Step 1 / Step 2 stream events
* repository reset boundaries
* model gate behavior
* whether the model is still warming up

### Config appears missing

Check:

* `SurveyApplication` is registered and running
* `survey.yaml` exists and is valid
* the main process completed config installation

---

## License

MIT License.
