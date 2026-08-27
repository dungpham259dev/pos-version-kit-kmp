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
