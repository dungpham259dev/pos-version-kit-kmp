package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.entity.downloadTargetPath
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.repository.IVersionRepository
import com.vtcode.pos.version.fake.FakeInstaller
import com.vtcode.pos.version.fake.FakeVersionRepository
import com.vtcode.pos.version.fake.testUpdate
import com.vtcode.pos.version.fake.testVersionChecker
import com.vtcode.pos.version.presentation.install.InstallOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Yields before returning, so a test can observe Checking before StateFlow conflates it away. */
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

/** Delays before the download reaches the delegate, giving a test a window to call [check] mid-flight. */
private class DelayedDownloadVersionRepository(
    private val delegate: FakeVersionRepository,
    private val delayMs: Long = 200
) : IVersionRepository {

    override suspend fun checkForUpdate(
        softwareCode: String,
        currentVersion: String
    ): Result<List<UpdateInfo>, VersionError> = delegate.checkForUpdate(softwareCode, currentVersion)

    override suspend fun downloadFileWithProgress(
        fileId: String,
        destinationDir: Path,
        fileSystem: FileSystem,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Path, VersionError> {
        delay(delayMs)
        return delegate.downloadFileWithProgress(fileId, destinationDir, fileSystem, onProgress)
    }
}

/**
 * Deliberately non-cooperative, reproducing the data-layer bug B1 fixed: the work runs inside
 * `withContext(NonCancellable)` and the `catch (e: Exception)` swallows the `CancellationException`
 * thrown on resume, so a cancelled check still reaches `_state.value` and `scheduleRecheck()`.
 * That is what lets the tests below exercise the controller's second line of defence.
 *
 * [delaysMs] and [resultsOverride] are indexed by call-entry order, not completion order, so a
 * stale cycle can be configured to finish after a fresh one; indexes past the end reuse the last.
 */
private class DelayedCheckVersionRepository(
    private val delegate: FakeVersionRepository,
    private val delaysMs: List<Long> = listOf(1_000L),
    private val resultsOverride: List<Result<List<UpdateInfo>, VersionError>?> = emptyList()
) : IVersionRepository {

    private var callIndex = 0

    override suspend fun checkForUpdate(
        softwareCode: String,
        currentVersion: String
    ): Result<List<UpdateInfo>, VersionError> {
        val index = callIndex++
        val delayMs = delaysMs.getOrElse(index) { delaysMs.last() }
        return try {
            withContext(NonCancellable) {
                delay(delayMs)
                val delegateResult = delegate.checkForUpdate(softwareCode, currentVersion)
                resultsOverride.getOrNull(index) ?: delegateResult
            }
        } catch (e: Exception) {
            Result.Error(VersionError.NetworkError(e.message ?: "cancelled"))
        }
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
        recheckIntervalMs: Long? = 15 * 60 * 1000L,
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
            recheckIntervalMs = recheckIntervalMs,
            installOutcomes = installOutcomes
        )
    }

    private suspend fun RequiredUpdateController.awaitState(
        predicate: (RequiredUpdateState) -> Boolean
    ): RequiredUpdateState = withTimeout(5_000) { state.first(predicate) }

    /** Download/install hop to `Dispatchers.Default`, which `runTest`'s virtual clock cannot advance. */
    private suspend fun RequiredUpdateController.awaitStateAfterDownloadOrInstall(
        predicate: (RequiredUpdateState) -> Boolean
    ): RequiredUpdateState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) { state.first(predicate) }
    }

    /** `install()` sets Installing before the installer actually runs, so the state alone proves nothing. */
    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                while (!predicate()) delay(1)
            }
        }
    }

    /** Negative assertions never finish early, so they get a short window instead of the 5000ms one. */
    private val STALE_OUTCOME_WINDOW_MS = 300L

    /** Returns whether [predicate] ever held, instead of throwing, since a timeout is the expected result. */
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

    /** The re-check `delay()` runs on the test scheduler, so `advanceTimeBy` triggers it without real sleeping. */
    @Test
    fun recheck_after_check_failure_picks_up_update_that_appeared() = runTest(UnconfinedTestDispatcher()) {
        val recheckInterval = 15 * 60 * 1000L
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository, recheckIntervalMs = recheckInterval)
        sut.awaitState { it is RequiredUpdateState.CheckFailed }
        assertEquals(1, repository.checkCount)

        repository.checkError = null
        repository.updates = listOf(testUpdate())

        advanceTimeBy(recheckInterval + 1)
        runCurrent()

        val state = sut.awaitState { it is RequiredUpdateState.Required }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals(2, repository.checkCount)
    }

    /**
     * The stale cycle (A, from `init()`) gets the LONGER delay so the fresh one (B) settles first
     * and A then tries to overwrite it — the ordering that actually causes harm. The two return
     * distinct [VersionError]s so the surviving state is asserted by error identity, not just type.
     * Without the guards, A's orphaned re-check timer would run alongside B's.
     */
    @Test
    fun check_call_while_check_in_flight_does_not_stack_recheck_timers() = runTest(UnconfinedTestDispatcher()) {
        val recheckInterval = 15 * 60 * 1000L
        val staleCheckDelayMs = 1_000L
        val freshCheckDelayMs = 200L
        val staleError = VersionError.NetworkError("stale-cycle-must-not-win")
        val freshError = VersionError.TimeoutError
        val repository = FakeVersionRepository(checkError = freshError)
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        val sut = RequiredUpdateController(
            scope = backgroundScope,
            versionChecker = testVersionChecker(
                DelayedCheckVersionRepository(
                    repository,
                    delaysMs = listOf(staleCheckDelayMs, freshCheckDelayMs),
                    resultsOverride = listOf(Result.Error(staleError), Result.Error(freshError))
                ),
                FakeInstaller()
            ),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem,
            recheckIntervalMs = recheckInterval
        )

        // cycle A is still parked in its delay, so this is a check() during an in-flight check
        assertEquals(0, repository.checkCount)
        sut.check()
        assertEquals(0, repository.checkCount)

        // advance only far enough for B to settle first
        advanceTimeBy(freshCheckDelayMs + 1)
        runCurrent()

        assertEquals(1, repository.checkCount)
        assertEquals(
            freshError,
            (sut.state.value as? RequiredUpdateState.CheckFailed)?.error,
            "Chu kỳ mới (B) phải chốt trạng thái trước"
        )

        // now let cancelled cycle A finish late; the isActive guard must stop it overwriting B
        advanceTimeBy(staleCheckDelayMs - freshCheckDelayMs)
        runCurrent()

        assertEquals(2, repository.checkCount, "Chu kỳ cũ (A) vẫn chạm tới repository do NonCancellable")
        assertEquals(
            freshError,
            (sut.state.value as? RequiredUpdateState.CheckFailed)?.error,
            "Chu kỳ cũ (A) hoàn tất trễ không được ghi đè lên trạng thái mà chu kỳ mới (B) đã chốt"
        )

        // every re-check also pays freshCheckDelayMs, so pad the window generously
        advanceTimeBy(recheckInterval * 2 + 5_000)
        runCurrent()

        assertEquals(
            4,
            repository.checkCount,
            "Chỉ đúng một timer hỏi lại được sống sót sau chu kỳ check() chồng lấn: " +
                "2 lần check ban đầu (lượt bị huỷ vẫn hoàn tất do NonCancellable + lượt mới) " +
                "+ đúng 2 lần hỏi lại trong khoảng thời gian đã trôi qua"
        )
    }

    /** Without the isActive guard a late stale cycle overwrites Required with CheckFailed, reopening the gate. */
    @Test
    fun stale_check_completing_after_Required_settles_cannot_reopen_gate() = runTest(UnconfinedTestDispatcher()) {
        val staleCheckDelayMs = 1_000L
        val freshCheckDelayMs = 200L
        val staleError = VersionError.NetworkError("stale-cycle-must-not-reopen-gate")
        val requiredUpdate = testUpdate(version = "9.9.9")
        val repository = FakeVersionRepository()
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        val sut = RequiredUpdateController(
            scope = backgroundScope,
            versionChecker = testVersionChecker(
                DelayedCheckVersionRepository(
                    repository,
                    delaysMs = listOf(staleCheckDelayMs, freshCheckDelayMs),
                    resultsOverride = listOf(
                        Result.Error(staleError),
                        Result.Success(listOf(requiredUpdate))
                    )
                ),
                FakeInstaller()
            ),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem,
            recheckIntervalMs = 15 * 60 * 1000L
        )

        // A is still parked; start an overlapping cycle B
        sut.check()

        // B finishes first and settles the gate on Required
        advanceTimeBy(freshCheckDelayMs + 1)
        runCurrent()

        val required = sut.state.value
        assertIs<RequiredUpdateState.Required>(required)
        assertEquals("9.9.9", required.update.version)
        assertEquals(UpdateStage.Ready, required.stage)

        // A finishes late and must not reopen the gate
        advanceTimeBy(staleCheckDelayMs - freshCheckDelayMs)
        runCurrent()

        assertEquals(2, repository.checkCount, "Cả hai chu kỳ đều phải chạm tới repository")
        val stateAfterStaleCompletes = sut.state.value
        assertIs<RequiredUpdateState.Required>(
            stateAfterStaleCompletes,
            "Chu kỳ cũ hoàn tất trễ không được mở lại gate: $stateAfterStaleCompletes"
        )
        assertEquals("9.9.9", stateAfterStaleCompletes.update.version)
        assertEquals(UpdateStage.Ready, stateAfterStaleCompletes.stage)
    }

    /** [RequiredUpdateController.close] must stop the recurring re-check, not just the current cycle. */
    @Test
    fun close_stops_recheck_loop() = runTest(UnconfinedTestDispatcher()) {
        val recheckInterval = 15 * 60 * 1000L
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository, recheckIntervalMs = recheckInterval)
        sut.awaitState { it is RequiredUpdateState.CheckFailed }
        assertEquals(1, repository.checkCount)

        sut.close()

        advanceTimeBy(recheckInterval * 4)
        runCurrent()

        assertEquals(1, repository.checkCount, "close() phải huỷ recheckJob đang chờ, không cho nó tiếp tục hỏi lại")
    }

    @Test
    fun recheckIntervalMs_null_performs_exactly_one_check() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository, recheckIntervalMs = null)
        sut.awaitState { it is RequiredUpdateState.CheckFailed }
        assertEquals(1, repository.checkCount)

        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()

        assertEquals(1, repository.checkCount)
        assertIs<RequiredUpdateState.CheckFailed>(sut.state.value)
    }

    @Test
    fun no_recheck_fires_while_state_is_Required() = runTest(UnconfinedTestDispatcher()) {
        val recheckInterval = 15 * 60 * 1000L
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val sut = controller(backgroundScope, repository, recheckIntervalMs = recheckInterval)
        sut.awaitState { it is RequiredUpdateState.Required }
        assertEquals(1, repository.checkCount)

        advanceTimeBy(recheckInterval * 4)
        runCurrent()

        assertEquals(1, repository.checkCount)
        assertIs<RequiredUpdateState.Required>(sut.state.value)
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
    fun second_install_call_while_installing_is_ignored() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller()
        val sut = controller(backgroundScope, repository, installer, autoInstall = false)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Downloaded
        }

        sut.install()
        sut.install()

        val installedTwice = awaitConditionOrTimeout { installer.installed.size > 1 }
        assertTrue(!installedTwice, "Lệnh install() thứ hai trong lúc đang cài không được thực thi")
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun check_mid_download_abandons_orphaned_download() = runTest(UnconfinedTestDispatcher()) {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller()
        val sut = RequiredUpdateController(
            scope = backgroundScope,
            versionChecker = testVersionChecker(DelayedDownloadVersionRepository(repository), installer),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem
        )
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()
        sut.awaitState {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Downloading
        }

        sut.check()

        assertIs<RequiredUpdateState.Required>(
            sut.awaitState { it is RequiredUpdateState.Required && it.stage == UpdateStage.Ready }
        )

        val becameInstalling = awaitConditionOrTimeout(timeoutMs = 500) {
            val state = sut.state.value
            state is RequiredUpdateState.Required && state.stage is UpdateStage.Installing
        }
        assertTrue(!becameInstalling, "check() giữa chừng tải không được để lượt tải mồ côi tự động cài đặt")
        assertTrue(installer.installed.isEmpty(), "Installer không được gọi từ lượt tải đã bị check() huỷ bỏ")
        assertEquals(0, repository.downloadCount, "Lượt tải mồ côi phải bị huỷ trước khi chạm tới repository")
    }

    @Test
    fun existing_valid_download_resumes_at_Downloaded_without_downloading_again() = runTest(UnconfinedTestDispatcher()) {
        val update = testUpdate(version = "2.0.0", fileId = "file-1")
        val repository = FakeVersionRepository(updates = listOf(update))
        val installer = FakeInstaller()
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        val alreadyThere = downloadTargetPath("file-1", cacheDir)
        fileSystem.write(alreadyThere) { write(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }

        val sut = controller(backgroundScope, repository, installer, fileSystem)

        val state = sut.awaitState { it is RequiredUpdateState.Required }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals(UpdateStage.Downloaded(alreadyThere), state.stage)
        assertEquals(0, repository.downloadCount)
        assertTrue(installer.installed.isEmpty(), "a cold start must not fire the installer on its own")

        sut.install()
        awaitCondition { installer.installed.isNotEmpty() }
        assertEquals(listOf(alreadyThere), installer.installed)
        assertEquals(0, repository.downloadCount)
    }

    @Test
    fun corrupt_cached_file_is_ignored_and_download_still_runs() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate(fileId = "file-1")))
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        fileSystem.write(downloadTargetPath("file-1", cacheDir)) { write("<html>nope</html>".encodeToByteArray()) }

        val sut = controller(backgroundScope, repository, FakeInstaller(), fileSystem)

        val state = sut.awaitState { it is RequiredUpdateState.Required }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals(UpdateStage.Ready, state.stage)
    }

    @Test
    fun returning_to_foreground_while_Installing_frees_a_stranded_install() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate(fileId = "file-1")))
        val installer = FakeInstaller()
        val fileSystem = FakeFileSystem()
        val sut = controller(backgroundScope, repository, installer, fileSystem)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }

        // the platform never reports a terminal outcome; coming back must not leave the gate stuck
        sut.onAppForegrounded()

        val state = sut.state.value
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals(UpdateStage.Downloaded(downloadTargetPath("file-1", cacheDir)), state.stage)
    }

    @Test
    fun returning_to_foreground_outside_Installing_changes_nothing() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val sut = controller(backgroundScope, repository)
        val before = sut.awaitState { it is RequiredUpdateState.Required }

        sut.onAppForegrounded()

        assertEquals(before, sut.state.value)
    }

    @Test
    fun canInstallPackages_reflects_the_platform_installer() = runTest(UnconfinedTestDispatcher()) {
        val installer = FakeInstaller(canInstall = false)
        val sut = controller(backgroundScope, FakeVersionRepository(), installer)

        assertFalse(sut.canInstallPackages())

        installer.canInstall = true
        assertTrue(sut.canInstallPackages())
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
        awaitCondition { installer.installed.size == 2 }
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

    /** A late outcome from an earlier install must be ignored once the stage is no longer Installing. */
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

        // give the late outcome a real chance to reach the collector before asserting
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
