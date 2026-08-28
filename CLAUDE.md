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
- `RequiredUpdateController` (headless) + `rememberRequiredUpdateController(...)`
- `RequiredUpdateGate(...)` + `RequiredUpdateScope`, `RequiredUpdateState`, `UpdateStage`
- domain types: `UpdateInfo` (incl. `UpdateInfo.isRequired`), `VersionError`, `Result`, `DownloadProgress(Tracker)`

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

### Forced update (`is_required`)

Server trả `is_required` trên từng version; `VersionData.fromResponseItem` map thành
`UpdateInfo.isRequired` (mặc định `false`). `List<UpdateInfo>.requiredUpdate()`
(`domain/entity/RequiredUpdate.kt`) chọn bản bắt buộc cao nhất **sau khi**
`shouldPresentUpdate` đã lọc — nên một version bật cờ mà thiếu file hoặc không mới hơn
sẽ không khoá được app. Đó là chủ ý, đừng "sửa".

`RequiredUpdateController` (`presentation/update/`) là state holder headless cho luồng
này: public API là `state: StateFlow<RequiredUpdateState>`, `check()`, `download()`,
`install()`, `requestInstallPermission()`, `retry()`, `close()`. Nó nhận `CoroutineScope`
từ ngoài (qua constructor public, không tham số `installOutcomes`) nên host dùng
ViewModel riêng vẫn được — nhưng controller **không** chạy trực tiếp trên scope đó: nó
tự tạo một `controllerScope` con (`SupervisorJob` gắn vào `Job` của scope cha) và mọi
coroutine nội bộ chạy trên scope con này. `close()` chỉ huỷ `controllerScope`, không đụng
tới scope host truyền vào — vì vậy host được phép truyền một scope dùng chung (ví dụ
scope của ViewModel sống lâu hơn) mà không sợ `close()` làm sập những coroutine khác trên
scope đó. Constructor chính (nhận thêm `installOutcomes: Flow<InstallOutcome>`) là
`internal`, tồn tại chỉ để test tiêm outcome giả; constructor public không có tham số đó
và mặc định dùng `platformInstallOutcomes`.

**`scope` bắt buộc phải bị giới hạn (confined) vào main dispatcher** — `viewModelScope` hay
`CoroutineScope(SupervisorJob() + Dispatchers.Main)` đều hợp lệ. Trạng thái nội bộ của
controller (`lastDownloadedFile`, `downloadTracker`, các `Job` theo dõi, và việc cập nhật
`state`) không dùng lock/atomic gì cả — an toàn đồng thời chỉ được đảm bảo bằng cách buộc mọi
thay đổi chạy tuần tự trên một luồng. Truyền một scope chạy trên `Dispatchers.IO`/`Default` có
thể khiến hai lệnh gọi (ví dụ `download()` hai lần từ hai luồng khác nhau) cùng vượt qua điều
kiện bảo vệ cùng lúc.

`check()` không chỉ chạy một lần lúc khởi tạo. Với gate đặt ở gốc app, controller sống
suốt phiên chạy, nên chỉ hỏi server một lần là "fail-open" (mặc định khi `CheckFailed`)
biến thành "fail-permanently-open": máy POS khởi động lúc mất mạng sẽ không bao giờ thấy
bản bắt buộc cho tới khi app khởi động lại, và một bản bị đánh dấu bắt buộc *sau khi* app
đã chạy sẽ không bao giờ chặn được máy đó. Vì vậy controller có tham số
`recheckIntervalMs: Long? = 15 * 60 * 1000L` (ở cả hai constructor, cùng một vị trí, cùng
giá trị mặc định): mỗi khi trạng thái ổn định ở `NotRequired` hoặc `CheckFailed`, nó tự đặt
lịch gọi lại `check()` sau khoảng thời gian này trên `controllerScope` (nên `close()` dừng
được vòng lặp), và không đặt lịch chồng lên nhau — gọi `check()`/`retry()` thủ công sẽ huỷ
timer đang chờ trước khi bắt đầu chu kỳ mới. **Không** hỏi lại khi đang `Required` — gate đã
chặn UI rồi, hỏi lại lúc này chỉ tranh chấp với vòng tải/cài đang diễn ra (và `check()` chủ
động huỷ mọi lượt tải dở dang, xem đoạn dưới). Truyền `null` để tắt hẳn việc hỏi lại.

`observeInstallOutcomes()` chỉ áp `InstallOutcome.Failure` khi state hiện tại là
`Required` **và** đang ở `UpdateStage.Installing` — một outcome trễ/trùng từ lượt cài
trước (ví dụ sau khi host đã gọi `retry()` và bắt đầu chu kỳ mới) sẽ bị bỏ qua thay vì
ghi đè stage mới. Đây là do `InstallOutcome` đến từ `InstallEventBus` toàn tiến trình,
không mang token liên kết với một lần gọi `install()` cụ thể.

`RequiredUpdateGate(...)` nhận ba slot: `content` (UI bình thường của host), `requiredContent`
(bắt buộc, vẽ khi có bản bắt buộc — nhận `RequiredUpdateScope` để gọi
`download()`/`install()`/`retry()`/`requestInstallPermission()`), và
`checkFailedContent: (@Composable () -> Unit)? = null`. Không còn cờ `blockOnCheckFailure`
riêng: truyền `checkFailedContent` là chặn khi `CheckFailed`, để `null` (mặc định) là
fail-open — mất mạng thì cho vào app luôn, vì khoá máy POS ngoài tiệm là thảm hoạ vận hành.
Gộp làm một tham số vì trước đây `blockOnCheckFailure = true` mà quên truyền
`checkFailedContent` (nó có default rỗng `{}`) tạo ra một overlay trong suốt nuốt hết chạm,
back, focus và accessibility mà không vẽ gì — trên máy POS thứ đó không khác gì máy bị đơ,
không có lối thoát ngoài force-stop. Với slot nullable, "bật chặn" và "có UI để chặn" là
cùng một hành động nên bẫy đó không còn cách nào xảy ra. Nhánh `CheckFailed` **không bao giờ**
tự bịa ra một `UpdateInfo` để hiện `requiredContent` — không có dữ liệu thật thì không vẽ
overlay bắt buộc, cùng lắm chỉ chặn qua `checkFailedContent`.

Gate tự sở hữu một `Box` xếp lớp (stacking) duy nhất bao quanh `content` và overlay, nên
nó phủ đúng lớp bất kể host đặt `RequiredUpdateGate` ở đâu trong cây UI. Khi bị chặn,
overlay không chỉ nuốt pointer input mà còn: rút focus khỏi `content` và chặn nó nhận
focus lại (`focusProperties { canFocus = false }`), gỡ `content` khỏi cây accessibility
(`clearAndSetSemantics {}`), và chặn nút back hệ thống. Ba điều sau quan trọng không kém
việc chặn chạm — một máy quét mã vạch trên POS giả lập bàn phím cứng, nên nếu `content`
còn giữ focus thì máy quét vẫn "gõ" được dữ liệu vào UI bị khoá phía sau overlay.

Back hệ thống được chặn qua `internal expect/actual BlockSystemBack()`
(`presentation/update/BlockSystemBack.kt`) chứ không dùng `BackHandler` thẳng trong
commonMain — Compose Multiplatform 1.9.0 chưa có `BackHandler` cho commonMain. Actual
Android gọi `androidx.activity.compose.BackHandler(enabled = true) {}` (phụ thuộc
`activity-compose`, khai báo `implementation` — không `api` — trong `androidMain` của
`build.gradle.kts`, vì đây là chi tiết cài đặt, không lộ ra public API). Actual Desktop
là no-op vì desktop không có nút back hệ thống.

`check()` cũng nhặt lại file đã tải: nếu `cacheDir` còn một installer hợp lệ cho đúng bản bắt
buộc đó (`VersionService.existingDownload`, kiểm bằng ZIP magic như mọi chỗ khác), controller vào
thẳng `UpdateStage.Downloaded` thay vì `Ready`. Cần thiết vì trên Android việc cấp quyền "cài ứng
dụng không xác định" thường giết process — không có bước này thì user cấp quyền xong quay lại phải
tải lại toàn bộ APK. Nó **không** tự cài: bung dialog hệ thống ngay lúc app vừa khởi động là việc
user không hề yêu cầu. Đường dẫn file do `downloadTargetPath()` (`domain/entity/DownloadTarget.kt`)
quyết định — một định nghĩa duy nhất, dùng chung bởi cả nơi ghi (`VersionRemoteDataSource`) lẫn nơi
tìm lại, nên đừng tự nội suy tên file ở chỗ khác.

`RequiredUpdateController.onAppForegrounded()` **phải** được host gọi khi app quay lại foreground.
Cài thành công thì hệ thống giết process, nên còn sống mà vẫn ở `UpdateStage.Installing` nghĩa là
việc cài đã không xảy ra. Không phải máy nào cũng gửi broadcast kết thúc khi user đóng dialog cài
đặt hệ thống — thiếu hook này thì gate treo vĩnh viễn ở `Installing`, mà gate chặn cả nút back, tức
là máy POS đơ hẳn cho tới khi force-stop. Đây là lối thoát duy nhất không dựa vào broadcast, đừng gỡ.
Receiver cũng có nhánh `else` phát `Failure` cho mọi status lạ (ví dụ `STATUS_FAILURE_TIMEOUT` của
API 34+) vì cùng lý do.

`VersionChecker.canInstallPackages()` / `RequiredUpdateScope.canInstallPackages()` cho host hỏi
quyền cài đặt hiện có hay không, để ẩn nút "Cấp quyền" sau khi user đã cấp trong Settings. Query
lại mỗi lần gọi, nên host cần tự kích hoạt recomposition khi app resume.

### Server contract

Backend is the Rasa version-management server. Two endpoints, both under the configured `baseUrl`:
- `GET /api/versions/get-stable/{softwareCode}/{currentVersion}` → `GetStableVersionResponse`
- `GET /api/upload/download/{fileId}` → binary stream

Server field names are snake_case and **every field is nullable** — `VersionResponseItem` maps them via `@SerialName` (including `is_required` → `isRequired`, defaulting to `false` when null), and `VersionData.fromResponseItem()` drops items missing `id`/`version` and forces `isDownloadable = false` when `fileId` is blank. `Json { ignoreUnknownKeys = true; isLenient = true }`. Server naming mismatches are already normalized there (`software` → `softwareName`, `size` → `fileSize`); keep new mapping in that one function rather than leaking nullables into the domain.

`fileSize` arrives as either raw bytes or a formatted string; `parseFileSize` in `UpdateInfo.kt` handles both. Version comparison is semver via `compareVersions` with a digit-stripping fallback for non-semver strings.
