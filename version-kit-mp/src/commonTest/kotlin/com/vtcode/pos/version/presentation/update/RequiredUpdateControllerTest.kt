package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.repository.IVersionRepository
import com.vtcode.pos.version.fake.FakeInstaller
import com.vtcode.pos.version.fake.FakeVersionRepository
import com.vtcode.pos.version.fake.testUpdate
import com.vtcode.pos.version.fake.testVersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
}
