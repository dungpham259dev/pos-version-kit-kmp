# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`pos-version-kit-kmp` is a **single-module Kotlin Multiplatform library** (`:version-kit-mp`) published to Maven Central as `io.github.dungpham259dev:version-kit-mp`. It gives VTcode POS apps (`ipos`, `kpos`) version check → download → install, plus a drop-in Compose Multiplatform UI. Targets: **androidTarget** and **jvm("desktop")**. There is no sample/demo app — the only consumers are the host apps.

## Commands

```bash
./gradlew build                       # compile both targets + run all tests
./gradlew :version-kit-mp:allTests    # all common tests on every target
./gradlew :version-kit-mp:desktopTest # commonTest on the JVM/desktop target (fastest feedback)
./gradlew :version-kit-mp:testDebugUnitTest   # commonTest on the Android target

# single test class / method (desktop target)
./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.domain.service.VersionServiceTest"
./gradlew :version-kit-mp:desktopTest --tests "*VersionServiceTest.zip_magic_file_is_complete"

./gradlew publishToMavenLocal         # consume locally from ipos/kpos (no GPG key needed)
./gradlew publishAllPublicationsToMavenCentralRepository   # real release; requires signing keys
```

There is no lint/ktlint/detekt setup. Tests use `kotlin.test` + `kotlinx-coroutines-test`, with `MockEngine` (Ktor) and `FakeFileSystem` (okio) as the doubles — no mocking framework.

### Releasing

Version and coordinates live in `gradle.properties` (`GROUP` / `POM_ARTIFACT_ID` / `VERSION_NAME`) — **not** in `build.gradle.kts`; the `mavenPublishing {}` block intentionally omits `coordinates()` because re-declaring them conflicts. Bump `VERSION_NAME` there. `signAllPublications()` is applied only when a signing key is present, so `publishToMavenLocal` works keyless.

## Architecture

Clean-architecture layering inside one module; dependencies point inward (`presentation → domain ← data`).

```
data/        api (Ktor), datasource, model (DTOs), repository   — all `internal`
domain/      entity, error, repository+service interfaces, usecase
presentation/ VersionChecker (facade), components/ (Compose UI), install/ (expect/actual)
di/          versionKitModule() + platform modules
```

### Public API surface (what hosts may touch)

- `versionKitModule(VersionKitConfig)` + `versionKitAndroidModule()` / `versionKitDesktopModule()`
- `VersionChecker` — the facade: `check()`, `downloadUpdate()`, `installUpdate()`, `requestInstallPermission()`
- `VersionListSection(...)` composable + `VersionListCallbacks`
- domain types: `UpdateInfo`, `VersionError`, `Result`, `DownloadProgress(Tracker)`

Everything in `data/` and the Android `ApkInstaller` / `InstallEventBus` is `internal`. In `build.gradle.kts`, dependencies that appear in public signatures use `api(...)` (coroutines, okio, koin-core, compose) and pure implementation details use `implementation(...)` (ktor, serialization). **Keep that split** when adding a dependency — mis-scoping it silently breaks the host's compile classpath.

### Koin qualifier isolation (important)

The kit's own `HttpClient` / `HttpClientEngine` are registered behind `internal val VersionKitQualifier = named("versionKit")`. This exists so a host app's *unnamed* `HttpClient` never collides with (or is overridden by) the kit's — embedding stays order-independent. When adding a definition that a host is likely to also define, register it under `VersionKitQualifier` and resolve it with `get(VersionKitQualifier)`.

The host must supply `androidContext()` (Android) for `VersionInstaller`.

### Error handling: custom `Result`, not `kotlin.Result`

`domain/error/VersionError.kt` defines a hand-rolled `sealed class Result<out T, out E>` with `Success`/`Error` plus `map`/`flatMap`/`onSuccess`/`onError`, and `typealias VersionResult<T> = Result<T, VersionError>`. Every fallible operation returns this — **no exceptions escape the data layer**. `VersionRemoteDataSource` is where `Throwable`s are converted (`HttpRequestTimeoutException` → `TimeoutError`, `ResponseException` → `ApiError`, else `NetworkError`). Note this `Result` shadows `kotlin.Result` inside the package, so imports matter.

### expect/actual pairs

Two, both under `presentation/install/`:

| expect (commonMain) | Android actual | Desktop actual |
|---|---|---|
| `class VersionInstaller` | `PackageInstaller` session, falls back to `ACTION_VIEW` + FileProvider | `ProcessBuilder` launches `.msi`/`.exe`, then `exitProcess(0)` |
| `val platformInstallOutcomes: Flow<InstallOutcome>` | bridges `InstallEventBus` (fed by the broadcast receiver) | empty flow (install is terminal — process exits) |

`-Xexpect-actual-classes` is enabled to allow `expect class VersionInstaller`.

### Android install flow (the subtle part)

`ApkInstaller` commits a `PackageInstaller` session with a `PendingIntent` to `PackageInstallerBroadcastReceiver`. The receiver is system-instantiated and has no reference to the UI, so it publishes terminal outcomes into the process-wide `InstallEventBus` (`replay = 0`, buffer 1 — a returning collector must *not* re-see a stale outcome). `platformInstallOutcomes` maps that to `InstallOutcome`, which `VersionListController` collects so a cancelled/failed system dialog clears the installing state instead of hanging forever. When touching install code, preserve that every `STATUS_FAILURE_*` (including `STATUS_FAILURE_ABORTED` = user cancelled) emits an event.

The library's own `AndroidManifest.xml` declares `REQUEST_INSTALL_PACKAGES`, the receiver, and a `VersionKitFileProvider` under authority `${applicationId}.versionkit.fileprovider` — hosts get these by manifest merge and must not re-declare them.

### Download integrity

Two independent guards, both deliberate — don't remove them:
1. `VersionRemoteDataSource` lifts the shared 60s `requestTimeoutMillis` to `INFINITE_TIMEOUT_MS` per-download (a large APK would otherwise abort) and relies on `socketTimeoutMillis` for stalls; it then fails if `downloaded != contentLength` (silent truncation).
2. `VersionService.isDownloadComplete()` validates by **content, not server-reported size** — an APK/MSI must start with the ZIP magic `PK\x03\x04`. This catches HTTP-200 error bodies saved as `.apk`. `DownloadUpdateUseCase` deletes the file and fails if this check doesn't pass.

Downloads write to `destinationDir / "update_<sanitized fileId>"` and the host passes the cache dir in.

### UI layer

`VersionListSection` is a plain `@Composable` — no ViewModel/lifecycle dependency. It `remember`s an internal `VersionListController` that owns a `CoroutineScope(SupervisorJob() + Dispatchers.Main)`, cancelled from `DisposableEffect { onDispose { ... } }`. State is a `VersionListUiState` sealed class combined into one `StateFlow`. Hosts interact only through `VersionListCallbacks` (`onInstallRequested` returns `true` if the host handled the install itself, `false` to delegate to the kit).

**User-facing strings in the UI and in some error paths are Vietnamese and hardcoded** (e.g. `"Tải xuống thất bại"`, `"Thử lại"`). Match that when adding UI text.

### Server contract

Backend is the Rasa version-management server. Two endpoints, both under the configured `baseUrl`:
- `GET /api/versions/get-stable/{softwareCode}/{currentVersion}` → `GetStableVersionResponse`
- `GET /api/upload/download/{fileId}` → binary stream

Server field names are snake_case and **every field is nullable** — `VersionResponseItem` maps them via `@SerialName`, and `VersionData.fromResponseItem()` drops items missing `id`/`version` and forces `isDownloadable = false` when `fileId` is blank. `Json { ignoreUnknownKeys = true; isLenient = true }`. Server naming mismatches are already normalized there (`software` → `softwareName`, `size` → `fileSize`); keep new mapping in that one function rather than leaking nullables into the domain.

`fileSize` arrives as either raw bytes or a formatted string; `parseFileSize` in `UpdateInfo.kt` handles both. Version comparison is semver via `compareVersions` with a digit-stripping fallback for non-semver strings.
