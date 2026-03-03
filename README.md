# Surveys2026 (LiteRT branch)

An Android (Jetpack Compose) **survey runner** prototype that embeds an on-device Small Language Model (SLM) via
**Google AI Edge LiteRT-LM**, with a **warmup pipeline** (prefetch + compile) and a **YAML-driven survey graph**.

> Branch: `LiteRT` (this README is written to match the implementation on that branch).

---

## Review notes (what was fixed in this pass)

* Removed duplicate intro paragraphs.
* Standardized names to match the branch:

    * `SurveyConfig.resolveModelDownloadSpec()`
    * `ModelDownloadController.ensureModelOnce(...)`
    * `ModelDownloadController.ModelState.*`
    * `PrefetchState` / `CompileState` (from `utils/WarmupController.kt`)
    * `GatePolicy.MODEL_ONLY` / `GatePolicy.MODEL_PREFETCH_COMPILE`
* Replaced conceptual “ensureDownloaded” language with the actual controller API (`ensureModelOnce`).
* Kept “conceptual” labels only where the definitive type lives elsewhere.

---

## Reading Index (start here)

If you want to understand the whole app quickly, read in this order:

1. **Process bootstrap & config single-source rule**

    * `app/src/main/kotlin/com/negi/surveys/SurveyApplication.kt`
    * What to look for:

        * `SurveyConfigLoader.fromAssetsValidated(...)`
        * `SurveyConfigLoader.installProcessConfig(cfg)`
        * Main-process-only install (secondary processes must not install)

2. **UI root + orchestration hub (model → warmup → UI wiring)**

    * `app/src/main/kotlin/com/negi/surveys/SurveyAppRoot.kt`
    * What to look for:

        * config gate: `SurveyConfigLoader.getInstalledConfigOrNull()`
        * model spec: `SurveyConfig.resolveModelDownloadSpec()`
        * model downloader recreation key: `ModelSpecKey`
        * warmup wiring: `WarmupController` / `SlmWarmupController`
        * gate policy: `GatePolicy.MODEL_ONLY` vs `GatePolicy.MODEL_PREFETCH_COMPILE`
        * `ensureModelOnce(...)` and `requestCompileAfterPrefetch(...)`
        * Navigation3 setup + screens

3. **Warmup implementation (prefetch/compile split + waiting policy)**

    * `app/src/main/kotlin/com/negi/surveys/slm/SlmWarmupController.kt`
    * `app/src/main/kotlin/com/negi/surveys/slm/SlmWarmup.kt`
    * `app/src/main/kotlin/com/negi/surveys/utils/WarmupController.kt`
    * What to look for:

        * `prefetchOnce()` / `compileOnce()` / `warmupOnce()`
        * `ensureCompiled(timeoutMs, reason)`
        * `requestCompileAfterPrefetch(reason)`
        * terminal rules: `PrefetchState.isTerminal()` / `CompileState.isTerminal()`

4. **SLM repository (2-step Eval → FollowUp + streaming boundary)**

    * `app/src/main/kotlin/com/negi/surveys/slm/SlmRepository.kt`
    * What to look for:

        * `buildPrompt(userPrompt)` must be I/O-free (non-suspend)
        * `request(prompt): Flow<String>` (streaming)
        * `enableTwoStepEval` + JSON contract + `acceptScoreThreshold`

5. **SLM facade + LiteRT runtime wrappers**

    * `app/src/main/kotlin/com/negi/surveys/slm/SLM.kt`
    * `app/src/main/kotlin/com/negi/surveys/slm/liteRT/` (session/run/delta)
    * What to look for:

        * initialization boundary
        * streaming termination / watchdog behavior (SDK drift tolerance)

6. **Export pipeline (binder-safe sharing)**

    * `app/src/main/kotlin/com/negi/surveys/ui/ExportScreen.kt`
    * What to look for:

        * SHA-256 + byte count calculation (off main thread)
        * preview vs full copy/share behavior
        * FileProvider share path + large payload handling

---

## What this project is

* **Survey runtime** driven by `app/src/main/assets/survey.yaml`.
* **On-device SLM** using LiteRT-LM (`com.google.ai.edge.litertlm`).
* **Warmup orchestration** to reduce first-token latency:

    * Prefetch = OS page-cache warming / I/O warming.
    * Compile  = heavier runtime initialization / delegate compilation (optionally OpenCL).
* **Developer-friendly config** with optional tokens (e.g., Hugging Face download, GitHub upload).

---

## Tech stack

* Kotlin / Android
* Jetpack Compose (Material 3)
* Navigation3 (alpha)
* Kotlinx Serialization + KAML
* LiteRT-LM (`com.google.ai.edge.litertlm`)

Build targets (from Gradle):

* `compileSdk = 36`
* `minSdk = 26`
* `targetSdk = 36`

---

## Repository layout (LiteRT branch)

```
Surveys2026/
  app/
    src/main/
      AndroidManifest.xml
      assets/
        survey.yaml
      kotlin/com/negi/surveys/
        SurveyApplication.kt
        MainActivity.kt
        SurveyAppRoot.kt
        config/
          SurveyConfigModelDownloadSpec.kt
          ...
        slm/
          SLM.kt
          SlmRepository.kt
          SlmWarmup.kt
          SlmWarmupController.kt
          liteRT/
            ...
        ui/
          ...
        utils/
          ModelDownloadController.kt
          WarmupController.kt
          HeavyInitializer.kt
```

Single-module build on this branch:

* `include(":app")` (see `settings.gradle.kts`).

---

## Configuration

### 1) `survey.yaml` (survey graph + model defaults)

File: `app/src/main/assets/survey.yaml`

Contains (conceptually):

* `graph`: a node graph (currently minimal START → DONE)
* `model_defaults`: default LiteRT-LM model source + UI/timeout parameters

The model download parameters are resolved via:

* `SurveyConfig.resolveModelDownloadSpec()` in
  `app/src/main/kotlin/com/negi/surveys/config/SurveyConfigModelDownloadSpec.kt`

That resolver:

* accepts only http(s) URLs (rejects whitespace / non-http(s))
* sanitizes the file name to avoid traversal / separators
* loads Hugging Face token from `BuildConfig` via reflection (best-effort)
* applies safe minimums:

    * `timeoutMs >= 1`
    * `uiThrottleMs >= 0`
    * `uiMinDeltaBytes >= 0`

### 2) Tokens / secrets (Hugging Face)

Hugging Face token is resolved from `BuildConfig` via reflection.
Supported field names (first match wins):

* `HF_TOKEN`
* `HUGGINGFACE_TOKEN`
* `HUGGING_FACE_TOKEN`
* `HUGGING_FACE_API_TOKEN`

Recommendations:

* Keep secrets out of git.
* Prefer local-only injection and/or runtime secrets.

---

## OpenCL / GPU (AndroidManifest)

The app declares optional native libraries to enable GPU (OpenCL) access on `targetSdk 31+`.
These are marked `android:required="false"`, so the app should still run on devices without them.

Practical guidance:

* Treat OpenCL as best-effort.
* Validate stability per device family.
* Keep a CPU fallback path.

---

## Warmup pipeline (why it exists)

Cold-starting an on-device model often has two distinct kinds of “first time pain”:

1. **I/O pain**: reading a large model file from storage.
2. **Compile/delegate pain**: initializing runtime, compiling kernels, loading delegates.

This project separates them:

* **Prefetch warmup** warms the OS page cache (I/O warming).
* **Compile warmup** performs heavyweight runtime initialization.

The public API and state model are intentionally SDK-agnostic and live in:

* `app/src/main/kotlin/com/negi/surveys/utils/WarmupController.kt`

---

## Warmup states (exact names)

Defined in `utils/WarmupController.kt`.

### `PrefetchState`

* `PrefetchState.Idle`
* `PrefetchState.Running(file, startedAtMs, downloaded, total, elapsedMs)`
* `PrefetchState.Prefetched(file, sizeBytes, startedAtMs, elapsedMs)`
* `PrefetchState.Failed(message, startedAtMs, elapsedMs)`
* `PrefetchState.Cancelled(startedAtMs, elapsedMs)`
* `PrefetchState.SkippedNotConfigured(reason, elapsedMs)`

### `CompileState`

* `CompileState.Idle`
* `CompileState.WaitingForPrefetch(file, requestedAtMs, elapsedMs)`
* `CompileState.Compiling(file, startedAtMs, elapsedMs)`
* `CompileState.Compiled(file, startedAtMs, elapsedMs)`
* `CompileState.Failed(message, startedAtMs, elapsedMs)`
* `CompileState.Cancelled(startedAtMs, elapsedMs)`
* `CompileState.SkippedNotConfigured(reason, elapsedMs)`

Terminal states:

* Prefetch terminal: `Prefetched`, `Failed`, `Cancelled`, `SkippedNotConfigured`
* Compile terminal: `Compiled`, `Failed`, `Cancelled`, `SkippedNotConfigured`

---

## App lifecycle & single-source config (important)

### Process bootstrap (`SurveyApplication`)

File: `app/src/main/kotlin/com/negi/surveys/SurveyApplication.kt`

On process start (conceptually):

1. Logs early breadcrumbs (pid/process/build).
2. Initializes bootstrap helpers best-effort.
3. Installs **SurveyConfig exactly once per process** (main process only):

    * Loads config from assets via `SurveyConfigLoader.fromAssetsValidated(...)`.
    * Stores it via `SurveyConfigLoader.installProcessConfig(cfg)`.
    * Secondary processes must skip install to avoid redundant I/O.

Single source of truth rule:

* Everything else must read config via `SurveyConfigLoader.getInstalledConfigOrNull()`.
* UI must not re-parse YAML directly.

### UI root waits for installed config (`SurveyAppRoot`)

File: `app/src/main/kotlin/com/negi/surveys/SurveyAppRoot.kt`

* `SurveyAppRoot()` does not load YAML directly.
* It waits briefly for `SurveyApplication` to install config:

    * `INSTALLED_CONFIG_WAIT_MS = 1500`
    * `INSTALLED_CONFIG_POLL_MS = 25`

If config is still missing after the deadline:

* `ConfigState.Failed("InstalledConfigMissing")`

---

## Model download (exact controller + state names)

File: `app/src/main/kotlin/com/negi/surveys/utils/ModelDownloadController.kt`

The root uses `ModelDownloadController` to ensure a local model file exists.

Start/ensure call:

* `ensureModelOnce(timeoutMs, forceFresh, reason)`

State machine (exact names):

* `ModelState.Idle(elapsedMs)`
* `ModelState.NotConfigured(reason, elapsedMs)`
* `ModelState.Checking(file, startedAtMs, elapsedMs)`
* `ModelState.Downloading(file, startedAtMs, downloaded, total, elapsedMs)`
* `ModelState.Ready(file, sizeBytes, startedAtMs, elapsedMs)`
* `ModelState.Failed(safeReason, startedAtMs, elapsedMs)`
* `ModelState.Cancelled(startedAtMs, elapsedMs)`

Safety note:

* Failure messages must be safe (avoid raw URLs, tokens, file paths, or exception dumps).

---

## How “model DL → warmup → repository → UI” is wired (implementation-aligned)

Primary orchestration lives in:

* `app/src/main/kotlin/com/negi/surveys/SurveyAppRoot.kt`

### 0) Config gate (installed-only)

```
SurveyAppRoot()
  └─ produceState: wait for installed config
     ├─ SurveyConfigLoader.getInstalledConfigOrNull()
     ├─ INSTALLED_CONFIG_WAIT_MS = 1500
     └─ INSTALLED_CONFIG_POLL_MS = 25

ConfigState:
  - Loading
  - Ready(cfg)
  - Failed("InstalledConfigMissing")
```

### 1) Resolve model spec from config

```
ConfigState.Ready(cfg)
  └─ cfg.resolveModelDownloadSpec()
      -> ModelDownloadSpec(modelUrl, fileName, timeoutMs, uiThrottleMs, uiMinDeltaBytes, hfToken)
```

### 2) Downloader lifecycle (recreate on effective spec changes)

`SurveyAppRoot` computes a stable `ModelSpecKey` and recreates the downloader when it changes.

Downloader entry:

* `modelDownloader.ensureModelOnce(timeoutMs, forceFresh, reason)`

### 3) Warmup orchestration

Warmup is surfaced via `WarmupController`:

* `prefetchState: StateFlow<PrefetchState>`
* `compileState: StateFlow<CompileState>`

Root policy used on this branch:

* download model once after first frame (startup)
* once model becomes ready, start warmup
* optionally block via `ensureCompiled()` when entering gated UI

Key calls:

* `warmup.requestCompileAfterPrefetch(reason = "startupAfterModelReady")`
* `warmup.ensureCompiled(timeoutMs, reason)`

### 4) Repository streaming boundary

SLM repository interface:

* `buildPrompt(userPrompt: String): String` (no I/O)
* `request(prompt: String): Flow<String>` (streaming)

In this branch the concrete repository is:

* `app/src/main/kotlin/com/negi/surveys/slm/SlmRepository.kt`

It implements a **2-step flow** by default:

1. Eval (internal, strict JSON score)
2. Follow-up question generation (strict JSON)

### 5) UI gating

`SurveyAppRoot` gates screen entry via `GatePolicy`:

* `GatePolicy.MODEL_ONLY`
* `GatePolicy.MODEL_PREFETCH_COMPILE`

Screens (conceptually):

* Setup / Download status (ModelState)
* Warmup status (PrefetchState / CompileState)
* Chat / Survey runner (collects Flow deltas)
* Review / Export

### End-to-end ASCII flow

```
[SurveyApplication]
  └─ installProcessConfig(from assets/survey.yaml)

[MainActivity]
  └─ setContent { SurveyAppRoot() }

[SurveyAppRoot]
  ├─ wait installed cfg (polling, bounded)
  ├─ cfg.resolveModelDownloadSpec()
  ├─ modelDownloader.ensureModelOnce(...)
  ├─ warmup.requestCompileAfterPrefetch(...)
  ├─ (optional) warmup.ensureCompiled(...)
  ├─ repo.request(prompt) : Flow<String>
  └─ UI collects Flow → renders deltas → Export
```

---

## SlmWarmupController (public operations)

File: `app/src/main/kotlin/com/negi/surveys/slm/SlmWarmupController.kt`

Operations:

* `prefetchOnce()`
* `compileOnce()`
* `warmupOnce()` (prefetch then compile-after-prefetch)
* `requestCompileAfterPrefetch(reason)`
* `ensureCompiled(timeoutMs, reason)`
* `cancelAll(reason)`
* `resetForRetry(reason)`

SDK boundary rule:

* Public API uses `SystemPrompt` (SDK-agnostic).
* Internally it attempts to build a LiteRT `Message` best-effort (tolerates SDK drift).

---

## SlmRepository (2-step Eval → FollowUp)

File: `app/src/main/kotlin/com/negi/surveys/slm/SlmRepository.kt`

Key behavior:

* `buildPrompt()` is non-suspend and must not do I/O.
* `request()` is suspend and may load config best-effort depending on `allowAssetConfigFallback`
  (but must never install process config; `SurveyApplication` is the owner).

2-step mode:

* controlled by `enableTwoStepEval` (default `true`)
* acceptance threshold: `acceptScoreThreshold` (default `70`)

---

## Export & sharing (binder-safe)

Why:

* Huge intent extras can crash (`TransactionTooLarge`).
* Clipboard may fail or behave inconsistently for very large payloads.

Implementation:

* `app/src/main/kotlin/com/negi/surveys/ui/ExportScreen.kt`

Behavior:

* SHA-256 + UTF-8 byte count computed off main thread
* preview vs full copy/share decisions
* large payload share uses a file + `FileProvider` best-effort

---

## Build & Run

### Prerequisites

* Android Studio (AGP 9+ compatible)
* JDK 17
* Android SDK (compileSdk 36)
* Device/emulator: Android 8.0+ (`minSdk 26`)

### Quick start

```bash
git clone https://github.com/ishizuki-tech/Surveys2026.git
cd Surveys2026
git checkout LiteRT
```

Open in Android Studio and run the `app` configuration.

---

## Troubleshooting

### UI shows `InstalledConfigMissing`

* Ensure `SurveyApplication` is registered in `AndroidManifest.xml`.
* Confirm `assets/survey.yaml` exists and is valid.
* Watch logcat for success breadcrumbs from `SurveyApplication`.

### Model download never reaches `Ready`

* Inspect `ModelState` transitions (Idle → Checking → Downloading → Ready/Failed).
* Verify `model_defaults.default_model_url` is http(s) and contains no whitespace.
* Ensure file name is valid (sanitization may change it).

### Warmup never reaches terminal

* Treat warmup as best-effort.
* Use bounded waits: `ensureCompiled(timeoutMs, reason)`.
* Timeout returns the current state snapshot (it does not throw).

### Export share fails

* Some share targets reject large payloads.
* Prefer file-based sharing (ExportScreen should auto-switch when large).
* Confirm FileProvider authority matches `${applicationId}.fileprovider`.

### OpenCL crashes on certain devices

* Treat GPU as best-effort.
* Validate per device family; disable GPU path if unstable.
* Keep CPU fallback.

---

## License

MIT License (see repository license and file headers).
