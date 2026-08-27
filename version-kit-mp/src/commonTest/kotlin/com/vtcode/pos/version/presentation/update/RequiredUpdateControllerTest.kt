package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.repository.IVersionRepository
import com.vtcode.pos.version.fake.FakeInstaller
import com.vtcode.pos.version.fake.FakeVersionRepository
import com.vtcode.pos.version.fake.testUpdate
import com.vtcode.pos.version.fake.testVersionChecker
import com.vtcode.pos.version.presentation.install.InstallOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Bọc [FakeVersionRepository] và chèn một điểm suspend thật (`yield()`) trước khi trả kết
 * quả `checkForUpdate`, để test có thể quan sát chắc chắn trạng thái [RequiredUpdateState.Checking]
 * trước khi nó bị `StateFlow` conflate mất bởi trạng thái kết thúc.
 */
private class YieldingVersionRepository(
    private val delegate: FakeVersionRepository
) : IVersionRepository {

    override suspend fun checkForUpdate(
        softwareCode: String,
        currentVersion: String
    ): Result<List<UpdateInfo>, VersionError> {
        yield()
        return delegate.checkForUpdate(softwareCode, currentVersion)
    }

    override suspend fun downloadFileWithProgress(
        fileId: String,
        destinationDir: Path,
        fileSystem: FileSystem,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Path, VersionError> = delegate.downloadFileWithProgress(fileId, destinationDir, fileSystem, onProgress)
}

class RequiredUpdateControllerTest {

    private val cacheDir = "/cache".toPath()

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

    private suspend fun RequiredUpdateController.awaitState(
        predicate: (RequiredUpdateState) -> Boolean
    ): RequiredUpdateState = withTimeout(5_000) { state.first(predicate) }

    /**
     * `download()`/`install()` chạy `DownloadUpdateUseCase`/`installUpdate` bên trong
     * `withContext(Dispatchers.Default)` — công việc thật, không nằm trên `TestDispatcher` của
     * `runTest`. Timeout ảo của [awaitState] có thể fire ngay lập tức trước khi coroutine đó kịp
     * trả kết quả, nên các test download/install chờ bằng thời gian thật thay vì thời gian ảo.
     */
    private suspend fun RequiredUpdateController.awaitStateAfterDownloadOrInstall(
        predicate: (RequiredUpdateState) -> Boolean
    ): RequiredUpdateState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) { state.first(predicate) }
    }

    /**
     * `install()` chuyển trạng thái sang [UpdateStage.Installing] *trước khi* coroutine thật
     * gọi `installer.install(file)` trên [Dispatchers.Default], nên riêng việc thấy trạng thái
     * đó không đảm bảo installer đã được gọi. Poll bằng thời gian thật cho tới khi side effect
     * thật sự xảy ra.
     */
    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                while (!predicate()) delay(1)
            }
        }
    }

    /**
     * Cửa sổ chờ cho khẳng định phủ định ("trạng thái này KHÔNG đổi"). Khác với các wait dương
     * tính ở trên (chờ tới khi điều kiện đúng, kết thúc ngay khi thoả), một wait âm tính không
     * bao giờ tự kết thúc sớm nếu điều kiện không xảy ra — nên nó phải dùng cửa sổ ngắn, có tên
     * riêng, thay vì mượn timeout 5000ms vốn dành cho việc chờ một kết quả sẽ tới.
     */
    private val STALE_OUTCOME_WINDOW_MS = 300L

    /**
     * Poll [predicate] bằng thời gian thật trong tối đa [timeoutMs] và trả về liệu nó có bao giờ
     * đúng hay không, thay vì ném lỗi khi hết giờ — phù hợp cho khẳng định phủ định nơi hết giờ
     * chính là kết quả mong đợi.
     */
    private suspend fun awaitConditionOrTimeout(
        timeoutMs: Long = STALE_OUTCOME_WINDOW_MS,
        predicate: () -> Boolean
    ): Boolean = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeoutOrNull(timeoutMs) {
            while (!predicate()) delay(1)
        } != null
    }

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

    @Test
    fun check_emits_Checking_before_terminal_state() = runTest(UnconfinedTestDispatcher()) {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = RequiredUpdateController(
            scope = backgroundScope,
            versionChecker = testVersionChecker(YieldingVersionRepository(repository), FakeInstaller()),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem
        )
        sut.awaitState { it is RequiredUpdateState.CheckFailed }

        repository.checkError = null
        repository.updates = listOf(testUpdate())

        val recordedStates = mutableListOf<RequiredUpdateState>()
        backgroundScope.launch {
            sut.state.collect { recordedStates.add(it) }
        }

        sut.retry()
        withTimeout(5_000) {
            while (recordedStates.none { it is RequiredUpdateState.Required }) {
                yield()
            }
        }

        val retryIndex = recordedStates.indexOfFirst { it is RequiredUpdateState.CheckFailed }
        val checkingIndex = recordedStates.indexOfLast { it is RequiredUpdateState.Checking }
        val requiredIndex = recordedStates.indexOfFirst { it is RequiredUpdateState.Required }

        assertTrue(checkingIndex > retryIndex, "Checking phải xuất hiện sau CheckFailed: $recordedStates")
        assertTrue(checkingIndex < requiredIndex, "Checking phải xuất hiện trước trạng thái Required: $recordedStates")
    }

    @Test
    fun download_reaches_Downloaded_and_auto_installs() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller()
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val installing = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }
        assertIs<RequiredUpdateState.Required>(installing)
        awaitCondition { installer.installed.isNotEmpty() }
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

        val state = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Downloaded
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertTrue(installer.installed.isEmpty())

        sut.install()
        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }
        awaitCondition { installer.installed.isNotEmpty() }
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

        val state = sut.awaitStateAfterDownloadOrInstall {
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

        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
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
        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }

        repository.downloadError = null
        sut.retry()

        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }
        assertEquals(2, repository.downloadCount)
    }

    @Test
    fun second_download_call_while_downloading_is_ignored() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val sut = controller(backgroundScope, repository)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()
        sut.download()

        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }
        assertEquals(1, repository.downloadCount)
    }

    @Test
    fun install_permission_error_yields_InstallPermissionRequired() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallPermissionRequired))
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        sut.awaitStateAfterDownloadOrInstall {
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
        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage == UpdateStage.InstallPermissionRequired
        }

        installer.result = Result.Success(Unit)
        sut.retry()

        sut.awaitStateAfterDownloadOrInstall { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }
        assertEquals(2, installer.installed.size)
    }

    @Test
    fun install_error_yields_Failed_stage() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallError("signature mismatch")))
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val state = sut.awaitStateAfterDownloadOrInstall {
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
        sut.awaitStateAfterDownloadOrInstall { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }

        outcomes.emit(InstallOutcome.Failure("Người dùng đã huỷ"))

        val state = sut.awaitStateAfterDownloadOrInstall {
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
        sut.awaitStateAfterDownloadOrInstall { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }

        outcomes.emit(InstallOutcome.Failure(null))

        val state = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals("Cài đặt thất bại", (state.stage as UpdateStage.Failed).message)
    }

    /**
     * `InstallOutcome` không mang token liên kết với một lần gọi `install()` cụ thể, nên một
     * outcome trễ từ lượt cài trước có thể đến sau khi controller đã chuyển sang chu kỳ khác.
     * Test này đưa controller tới [UpdateStage.Failed] (không phải qua platform outcome, mà
     * qua lỗi cài đặt cục bộ) rồi bắn một `InstallOutcome.Failure` trễ; guard trong
     * `observeInstallOutcomes()` phải bỏ qua nó vì stage hiện tại không phải [UpdateStage.Installing].
     */
    @Test
    fun stale_install_failure_outside_installing_stage_is_ignored() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallError("signature mismatch")))
        val outcomes = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 1)
        val sut = controller(backgroundScope, repository, installer, installOutcomes = outcomes)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()
        val failedState = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(failedState)
        val originalMessage = (failedState.stage as UpdateStage.Failed).message

        outcomes.emit(InstallOutcome.Failure("Outcome trễ từ lượt cài trước"))

        // Chờ thật một khoảng thời gian để outcome trễ có cơ hội tới collector trước khi assert.
        val staleOverwrote = awaitConditionOrTimeout {
            val state = sut.state.value
            state is RequiredUpdateState.Required &&
                (state.stage as? UpdateStage.Failed)?.message == "Outcome trễ từ lượt cài trước"
        }

        assertTrue(!staleOverwrote, "Outcome trễ ngoài Installing không được ghi đè stage")
        val current = sut.state.value
        assertIs<RequiredUpdateState.Required>(current)
        assertEquals(UpdateStage.Failed(originalMessage), current.stage)
    }
}
