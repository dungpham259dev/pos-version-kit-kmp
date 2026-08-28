# Required Update Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cho phép server đánh dấu một version là bắt buộc (`is_required`), và cho host app chặn toàn bộ app bằng UI do chính host vẽ cho tới khi cập nhật xong.

**Architecture:** Cờ `is_required` chảy từ `VersionResponseItem` → `VersionData` → `UpdateInfo`. Một state holder headless mới, `RequiredUpdateController`, sở hữu state machine check → download → install cho **một** bản bắt buộc (không đụng `VersionListController`, vốn mô hình hoá việc duyệt danh sách). Một composable `RequiredUpdateGate` phủ overlay chặn tương tác và back, nhận UI của host qua slot `requiredContent`.

**Tech Stack:** Kotlin Multiplatform 2.2.20, Compose Multiplatform 1.9.0, coroutines 1.9.0, okio 3.9.1, Koin, `kotlin.test` + `kotlinx-coroutines-test` + `okio-fakefilesystem` (không dùng mocking framework).

Spec: `docs/superpowers/specs/2026-08-27-required-update-gate-design.md`

## Global Constraints

- Module duy nhất: `:version-kit-mp`. Targets: `androidTarget` và `jvm("desktop")`.
- Lệnh test nhanh nhất: `./gradlew :version-kit-mp:desktopTest`. Build đủ hai target: `./gradlew :version-kit-mp:build`.
- Không dùng mocking framework. Test double là class tay viết trong `commonTest`.
- Error handling dùng `com.vtcode.pos.version.domain.error.Result` (tự viết), **không** phải `kotlin.Result`. Không exception nào thoát khỏi data layer.
- Chuỗi hiển thị cho người dùng viết **tiếng Việt**, khớp phần còn lại của kit (ví dụ `"Cài đặt thất bại"`, `"Thử lại"`).
- Trong `version-kit-mp/build.gradle.kts`: dependency xuất hiện trong public signature dùng `api(...)`, chi tiết nội bộ dùng `implementation(...)`. Task trong plan này **không** thêm dependency mới.
- Field mới của data class thêm ở **cuối** để giữ source-compatibility với host.
- Comment: mặc định không comment. Chỉ comment một dòng khi cần giải thích WHY.
- Commit theo conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`.
- Không sửa `VersionListSection.kt` / `VersionListController` trong bất kỳ task nào.

---

## File Structure

**Tạo mới:**

| File | Trách nhiệm |
|---|---|
| `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/domain/entity/RequiredUpdate.kt` | Helper `List<UpdateInfo>.requiredUpdate()` |
| `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/install/IVersionInstaller.kt` | Interface installer để test được |
| `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateState.kt` | `RequiredUpdateState`, `UpdateStage` |
| `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateController.kt` | State machine headless |
| `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateGate.kt` | `rememberRequiredUpdateController`, `RequiredUpdateScope`, `RequiredUpdateGate` |
| `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/fake/TestDoubles.kt` | Fake repository, fake installer, factory helpers |
| `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/data/model/VersionDataTest.kt` | Test mapping `is_required` |
| `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/domain/entity/RequiredUpdateTest.kt` | Test `requiredUpdate()` |
| `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateControllerTest.kt` | Test state machine |

**Sửa:**

| File | Thay đổi |
|---|---|
| `.../data/model/GetStableVersionResponse.kt` | thêm `isRequired` vào `VersionResponseItem` |
| `.../data/model/VersionData.kt` | thêm `isRequired` vào `VersionData`, `fromResponseItem`, `toUpdateInfo` |
| `.../domain/entity/UpdateInfo.kt` | thêm `isRequired` |
| `.../domain/usecase/InstallUpdateUseCase.kt` | nhận `IVersionInstaller` |
| `.../androidMain/.../install/VersionInstaller.android.kt` | implement `IVersionInstaller` |
| `.../desktopMain/.../install/VersionInstaller.desktop.kt` | implement `IVersionInstaller` |
| `.../commonMain/.../install/VersionInstaller.kt` | `expect class VersionInstaller : IVersionInstaller` |
| `gradle.properties` | `VERSION_NAME=0.3.0` |
| `CLAUDE.md` | tài liệu public API mới |

---

### Task 1: Cờ `is_required` chảy từ server tới domain

**Files:**
- Modify: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/data/model/GetStableVersionResponse.kt`
- Modify: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/data/model/VersionData.kt`
- Modify: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/domain/entity/UpdateInfo.kt`
- Test: `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/data/model/VersionDataTest.kt`

**Interfaces:**
- Consumes: không có (task đầu tiên).
- Produces: `UpdateInfo.isRequired: Boolean`, `VersionData.isRequired: Boolean`, `VersionResponseItem.isRequired: Boolean?`. Task 2, 5, 6, 7, 8 đều dùng `UpdateInfo.isRequired`.

- [ ] **Step 1: Viết test thất bại**

Tạo `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/data/model/VersionDataTest.kt`:

```kotlin
package com.vtcode.pos.version.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VersionDataTest {

    private fun item(isRequired: Boolean? = null) = VersionResponseItem(
        id = "v1",
        version = "2.0.0",
        fileId = "file-1",
        isDownload = true,
        isRequired = isRequired
    )

    @Test
    fun maps_is_required_true() {
        val data = VersionData.fromResponseItem(item(isRequired = true))
        assertNotNull(data)
        assertTrue(data.isRequired)
        assertTrue(data.toUpdateInfo().isRequired)
    }

    @Test
    fun maps_is_required_false() {
        val data = VersionData.fromResponseItem(item(isRequired = false))
        assertNotNull(data)
        assertFalse(data.isRequired)
    }

    @Test
    fun missing_is_required_defaults_to_false() {
        val data = VersionData.fromResponseItem(item(isRequired = null))
        assertNotNull(data)
        assertFalse(data.isRequired)
        assertFalse(data.toUpdateInfo().isRequired)
    }

    @Test
    fun drops_item_without_id_or_version() {
        assertEquals(null, VersionData.fromResponseItem(VersionResponseItem(id = null, version = "2.0.0")))
        assertEquals(null, VersionData.fromResponseItem(VersionResponseItem(id = "v1", version = null)))
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận nó fail**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.data.model.VersionDataTest"`
Expected: FAIL — compile error, `VersionResponseItem` không có tham số `isRequired`.

- [ ] **Step 3: Thêm field vào ba data class**

Trong `GetStableVersionResponse.kt`, thêm dòng cuối cùng của `VersionResponseItem` (sau `releaseNote`):

```kotlin
    @SerialName("release_note") val releaseNote: String? = null,
    @SerialName("is_required") val isRequired: Boolean? = null
```

Trong `UpdateInfo.kt`, thêm tham số cuối của `UpdateInfo` (sau `description`):

```kotlin
    val releaseNote: String?,
    val description: String?,
    val isRequired: Boolean = false
```

Trong `VersionData.kt`, thêm tham số cuối của `VersionData` (sau `description`):

```kotlin
    val releaseNote: String?,
    val description: String?,
    val isRequired: Boolean = false
```

Trong `VersionData.fromResponseItem`, thêm dòng cuối trước dấu `)` đóng:

```kotlin
                releaseNote = item.releaseNote,
                description = item.description,
                isRequired = item.isRequired ?: false
```

Trong `VersionData.toUpdateInfo()`, thêm dòng cuối trước dấu `)` đóng:

```kotlin
        releaseNote = this.releaseNote,
        description = this.description,
        isRequired = this.isRequired
```

- [ ] **Step 4: Chạy test để xác nhận pass**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.data.model.VersionDataTest"`
Expected: PASS, 4 test.

- [ ] **Step 5: Chạy toàn bộ test cũ để chắc không vỡ gì**

Run: `./gradlew :version-kit-mp:desktopTest`
Expected: PASS toàn bộ.

- [ ] **Step 6: Commit**

```bash
git add version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/data/model/GetStableVersionResponse.kt \
        version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/data/model/VersionData.kt \
        version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/domain/entity/UpdateInfo.kt \
        version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/data/model/VersionDataTest.kt
git commit -m "feat: map server is_required flag into UpdateInfo"
```

---

### Task 2: Helper `requiredUpdate()`

**Files:**
- Create: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/domain/entity/RequiredUpdate.kt`
- Test: `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/domain/entity/RequiredUpdateTest.kt`

**Interfaces:**
- Consumes: `UpdateInfo.isRequired` (Task 1), `compareVersions(v1: String, v2: String): Int` có sẵn trong `UpdateInfo.kt`.
- Produces: `fun List<UpdateInfo>.requiredUpdate(): UpdateInfo?` — Task 5 dùng.

- [ ] **Step 1: Viết test thất bại**

Tạo `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/domain/entity/RequiredUpdateTest.kt`:

```kotlin
package com.vtcode.pos.version.domain.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequiredUpdateTest {

    private fun update(version: String, isRequired: Boolean) = UpdateInfo(
        hasUpdate = true,
        versionId = version,
        version = version,
        releaseDate = "",
        softwareName = "ipos",
        platform = "android",
        fileId = "file-$version",
        filePath = "",
        fileSize = "1000",
        isDownloadable = true,
        releaseNote = null,
        description = null,
        isRequired = isRequired
    )

    @Test
    fun returns_null_when_no_required_update() {
        val list = listOf(update("2.0.0", false), update("3.0.0", false))
        assertNull(list.requiredUpdate())
    }

    @Test
    fun returns_null_for_empty_list() {
        assertNull(emptyList<UpdateInfo>().requiredUpdate())
    }

    @Test
    fun picks_highest_required_version() {
        val list = listOf(
            update("2.0.0", true),
            update("3.1.0", true),
            update("4.0.0", false)
        )
        assertEquals("3.1.0", list.requiredUpdate()?.version)
    }

    @Test
    fun ignores_non_required_even_if_higher() {
        val list = listOf(update("1.5.0", true), update("9.9.9", false))
        assertEquals("1.5.0", list.requiredUpdate()?.version)
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận nó fail**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.domain.entity.RequiredUpdateTest"`
Expected: FAIL — compile error, không tìm thấy `requiredUpdate`.

- [ ] **Step 3: Viết implementation**

Tạo `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/domain/entity/RequiredUpdate.kt`:

```kotlin
package com.vtcode.pos.version.domain.entity

/**
 * Bản cập nhật bắt buộc cao nhất trong danh sách, hoặc null nếu không có.
 *
 * Danh sách truyền vào đã được [com.vtcode.pos.version.domain.service.IVersionService.shouldPresentUpdate]
 * lọc, nên mọi phần tử ở đây đều mới hơn version hiện tại và tải được. Hệ quả có chủ ý:
 * một version bật `is_required` nhưng thiếu file sẽ không bao giờ tới được đây, nên không
 * khoá được app.
 */
fun List<UpdateInfo>.requiredUpdate(): UpdateInfo? =
    filter { it.isRequired }
        .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
```

- [ ] **Step 4: Chạy test để xác nhận pass**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.domain.entity.RequiredUpdateTest"`
Expected: PASS, 4 test.

- [ ] **Step 5: Commit**

```bash
git add version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/domain/entity/RequiredUpdate.kt \
        version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/domain/entity/RequiredUpdateTest.kt
git commit -m "feat: add requiredUpdate() selector for mandatory versions"
```

---

### Task 3: Tách `IVersionInstaller` để test được luồng install

**Files:**
- Create: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/install/IVersionInstaller.kt`
- Modify: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/install/VersionInstaller.kt`
- Modify: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/domain/usecase/InstallUpdateUseCase.kt`
- Modify: `version-kit-mp/src/androidMain/kotlin/com/vtcode/pos/version/presentation/install/VersionInstaller.android.kt`
- Modify: `version-kit-mp/src/desktopMain/kotlin/com/vtcode/pos/version/presentation/install/VersionInstaller.desktop.kt`

**Interfaces:**
- Consumes: không có.
- Produces: `interface IVersionInstaller { fun install(file: Path): Result<Unit, VersionError>; fun requestInstallPermission(): Boolean }`. Task 5 dùng để dựng fake.

Đây là refactor thuần: không đổi hành vi runtime, không thêm test mới. Test hiện có + build hai target là lưới an toàn.

- [ ] **Step 1: Tạo interface**

Tạo `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/install/IVersionInstaller.kt`:

```kotlin
package com.vtcode.pos.version.presentation.install

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path

/**
 * Hợp đồng cài đặt của nền tảng. Tách khỏi `expect class VersionInstaller` để
 * commonTest dựng được test double cho luồng install.
 */
interface IVersionInstaller {
    fun install(file: Path): Result<Unit, VersionError>

    /**
     * Mở luồng cấp quyền cài đặt của nền tảng (Android mở màn hình
     * "install unknown apps"). Trả `true` nếu đã mở được. Nền tảng không cần
     * quyền này thì no-op trả `true`.
     */
    fun requestInstallPermission(): Boolean
}
```

- [ ] **Step 2: Cho `expect class` implement interface**

Thay toàn bộ nội dung `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/install/VersionInstaller.kt`:

```kotlin
package com.vtcode.pos.version.presentation.install

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path

/** Platform installer: Android = PackageInstaller, Desktop = launch .msi/.exe then exit. */
expect class VersionInstaller : IVersionInstaller {
    override fun install(file: Path): Result<Unit, VersionError>
    override fun requestInstallPermission(): Boolean
}
```

- [ ] **Step 3: Thêm `override` cho hai actual**

Trong `VersionInstaller.android.kt`, đổi hai chữ ký:

```kotlin
actual class VersionInstaller(private val context: Context) : IVersionInstaller {
    actual override fun install(file: Path): Result<Unit, VersionError> {
```

và

```kotlin
    actual override fun requestInstallPermission(): Boolean =
        ApkInstaller(context).requestInstallPermission()
```

Trong `VersionInstaller.desktop.kt`, tương tự:

```kotlin
actual class VersionInstaller : IVersionInstaller {
    actual override fun install(file: Path): Result<Unit, VersionError> {
```

và

```kotlin
    // Desktop installs do not require a runtime "unknown sources" permission.
    actual override fun requestInstallPermission(): Boolean = true
```

- [ ] **Step 4: Đổi use case sang phụ thuộc interface**

Trong `InstallUpdateUseCase.kt`, đổi import và tham số constructor:

```kotlin
import com.vtcode.pos.version.presentation.install.IVersionInstaller

class InstallUpdateUseCase(
    private val installer: IVersionInstaller
) {
```

Phần thân giữ nguyên.

Koin **phải** sửa. `single { VersionInstaller(...) }` chỉ đăng ký định nghĩa dưới type cụ thể `VersionInstaller`; `InstallUpdateUseCase(get())` giờ suy ra `get<IVersionInstaller>()` và sẽ ném `NoDefinitionFoundException` lúc chạy. Thêm binding vào **cả hai** module platform:

```kotlin
// VersionKitAndroidModule.kt
single { VersionInstaller(androidContext()) }
single<IVersionInstaller> { get<VersionInstaller>() }
```

```kotlin
// VersionKitDesktopModule.kt
single { VersionInstaller() }
single<IVersionInstaller> { get<VersionInstaller>() }
```

Thêm `import com.vtcode.pos.version.presentation.install.IVersionInstaller` vào cả hai file. Không test nào hiện dựng Koin container, nên build xanh **không** chứng minh chỗ này đúng.

- [ ] **Step 5: Build cả hai target**

Run: `./gradlew :version-kit-mp:build`
Expected: BUILD SUCCESSFUL, toàn bộ test cũ pass.

- [ ] **Step 6: Commit**

```bash
git add version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/install/IVersionInstaller.kt \
        version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/install/VersionInstaller.kt \
        version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/domain/usecase/InstallUpdateUseCase.kt \
        version-kit-mp/src/androidMain/kotlin/com/vtcode/pos/version/presentation/install/VersionInstaller.android.kt \
        version-kit-mp/src/desktopMain/kotlin/com/vtcode/pos/version/presentation/install/VersionInstaller.desktop.kt
git commit -m "refactor: extract IVersionInstaller so install flow is testable"
```

---

### Task 4: Test doubles dùng chung

**Files:**
- Create: `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/fake/TestDoubles.kt`

**Interfaces:**
- Consumes: `IVersionInstaller` (Task 3), `UpdateInfo.isRequired` (Task 1), `IVersionRepository`, `VersionService`, `VersionChecker`, `VersionKitConfig` (đã có).
- Produces: `FakeVersionRepository`, `FakeInstaller`, `fun testUpdate(...): UpdateInfo`, `fun testVersionChecker(repo, installer): VersionChecker`. Task 5, 6, 7 dùng.

Task này không có test riêng — nó là hạ tầng test, được Task 5 chứng minh là chạy được.
Deliverable là "commonTest vẫn compile với các test double mới".

- [ ] **Step 1: Viết file test double**

Tạo `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/fake/TestDoubles.kt`:

```kotlin
package com.vtcode.pos.version.fake

import com.vtcode.pos.version.di.VersionKitConfig
import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.repository.IVersionRepository
import com.vtcode.pos.version.domain.service.VersionService
import com.vtcode.pos.version.domain.usecase.CheckUpdateUseCase
import com.vtcode.pos.version.domain.usecase.DownloadUpdateUseCase
import com.vtcode.pos.version.domain.usecase.InstallUpdateUseCase
import com.vtcode.pos.version.presentation.VersionChecker
import com.vtcode.pos.version.presentation.install.IVersionInstaller
import okio.FileSystem
import okio.Path

fun testUpdate(
    version: String = "2.0.0",
    isRequired: Boolean = true,
    isDownloadable: Boolean = true,
    fileId: String = "file-1"
): UpdateInfo = UpdateInfo(
    hasUpdate = true,
    versionId = version,
    version = version,
    releaseDate = "2026-08-27",
    softwareName = "ipos",
    platform = "android",
    fileId = fileId,
    filePath = "",
    fileSize = "1000",
    isDownloadable = isDownloadable,
    releaseNote = null,
    description = null,
    isRequired = isRequired
)

class FakeVersionRepository(
    var updates: List<UpdateInfo> = emptyList(),
    var checkError: VersionError? = null,
    var downloadError: VersionError? = null,
    /** Ghi rác thay vì ZIP magic để giả lập file tải về hỏng. */
    var writeCorruptFile: Boolean = false
) : IVersionRepository {

    var checkCount = 0
    var downloadCount = 0

    override suspend fun checkForUpdate(
        softwareCode: String,
        currentVersion: String
    ): Result<List<UpdateInfo>, VersionError> {
        checkCount++
        checkError?.let { return Result.Error(it) }
        return Result.Success(updates)
    }

    override suspend fun downloadFileWithProgress(
        fileId: String,
        destinationDir: Path,
        fileSystem: FileSystem,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Path, VersionError> {
        downloadCount++
        downloadError?.let { return Result.Error(it) }
        onProgress(50, 100L, 50L)
        fileSystem.createDirectories(destinationDir)
        val file = destinationDir / "update_$fileId"
        fileSystem.write(file) {
            if (writeCorruptFile) {
                write("<html>error</html>".encodeToByteArray())
            } else {
                write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            }
        }
        onProgress(100, 100L, 100L)
        return Result.Success(file)
    }
}

class FakeInstaller(
    var result: Result<Unit, VersionError> = Result.Success(Unit),
    var permissionLaunched: Boolean = true
) : IVersionInstaller {

    val installed = mutableListOf<Path>()
    var permissionRequests = 0

    override fun install(file: Path): Result<Unit, VersionError> {
        installed += file
        return result
    }

    override fun requestInstallPermission(): Boolean {
        permissionRequests++
        return permissionLaunched
    }
}

fun testVersionChecker(
    repository: IVersionRepository,
    installer: IVersionInstaller
): VersionChecker {
    val service = VersionService()
    return VersionChecker(
        CheckUpdateUseCase(repository, service),
        DownloadUpdateUseCase(repository, service),
        InstallUpdateUseCase(installer),
        VersionKitConfig(baseUrl = "https://test.local", softwareCode = "ipos")
    )
}
```

- [ ] **Step 2: Xác nhận commonTest vẫn compile**

Run: `./gradlew :version-kit-mp:desktopTest`
Expected: PASS toàn bộ test cũ (file mới chưa có test nào, chỉ cần compile được).

- [ ] **Step 3: Commit**

```bash
git add version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/fake/TestDoubles.kt
git commit -m "test: add shared test doubles for version kit flows"
```

---

### Task 5: `RequiredUpdateController` — luồng check

**Files:**
- Create: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateState.kt`
- Create: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateController.kt`
- Test: `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateControllerTest.kt`

**Interfaces:**
- Consumes: `requiredUpdate()` (Task 2), `FakeVersionRepository` / `FakeInstaller` / `testUpdate` / `testVersionChecker` (Task 4), `platformInstallOutcomes: Flow<InstallOutcome>` (`internal`, đã có), `InstallOutcome` (public, đã có).
- Produces:
  - `sealed interface RequiredUpdateState` với `Checking`, `NotRequired`, `CheckFailed(error: VersionError)`, `Required(update: UpdateInfo, stage: UpdateStage)`
  - `sealed interface UpdateStage` với `Ready`, `Downloading(percentage: Int, downloadedBytes: Long, totalBytes: Long)`, `Downloaded(file: Path)`, `Installing`, `InstallPermissionRequired`, `Failed(message: String)`
  - `class RequiredUpdateController` với `state: StateFlow<RequiredUpdateState>`, `check()`, `download()`, `install()`, `requestInstallPermission(): Boolean`, `retry()`, `close()`
  - Task 6, 7 mở rộng cùng class này; Task 8 dùng nó.

- [ ] **Step 1: Viết test thất bại cho luồng check**

Tạo `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateControllerTest.kt`:

```kotlin
package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.fake.FakeInstaller
import com.vtcode.pos.version.fake.FakeVersionRepository
import com.vtcode.pos.version.fake.testUpdate
import com.vtcode.pos.version.fake.testVersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequiredUpdateControllerTest {

    private val cacheDir = "/cache".toPath()

    private fun controller(
        scope: CoroutineScope,
        repository: FakeVersionRepository,
        installer: FakeInstaller = FakeInstaller(),
        fileSystem: FakeFileSystem = FakeFileSystem(),
        autoInstall: Boolean = true
    ): RequiredUpdateController {
        fileSystem.createDirectories(cacheDir)
        return RequiredUpdateController(
            scope = scope,
            versionChecker = testVersionChecker(repository, installer),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem,
            autoInstall = autoInstall
        )
    }

    private suspend fun RequiredUpdateController.awaitState(
        predicate: (RequiredUpdateState) -> Boolean
    ): RequiredUpdateState = withTimeout(5_000) { state.first(predicate) }

    @Test
    fun no_required_update_yields_NotRequired() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate(isRequired = false)))
        val sut = controller(backgroundScope, repository)

        assertEquals(RequiredUpdateState.NotRequired, sut.awaitState { it is RequiredUpdateState.NotRequired })
    }

    @Test
    fun empty_update_list_yields_NotRequired() = runTest(UnconfinedTestDispatcher()) {
        val sut = controller(backgroundScope, FakeVersionRepository(updates = emptyList()))

        assertEquals(RequiredUpdateState.NotRequired, sut.awaitState { it is RequiredUpdateState.NotRequired })
    }

    @Test
    fun required_update_not_downloadable_yields_NotRequired() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(
            updates = listOf(testUpdate(isRequired = true, isDownloadable = false))
        )
        val sut = controller(backgroundScope, repository)

        assertEquals(RequiredUpdateState.NotRequired, sut.awaitState { it is RequiredUpdateState.NotRequired })
    }

    @Test
    fun required_update_yields_Required_Ready() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate(version = "2.0.0")))
        val sut = controller(backgroundScope, repository)

        val state = sut.awaitState { it is RequiredUpdateState.Required }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals("2.0.0", state.update.version)
        assertEquals(UpdateStage.Ready, state.stage)
    }

    @Test
    fun check_error_yields_CheckFailed() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository)

        val state = sut.awaitState { it is RequiredUpdateState.CheckFailed }
        assertIs<RequiredUpdateState.CheckFailed>(state)
        assertEquals(VersionError.TimeoutError, state.error)
    }

    @Test
    fun retry_after_check_failure_checks_again() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository)
        sut.awaitState { it is RequiredUpdateState.CheckFailed }

        repository.checkError = null
        repository.updates = listOf(testUpdate())
        sut.retry()

        assertIs<RequiredUpdateState.Required>(sut.awaitState { it is RequiredUpdateState.Required })
        assertTrue(repository.checkCount >= 2)
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận nó fail**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.presentation.update.RequiredUpdateControllerTest"`
Expected: FAIL — compile error, không tìm thấy `RequiredUpdateController`.

- [ ] **Step 3: Viết file state**

Tạo `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateState.kt`:

```kotlin
package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path

/** Trạng thái của luồng cập nhật bắt buộc. */
sealed interface RequiredUpdateState {
    /** Đang hỏi server. */
    data object Checking : RequiredUpdateState

    /** Không có bản bắt buộc nào — app chạy bình thường. */
    data object NotRequired : RequiredUpdateState

    /** Không hỏi được server. Mặc định gate cho app chạy tiếp (fail-open). */
    data class CheckFailed(val error: VersionError) : RequiredUpdateState

    /** Có bản bắt buộc. [stage] cho biết đang ở bước nào của việc cài nó. */
    data class Required(val update: UpdateInfo, val stage: UpdateStage) : RequiredUpdateState
}

/** Bước hiện tại trong việc tải và cài bản bắt buộc. */
sealed interface UpdateStage {
    /** Chưa tải. */
    data object Ready : UpdateStage

    data class Downloading(
        val percentage: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateStage

    data class Downloaded(val file: Path) : UpdateStage

    /** Đã gọi installer, đang chờ nền tảng. */
    data object Installing : UpdateStage

    /** Android chưa được cấp quyền "cài ứng dụng không xác định". */
    data object InstallPermissionRequired : UpdateStage

    /** Luôn thử lại được bằng [RequiredUpdateController.retry]. */
    data class Failed(val message: String) : UpdateStage
}
```

- [ ] **Step 4: Viết controller với luồng check**

Tạo `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateController.kt`:

```kotlin
package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.requiredUpdate
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.presentation.VersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path

/**
 * State holder headless cho luồng cập nhật bắt buộc.
 *
 * Nhận [scope] từ ngoài nên dùng được cả trong Compose (qua
 * [rememberRequiredUpdateController]) lẫn trong ViewModel riêng của host. Gọi [close]
 * khi không dùng nữa.
 */
class RequiredUpdateController(
    private val scope: CoroutineScope,
    private val versionChecker: VersionChecker,
    private val currentVersion: String,
    private val cacheDir: Path,
    softwareCode: String? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val autoInstall: Boolean = true
) {

    private val softwareCode: String = softwareCode
        ?: versionChecker.defaultSoftwareCode
        ?: error("softwareCode not provided and VersionKitConfig.softwareCode is null")

    private val _state = MutableStateFlow<RequiredUpdateState>(RequiredUpdateState.Checking)
    val state: StateFlow<RequiredUpdateState> = _state.asStateFlow()

    init {
        check()
    }

    fun check() {
        scope.launch {
            _state.value = RequiredUpdateState.Checking
            when (val result = versionChecker.check(softwareCode, currentVersion)) {
                is Result.Success -> {
                    val required = result.value.requiredUpdate()
                    _state.value = if (required == null) {
                        RequiredUpdateState.NotRequired
                    } else {
                        RequiredUpdateState.Required(required, UpdateStage.Ready)
                    }
                }

                is Result.Error -> {
                    _state.value = RequiredUpdateState.CheckFailed(result.error)
                }
            }
        }
    }

    fun retry() {
        when (_state.value) {
            is RequiredUpdateState.CheckFailed -> check()
            else -> Unit
        }
    }

    /** Huỷ scope của controller. */
    fun close() {
        scope.cancel()
    }
}
```

- [ ] **Step 5: Chạy test để xác nhận pass**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.presentation.update.RequiredUpdateControllerTest"`
Expected: PASS, 6 test.

- [ ] **Step 6: Commit**

```bash
git add version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/ \
        version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/presentation/update/
git commit -m "feat: add RequiredUpdateController with check flow"
```

---

### Task 6: Luồng download và auto-install

> **Điều chỉnh sau review Task 5:** controller không còn `launch` trực tiếp trên `scope`
> host truyền vào. Nó tự dựng một child scope (`controllerScope`) và `close()` chỉ huỷ
> child đó. Mọi `scope.launch { ... }` trong đoạn code của Task 6 và Task 7 dưới đây phải
> đọc là `controllerScope.launch { ... }` — dùng đúng tên thuộc tính có trong file sau
> Task 5. `check()` cũng đã giữ `checkJob` và huỷ job cũ trước khi chạy job mới.


**Files:**
- Modify: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateController.kt`
- Test: `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateControllerTest.kt`

**Interfaces:**
- Consumes: mọi thứ Task 5 sinh ra; `DownloadProgress`, `DownloadProgressTracker` (đã có trong `domain/usecase/DownloadProgress.kt`).
- Produces: `RequiredUpdateController.download()` và `install()` hoạt động; field nội bộ `lastDownloadedFile: Path?`. Task 7 mở rộng nhánh lỗi install.

- [ ] **Step 1: Thêm test thất bại**

Thêm các test sau vào cuối class `RequiredUpdateControllerTest` (trước dấu `}` đóng class), và thêm import `com.vtcode.pos.version.domain.error.Result` cùng `kotlin.test.assertEquals` nếu chưa có:

```kotlin
    @Test
    fun download_reaches_Downloaded_and_auto_installs() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller()
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val installing = sut.awaitState {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }
        assertIs<RequiredUpdateState.Required>(installing)
        assertEquals(1, installer.installed.size)
        assertEquals("update_file-1", installer.installed.first().name)
    }

    @Test
    fun download_stops_at_Downloaded_when_autoInstall_disabled() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller()
        val sut = controller(backgroundScope, repository, installer, autoInstall = false)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val state = sut.awaitState {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Downloaded
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertTrue(installer.installed.isEmpty())

        sut.install()
        sut.awaitState { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun download_error_yields_Failed_stage() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(
            updates = listOf(testUpdate()),
            downloadError = VersionError.NetworkError("offline")
        )
        val sut = controller(backgroundScope, repository)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val state = sut.awaitState {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertTrue((state.stage as UpdateStage.Failed).message.isNotBlank())
    }

    @Test
    fun corrupt_download_yields_Failed_stage() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(
            updates = listOf(testUpdate()),
            writeCorruptFile = true
        )
        val installer = FakeInstaller()
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        sut.awaitState { it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed }
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun retry_after_download_failure_downloads_again() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(
            updates = listOf(testUpdate()),
            downloadError = VersionError.NetworkError("offline")
        )
        val sut = controller(backgroundScope, repository)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitState { it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed }

        repository.downloadError = null
        sut.retry()

        sut.awaitState { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }
        assertEquals(2, repository.downloadCount)
    }

    @Test
    fun second_download_call_while_downloading_is_ignored() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val sut = controller(backgroundScope, repository)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()
        sut.download()

        sut.awaitState { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }
        assertEquals(1, repository.downloadCount)
    }
```

- [ ] **Step 2: Chạy test để xác nhận nó fail**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.presentation.update.RequiredUpdateControllerTest"`
Expected: FAIL — compile error, không tìm thấy `download()` / `install()`.

- [ ] **Step 3: Thêm download và install vào controller**

Trong `RequiredUpdateController.kt`, thêm import:

```kotlin
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.usecase.DownloadProgress
import com.vtcode.pos.version.domain.usecase.DownloadProgressTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
```

Thêm field ngay dưới `_state` / `state`:

```kotlin
    private var lastDownloadedFile: Path? = null
    private var downloadTracker: DownloadProgressTracker? = null
```

Thêm hai hàm public và một helper, đặt phía trên `retry()`:

```kotlin
    /** Tải bản bắt buộc. No-op nếu không ở [UpdateStage.Ready] hoặc [UpdateStage.Failed]. */
    fun download() {
        val current = _state.value as? RequiredUpdateState.Required ?: return
        if (current.stage !is UpdateStage.Ready && current.stage !is UpdateStage.Failed) return
        if (downloadTracker?.isInProgress == true) return

        val update = current.update
        val tracker = DownloadProgressTracker()
        downloadTracker = tracker
        setStage(UpdateStage.Downloading(0, 0L, 0L))

        scope.launch {
            tracker.progress.collectLatest { progress ->
                when (progress) {
                    is DownloadProgress.Downloading -> setStage(
                        UpdateStage.Downloading(
                            percentage = progress.percentage,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes
                        )
                    )

                    is DownloadProgress.Completed -> {
                        lastDownloadedFile = progress.file
                        downloadTracker = null
                        setStage(UpdateStage.Downloaded(progress.file))
                        if (autoInstall) install()
                    }

                    is DownloadProgress.Failed -> {
                        downloadTracker = null
                        setStage(UpdateStage.Failed("Tải xuống thất bại: ${progress.error}"))
                    }

                    is DownloadProgress.Cancelled -> {
                        downloadTracker = null
                        setStage(UpdateStage.Ready)
                    }

                    DownloadProgress.Idle -> Unit
                }
            }
        }

        scope.launch {
            versionChecker.downloadUpdate(update, cacheDir, fileSystem, tracker)
        }
    }

    /** Cài file đã tải gần nhất. No-op nếu chưa tải xong. */
    fun install() {
        val file = lastDownloadedFile ?: return
        if (_state.value !is RequiredUpdateState.Required) return

        setStage(UpdateStage.Installing)
        scope.launch {
            val result = withContext(Dispatchers.Default) { versionChecker.installUpdate(file) }
            when (result) {
                // UI hệ thống tiếp quản; kết quả thật về qua platformInstallOutcomes.
                is Result.Success -> Unit

                is Result.Error -> setStage(
                    if (result.error is VersionError.InstallPermissionRequired) {
                        UpdateStage.InstallPermissionRequired
                    } else {
                        UpdateStage.Failed(result.error.message)
                    }
                )
            }
        }
    }

    /** Mở màn hình cấp quyền cài đặt của nền tảng. */
    fun requestInstallPermission(): Boolean = versionChecker.requestInstallPermission()

    private fun setStage(stage: UpdateStage) {
        val current = _state.value as? RequiredUpdateState.Required ?: return
        _state.value = current.copy(stage = stage)
    }
```

Thay thân `retry()` bằng:

```kotlin
    fun retry() {
        when (val current = _state.value) {
            is RequiredUpdateState.CheckFailed -> check()
            is RequiredUpdateState.Required -> when (current.stage) {
                is UpdateStage.Ready, is UpdateStage.Failed -> download()
                is UpdateStage.Downloaded, UpdateStage.InstallPermissionRequired -> install()
                else -> Unit
            }
            else -> Unit
        }
    }
```

- [ ] **Step 4: Chạy test để xác nhận pass**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.presentation.update.RequiredUpdateControllerTest"`
Expected: PASS, 12 test.

- [ ] **Step 5: Commit**

```bash
git add version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateController.kt \
        version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateControllerTest.kt
git commit -m "feat: download and auto-install flow in RequiredUpdateController"
```

---

### Task 7: Lỗi install và outcome bất đồng bộ từ nền tảng

**Files:**
- Modify: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateController.kt`
- Test: `version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateControllerTest.kt`

**Interfaces:**
- Consumes: Task 6; `InstallOutcome` (public sealed interface: `InstallOutcome.Success`, `InstallOutcome.Failure(message: String?)`), `platformInstallOutcomes: Flow<InstallOutcome>` (`internal`).
- Produces: constructor `internal` đầy đủ nhận thêm `installOutcomes: Flow<InstallOutcome>`, và constructor public giữ nguyên chữ ký Task 5. Task 8 chỉ dùng constructor public.

Trên Android, người dùng bấm Huỷ trên dialog hệ thống thì kết quả về qua broadcast → `platformInstallOutcomes`. Không xử lý thì stage kẹt ở `Installing` vĩnh viễn.

- [ ] **Step 1: Thêm test thất bại**

Thêm import vào đầu file test:

```kotlin
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.presentation.install.InstallOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
```

Đổi helper `controller(...)` trong file test để nhận thêm outcome flow:

```kotlin
    private fun controller(
        scope: CoroutineScope,
        repository: FakeVersionRepository,
        installer: FakeInstaller = FakeInstaller(),
        fileSystem: FakeFileSystem = FakeFileSystem(),
        autoInstall: Boolean = true,
        installOutcomes: Flow<InstallOutcome> = MutableSharedFlow()
    ): RequiredUpdateController {
        fileSystem.createDirectories(cacheDir)
        return RequiredUpdateController(
            scope = scope,
            versionChecker = testVersionChecker(repository, installer),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem,
            autoInstall = autoInstall,
            installOutcomes = installOutcomes
        )
    }
```

Thêm các test sau vào cuối class:

```kotlin
    @Test
    fun install_permission_error_yields_InstallPermissionRequired() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallPermissionRequired))
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        sut.awaitState {
            it is RequiredUpdateState.Required && it.stage == UpdateStage.InstallPermissionRequired
        }
        assertEquals(0, installer.permissionRequests)

        assertTrue(sut.requestInstallPermission())
        assertEquals(1, installer.permissionRequests)
    }

    @Test
    fun retry_after_permission_required_installs_again() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallPermissionRequired))
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitState {
            it is RequiredUpdateState.Required && it.stage == UpdateStage.InstallPermissionRequired
        }

        installer.result = Result.Success(Unit)
        sut.retry()

        sut.awaitState { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }
        assertEquals(2, installer.installed.size)
    }

    @Test
    fun install_error_yields_Failed_stage() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallError("signature mismatch")))
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val state = sut.awaitState {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertTrue((state.stage as UpdateStage.Failed).message.contains("signature mismatch"))
    }

    @Test
    fun platform_install_failure_yields_Failed_stage() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val outcomes = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 1)
        val sut = controller(backgroundScope, repository, installOutcomes = outcomes)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitState { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }

        outcomes.emit(InstallOutcome.Failure("Người dùng đã huỷ"))

        val state = sut.awaitState {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals("Người dùng đã huỷ", (state.stage as UpdateStage.Failed).message)
    }

    @Test
    fun platform_install_failure_without_message_uses_default_text() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val outcomes = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 1)
        val sut = controller(backgroundScope, repository, installOutcomes = outcomes)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitState { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }

        outcomes.emit(InstallOutcome.Failure(null))

        val state = sut.awaitState {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals("Cài đặt thất bại", (state.stage as UpdateStage.Failed).message)
    }
```

- [ ] **Step 2: Chạy test để xác nhận nó fail**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.presentation.update.RequiredUpdateControllerTest"`
Expected: FAIL — compile error, constructor không có tham số `installOutcomes`.

- [ ] **Step 3: Thêm constructor internal và collect outcomes**

Trong `RequiredUpdateController.kt`, thêm import:

```kotlin
import com.vtcode.pos.version.presentation.install.InstallOutcome
import com.vtcode.pos.version.presentation.install.platformInstallOutcomes
import kotlinx.coroutines.flow.Flow
```

Đổi khai báo class: constructor chính thành `internal` nhận thêm `installOutcomes`, và thêm constructor public giữ nguyên chữ ký cũ:

```kotlin
class RequiredUpdateController internal constructor(
    private val scope: CoroutineScope,
    private val versionChecker: VersionChecker,
    private val currentVersion: String,
    private val cacheDir: Path,
    softwareCode: String? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val autoInstall: Boolean = true,
    private val installOutcomes: Flow<InstallOutcome>
) {

    constructor(
        scope: CoroutineScope,
        versionChecker: VersionChecker,
        currentVersion: String,
        cacheDir: Path,
        softwareCode: String? = null,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        autoInstall: Boolean = true
    ) : this(
        scope = scope,
        versionChecker = versionChecker,
        currentVersion = currentVersion,
        cacheDir = cacheDir,
        softwareCode = softwareCode,
        fileSystem = fileSystem,
        autoInstall = autoInstall,
        installOutcomes = platformInstallOutcomes
    )
```

Constructor public tham chiếu `internal val platformInstallOutcomes` trong delegation
call. Nếu compiler từ chối, đổi `platformInstallOutcomes` từ `internal` thành `public`
trong `presentation/install/PlatformInstallEvents.kt` (và cả hai actual) — nó vốn là
thông tin host có thể quan tâm, không có gì phải giấu. Không dùng `@Suppress`.

Đổi `init` để collect outcomes:

```kotlin
    init {
        check()
        observeInstallOutcomes()
    }

    /**
     * Android trả kết quả cài đặt bất đồng bộ qua broadcast sau khi dialog hệ thống đóng.
     * Không nghe thì stage kẹt ở [UpdateStage.Installing] khi người dùng bấm Huỷ.
     */
    private fun observeInstallOutcomes() {
        scope.launch {
            installOutcomes.collect { outcome ->
                when (outcome) {
                    // Tiến trình sắp bị thay thế; giữ nguyên Installing.
                    is InstallOutcome.Success -> Unit

                    is InstallOutcome.Failure -> setStage(
                        UpdateStage.Failed(
                            outcome.message?.takeIf { it.isNotBlank() } ?: "Cài đặt thất bại"
                        )
                    )
                }
            }
        }
    }
```

- [ ] **Step 4: Chạy test để xác nhận pass**

Run: `./gradlew :version-kit-mp:desktopTest --tests "com.vtcode.pos.version.presentation.update.RequiredUpdateControllerTest"`
Expected: PASS, 17 test.

- [ ] **Step 5: Chạy toàn bộ test**

Run: `./gradlew :version-kit-mp:desktopTest`
Expected: PASS toàn bộ.

- [ ] **Step 6: Commit**

```bash
git add version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateController.kt \
        version-kit-mp/src/commonTest/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateControllerTest.kt
git commit -m "feat: surface install failures and platform install outcomes"
```

---

### Task 8: `RequiredUpdateGate` composable

**Files:**
- Create: `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateGate.kt`

**Interfaces:**
- Consumes: `RequiredUpdateController` (Task 5–7), `RequiredUpdateState`, `UpdateStage`, `UpdateInfo`.
- Produces:
  - `@Composable fun rememberRequiredUpdateController(versionChecker, currentVersion, cacheDir, softwareCode = null, fileSystem = FileSystem.SYSTEM, autoInstall = true): RequiredUpdateController`
  - `interface RequiredUpdateScope { val update: UpdateInfo; val stage: UpdateStage; fun download(); fun install(); fun retry(); fun requestInstallPermission(): Boolean }`
  - `@Composable fun RequiredUpdateGate(controller, blockOnCheckFailure = false, requiredContent: @Composable (RequiredUpdateScope) -> Unit, content: @Composable () -> Unit)`

Module chưa có hạ tầng Compose UI test, và Task này không thêm dependency test mới. Kiểm chứng bằng compile hai target — nhất quán với `VersionListSection` hiện tại (cũng không có UI test).

- [ ] **Step 1: Viết gate**

Tạo `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/RequiredUpdateGate.kt`:

```kotlin
package com.vtcode.pos.version.presentation.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.presentation.VersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path

/**
 * Tạo và ghi nhớ một [RequiredUpdateController]; huỷ scope của nó khi composable rời khỏi cây.
 */
@Composable
fun rememberRequiredUpdateController(
    versionChecker: VersionChecker,
    currentVersion: String,
    cacheDir: Path,
    softwareCode: String? = null,
    fileSystem: FileSystem = FileSystem.SYSTEM,
    autoInstall: Boolean = true
): RequiredUpdateController {
    val controller = remember(versionChecker, currentVersion, cacheDir, softwareCode) {
        RequiredUpdateController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            versionChecker = versionChecker,
            currentVersion = currentVersion,
            cacheDir = cacheDir,
            softwareCode = softwareCode,
            fileSystem = fileSystem,
            autoInstall = autoInstall
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}

/** Những gì UI của host nhận được để vẽ màn hình bắt buộc cập nhật. */
interface RequiredUpdateScope {
    val update: UpdateInfo
    val stage: UpdateStage
    fun download()
    fun install()
    fun retry()
    fun requestInstallPermission(): Boolean
}

/**
 * Vẽ [content], rồi phủ lên trên UI của host khi có bản bắt buộc phải cài.
 *
 * Overlay nuốt toàn bộ chạm và chặn nút back, nên [content] không thao tác được cho tới
 * khi cài xong. Kit không vẽ gì bên trong overlay — [requiredContent] là của host.
 *
 * @param blockOnCheckFailure chặn luôn khi không hỏi được server. Mặc định `false`
 *   (fail-open): máy POS mất mạng vẫn vào được app.
 * @param checkFailedContent UI hiện khi [blockOnCheckFailure] bật và không hỏi được
 *   server. Bỏ qua khi `blockOnCheckFailure = false`.
 */
@Composable
fun RequiredUpdateGate(
    controller: RequiredUpdateController,
    blockOnCheckFailure: Boolean = false,
    requiredContent: @Composable (RequiredUpdateScope) -> Unit,
    checkFailedContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    val state by controller.state.collectAsState()

    content()

    val required = state as? RequiredUpdateState.Required
    val blockedByCheckFailure = blockOnCheckFailure && state is RequiredUpdateState.CheckFailed
    if (required == null && !blockedByCheckFailure) return

    BackHandler(enabled = true) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            }
    ) {
        if (required != null) {
            requiredContent(rememberRequiredUpdateScope(controller, required))
        } else {
            checkFailedContent()
        }
    }
}

@Composable
private fun rememberRequiredUpdateScope(
    controller: RequiredUpdateController,
    state: RequiredUpdateState.Required
): RequiredUpdateScope = remember(controller, state) {
    object : RequiredUpdateScope {
        override val update = state.update
        override val stage = state.stage
        override fun download() = controller.download()
        override fun install() = controller.install()
        override fun retry() = controller.retry()
        override fun requestInstallPermission() = controller.requestInstallPermission()
    }
}
```

Nhánh `CheckFailed` cố tình **không** đi qua `RequiredUpdateScope`: lúc đó không có
`UpdateInfo` nào, và bịa một cái rỗng để lấp chỗ trống sẽ đẩy giá trị vô nghĩa vào UI của
host. Nó có slot riêng, không tham số.

- [ ] **Step 2: Build cả hai target**

Run: `./gradlew :version-kit-mp:build`
Expected: BUILD SUCCESSFUL.

Nếu `androidx.compose.ui.backhandler.BackHandler` không resolve được ở commonMain với Compose Multiplatform 1.9.0, làm fallback expect/actual thay vì thêm dependency:

Tạo `version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/BlockSystemBack.kt`:

```kotlin
package com.vtcode.pos.version.presentation.update

import androidx.compose.runtime.Composable

/** Nuốt nút back của hệ thống khi overlay bắt buộc đang hiện. */
@Composable
internal expect fun BlockSystemBack()
```

Tạo `version-kit-mp/src/androidMain/kotlin/com/vtcode/pos/version/presentation/update/BlockSystemBack.android.kt`:

```kotlin
package com.vtcode.pos.version.presentation.update

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun BlockSystemBack() {
    BackHandler(enabled = true) { }
}
```

và thêm vào `androidMain` dependencies trong `version-kit-mp/build.gradle.kts`:

```kotlin
                implementation(libs.androidx.activity.compose)
```

với `gradle/libs.versions.toml` thêm `androidx-activity-compose = { module = "androidx.activity:activity-compose", version = "1.9.3" }`.

Tạo `version-kit-mp/src/desktopMain/kotlin/com/vtcode/pos/version/presentation/update/BlockSystemBack.desktop.kt`:

```kotlin
package com.vtcode.pos.version.presentation.update

import androidx.compose.runtime.Composable

// Desktop không có nút back của hệ thống.
@Composable
internal actual fun BlockSystemBack() = Unit
```

rồi thay `BackHandler(enabled = true) { }` trong gate bằng `BlockSystemBack()` và bỏ import `androidx.compose.ui.backhandler.BackHandler`.

- [ ] **Step 3: Chạy toàn bộ test**

Run: `./gradlew :version-kit-mp:desktopTest`
Expected: PASS toàn bộ.

- [ ] **Step 4: Commit**

```bash
git add version-kit-mp/src/commonMain/kotlin/com/vtcode/pos/version/presentation/update/
git commit -m "feat: add RequiredUpdateGate with host-supplied blocking UI"
```

Nếu phải dùng fallback expect/actual thì `git add` thêm `version-kit-mp/src/androidMain/kotlin/com/vtcode/pos/version/presentation/update/`, `version-kit-mp/src/desktopMain/kotlin/com/vtcode/pos/version/presentation/update/`, `version-kit-mp/build.gradle.kts`, `gradle/libs.versions.toml`.

---

### Task 9: Bump version và cập nhật tài liệu

**Files:**
- Modify: `gradle.properties:8`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: toàn bộ public API từ Task 1–8.
- Produces: không có code mới.

- [ ] **Step 1: Bump version**

Trong `gradle.properties`, đổi dòng 8:

```
VERSION_NAME=0.3.0
```

- [ ] **Step 2: Cập nhật `CLAUDE.md`**

Trong mục "Public API surface (what hosts may touch)", thêm hai gạch đầu dòng sau dòng `VersionListSection(...)`:

```markdown
- `RequiredUpdateController` (headless) + `rememberRequiredUpdateController(...)`
- `RequiredUpdateGate(...)` + `RequiredUpdateScope`, `RequiredUpdateState`, `UpdateStage`
```

và trong dòng liệt kê domain types, thêm `UpdateInfo.isRequired`.

Thêm mục mới ngay trước "### Server contract":

```markdown
### Forced update (`is_required`)

Server trả `is_required` trên từng version; `VersionData.fromResponseItem` map thành
`UpdateInfo.isRequired` (mặc định `false`). `List<UpdateInfo>.requiredUpdate()` chọn bản
bắt buộc cao nhất **sau khi** `shouldPresentUpdate` đã lọc — nên một version bật cờ mà
thiếu file hoặc không mới hơn sẽ không khoá được app. Đó là chủ ý, đừng "sửa".

`RequiredUpdateController` (`presentation/update/`) là state holder headless cho luồng
này: `check()` → `download()` → `install()`, phát `RequiredUpdateState`. Nó nhận
`CoroutineScope` từ ngoài nên host dùng ViewModel riêng vẫn được. Constructor chính là
`internal` để test tiêm được `installOutcomes`; constructor public không có tham số đó.

`RequiredUpdateGate` phủ overlay nuốt pointer + chặn back lên trên `content()`, và
**không vẽ gì** bên trong — UI là của host qua slot `requiredContent`. Mặc định
`blockOnCheckFailure = false`: mất mạng thì cho vào app, vì khoá máy POS ngoài tiệm là
thảm hoạ vận hành.
```

Trong mục "Server contract", thêm `is_required` vào mô tả `VersionResponseItem`.

- [ ] **Step 3: Build đủ hai target lần cuối**

Run: `./gradlew :version-kit-mp:build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Kiểm tra publish local chạy được**

Run: `./gradlew publishToMavenLocal`
Expected: BUILD SUCCESSFUL, artifact `io.github.dungpham259dev:version-kit-mp:0.3.0` nằm trong `~/.m2/repository`.

- [ ] **Step 5: Commit**

```bash
git add gradle.properties CLAUDE.md
git commit -m "chore: release 0.3.0 with required update gate"
```
