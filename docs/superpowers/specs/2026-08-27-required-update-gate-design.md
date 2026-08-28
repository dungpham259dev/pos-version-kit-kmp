# Required Update (bắt buộc cập nhật) với UI do host cung cấp

Ngày: 2026-08-27
Module: `:version-kit-mp`

## Vấn đề

Server đánh dấu một version là bắt buộc. Người dùng phải cập nhật xong mới dùng tiếp
được app. Host app (`ipos`, `kpos`) muốn tự vẽ toàn bộ UI chặn đó — kit không được
áp đặt giao diện.

Hiện trạng: kit không có khái niệm "bắt buộc" ở bất kỳ tầng nào, và toàn bộ logic
download → install nằm trong `VersionListController` (`internal`, 799 dòng, gắn chặt
với `VersionListSection`). Host muốn UI riêng thì phải chép lại logic đó.

## Phạm vi

Trong phạm vi:
1. Cờ `is_required` từ server xuyên qua data → domain.
2. Một state holder headless, public, cho luồng cập nhật bắt buộc.
3. Một composable "gate" chặn app, nhận UI của host qua slot.
4. Refactor tối thiểu để test được luồng install.

Ngoài phạm vi: đổi `VersionListSection` / `VersionListController`; UI mặc định cho gate;
chính sách bắt buộc phía client (`minSupportedVersion`) — nguồn sự thật là server.

## 1. Server → domain

`VersionResponseItem` thêm:

```kotlin
@SerialName("is_required") val isRequired: Boolean? = null
```

`VersionData` và `UpdateInfo` thêm `val isRequired: Boolean`, map `item.isRequired ?: false`.
Thêm ở **cuối** data class để giữ source-compatibility với host đang build. Bump minor
`VERSION_NAME` trong `gradle.properties`.

File mới `domain/entity/RequiredUpdate.kt`:

```kotlin
fun List<UpdateInfo>.requiredUpdate(): UpdateInfo? =
    filter { it.isRequired }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
```

`VersionService.shouldPresentUpdate` **không đổi**. Hệ quả có chủ ý: một version
`is_required = true` nhưng `is_download = false`, thiếu `file_id`, hoặc không mới hơn
version hiện tại sẽ bị lọc khỏi danh sách, nên gate không thấy nó và app chạy bình
thường. Không tồn tại trạng thái "bắt buộc nhưng không cài được" — server bật cờ mà
quên upload file thì không khoá chết máy POS ngoài tiệm.

## 2. `RequiredUpdateController` — headless

Files mới, package `com.vtcode.pos.version.presentation.update`:
`RequiredUpdateState.kt`, `RequiredUpdateController.kt`.

Không tái sử dụng `VersionListController`: nó mô hình hoá việc *duyệt danh sách*
version (items, channel, Current/Disabled), khác ngữ nghĩa với *một* bản bắt buộc.

```kotlin
sealed interface RequiredUpdateState {
    data object Checking : RequiredUpdateState
    data object NotRequired : RequiredUpdateState
    data class CheckFailed(val error: VersionError) : RequiredUpdateState
    data class Required(val update: UpdateInfo, val stage: UpdateStage) : RequiredUpdateState
}

sealed interface UpdateStage {
    data object Ready : UpdateStage
    data class Downloading(val percentage: Int, val downloadedBytes: Long, val totalBytes: Long) : UpdateStage
    data class Downloaded(val file: Path) : UpdateStage
    data object Installing : UpdateStage
    data object InstallPermissionRequired : UpdateStage
    data class Failed(val message: String) : UpdateStage
}
```

```kotlin
class RequiredUpdateController(
    private val scope: CoroutineScope,
    private val versionChecker: VersionChecker,
    private val currentVersion: String,
    private val cacheDir: Path,
    softwareCode: String? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val autoInstall: Boolean = true,
) {
    val state: StateFlow<RequiredUpdateState>
    fun check()
    fun download()
    fun install()
    fun requestInstallPermission(): Boolean
    fun retry()
    fun close()
}
```

Hành vi:

- `init` gọi `check()` và bắt đầu collect `platformInstallOutcomes`.
- `check()`: `Checking` → `versionChecker.check(softwareCode, currentVersion)`.
  Success → `requiredUpdate()`; null thì `NotRequired`, khác null thì `Required(update, Ready)`.
  Error → `CheckFailed(error)`.
- `download()`: no-op nếu stage không phải `Ready` hoặc `Failed` (chống double-tap).
  Tạo `DownloadProgressTracker`, map `DownloadProgress` → `UpdateStage`:
  `Downloading → Downloading`, `Completed → Downloaded(file)`, `Failed → Failed(msg)`,
  `Cancelled → Ready`. Khi `Completed` và `autoInstall = true` thì gọi `install()` ngay.
- `install()`: cài file đã tải gần nhất (`Downloaded.file`); no-op nếu chưa có file. Stage `Installing`, chạy `versionChecker.installUpdate` trên
  `Dispatchers.Default`. `Result.Error` mang `VersionError.InstallPermissionRequired`
  → stage `InstallPermissionRequired` (host bấm nút gọi `requestInstallPermission()`);
  lỗi khác → `Failed`. Success thì giữ `Installing` — UI hệ thống tiếp quản, kết quả
  thật về qua `platformInstallOutcomes`.
- `platformInstallOutcomes`: `Failure` → `Failed(message ?: "Cài đặt thất bại")`.
  `Success` → giữ `Installing` (tiến trình sắp bị thay thế).
- `retry()`: dispatch theo stage — `Failed`/`Ready` → `download()`;
  `Downloaded`/`InstallPermissionRequired` → `install()`;
  state `CheckFailed` → `check()`.
- `close()`: `scope.cancel()`.

Nhận `CoroutineScope` từ ngoài nên host dùng ViewModel riêng vẫn được, không buộc Compose.

Chuỗi ký tự hiển thị cho người dùng viết tiếng Việt, khớp phần còn lại của kit.

## 3. `RequiredUpdateGate` — composable

File mới `presentation/update/RequiredUpdateGate.kt`.

```kotlin
@Composable
fun rememberRequiredUpdateController(
    versionChecker: VersionChecker,
    currentVersion: String,
    cacheDir: Path,
    softwareCode: String? = null,
    fileSystem: FileSystem = FileSystem.SYSTEM,
    autoInstall: Boolean = true,
): RequiredUpdateController      // DisposableEffect { onDispose { close() } }

interface RequiredUpdateScope {
    val update: UpdateInfo
    val stage: UpdateStage
    fun download()
    fun install()
    fun retry()
    fun requestInstallPermission(): Boolean
}

@Composable
fun RequiredUpdateGate(
    controller: RequiredUpdateController,
    blockOnCheckFailure: Boolean = false,
    requiredContent: @Composable (RequiredUpdateScope) -> Unit,
    content: @Composable () -> Unit,
)
```

Gate luôn vẽ `content()`, rồi phủ overlay khi state là `Required` (hoặc `CheckFailed`
với `blockOnCheckFailure = true`):

- Overlay là `Box(Modifier.fillMaxSize())` nuốt toàn bộ pointer event
  (`pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }`),
  UI phía dưới không bấm được.
- Chặn back bằng `androidx.compose.ui.backhandler.BackHandler(enabled = true) {}`
  (có ở commonMain từ CMP 1.8; dự án đang dùng 1.9.0). Nếu khi implement phát hiện API
  chưa nằm ở common, rơi về expect/actual `BlockSystemBack()` — Android dùng
  `BackHandler`, desktop no-op. Không thêm dependency `activity-compose`.
- Nội dung overlay là `requiredContent(scope)` — hoàn toàn của host. Kit không áp
  Material, màu, chữ, không ship UI mặc định.

`blockOnCheckFailure = false` mặc định (fail-open): mất mạng lúc mở app thì cho vào.
Máy POS rớt wifi mà bị khoá là thảm hoạ vận hành.

## 4. Refactor kèm theo: `IVersionInstaller`

`InstallUpdateUseCase` đang phụ thuộc thẳng `expect class VersionInstaller`, nên không
fake được ở `commonTest` và luồng install không test được. Thêm ở commonMain:

```kotlin
interface IVersionInstaller {
    fun install(file: Path): Result<Unit, VersionError>
    fun requestInstallPermission(): Boolean
}
```

`expect class VersionInstaller : IVersionInstaller`; `InstallUpdateUseCase` nhận
`IVersionInstaller`. Koin giữ nguyên `single { InstallUpdateUseCase(get()) }` vì
platform module đã cung cấp `VersionInstaller`, chỉ thêm binding
`single<IVersionInstaller> { get<VersionInstaller>() }` ở module platform.
Không đổi hành vi runtime.

## 5. Test

`commonTest`, chạy nhanh nhất qua `./gradlew :version-kit-mp:desktopTest`.

Mapping:
- `VersionData.fromResponseItem`: `is_required` = true / false / null (→ false).
- `requiredUpdate()`: chọn version cao nhất trong các bản required; null khi không có bản nào.

`RequiredUpdateController` với fake `IVersionRepository`, fake `IVersionService`,
fake `IVersionInstaller`, `FakeFileSystem`, `runTest`:
- không có bản required → `NotRequired`
- có → `Required(Ready)`; `download()` → `Downloading` → `Downloaded` → `Installing` (autoInstall)
- install trả `InstallPermissionRequired` → stage tương ứng; `retry()` gọi lại install
- `platformInstallOutcomes` phát `Failure` → `Failed`; `retry()` gọi lại install
- download thất bại → `Failed`; `retry()` tải lại
- check lỗi mạng → `CheckFailed`
- gọi `download()` hai lần liên tiếp chỉ chạy một download

Mục tiêu coverage cho code mới: 80%+.

## Ảnh hưởng tới host

Không có breaking change. Host đang dùng `VersionListSection` không phải sửa gì.
Host muốn bắt buộc cập nhật thì bọc root composable:

```kotlin
val controller = rememberRequiredUpdateController(versionChecker, BuildConfig.VERSION_NAME, cacheDir)
RequiredUpdateGate(
    controller = controller,
    requiredContent = { scope -> MyForcedUpdateScreen(scope) },
) { AppNavHost() }
```

## Điều chỉnh sau khi implement (2026-08-27)

Code thật khác spec ở các điểm sau; code là nguồn sự thật, giữ mục này để đọc spec không bị lạc.

- `blockOnCheckFailure: Boolean` bị bỏ. Thay bằng `checkFailedContent: (@Composable () -> Unit)? = null`:
  truyền UI là opt-in chặn, `null` là fail-open. Lý do: bật cờ mà quên slot sẽ tạo overlay trong
  suốt chặn hết chạm/back/focus — trên máy POS trông như treo máy.
- Controller không launch trên `scope` host truyền vào mà tạo child scope; `close()` chỉ huỷ child.
  Scope **phải** main-confined (`viewModelScope` đạt) — state của controller không thread-safe.
- Thêm `recheckIntervalMs: Long? = 15 phút`. Fail-open mà không hỏi lại là mở vĩnh viễn; chỉ hỏi
  lại khi state là `NotRequired`/`CheckFailed`, không bao giờ khi `Required`.
- `check()` huỷ luôn download đang chạy, tránh download mồ côi tự kích install ở chu kỳ mới.
- `install()` có guard chống gọi lặp khi đang `Installing`.
- Outcome cài đặt từ nền tảng chỉ được áp khi đang chờ cài (cả hai controller), vì
  `InstallEventBus` toàn tiến trình không có token liên kết với lượt cài.
- `BackHandler` không có ở commonMain (CMP 1.9.0) → `internal expect/actual BlockSystemBack()`.
- Overlay chặn cả focus/bàn phím/accessibility, không chỉ chạm — máy quét mã vạch là bàn phím.
- `VersionRemoteDataSource` rethrow `CancellationException` thay vì nuốt vào `NetworkError`.
