package com.vtcode.pos.version.presentation.components

import com.vtcode.pos.version.fake.FakeInstaller
import com.vtcode.pos.version.fake.FakeVersionRepository
import com.vtcode.pos.version.fake.testUpdate
import com.vtcode.pos.version.fake.testVersionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VersionListControllerTest {

    // Losing file_path drops the ".msi" extension, and Windows then asks the user which app opens the file.
    @Test
    fun downloading_a_listed_item_keeps_the_server_file_path() = runTest(UnconfinedTestDispatcher()) {
        val update = testUpdate(version = "2.0.0", isRequired = false)
            .copy(filePath = "upload/ipos-desktop/ipos-desktop-2.0.0.msi")
        val repository = FakeVersionRepository(updates = listOf(update))
        val sut = VersionListController(
            scope = backgroundScope,
            versionChecker = testVersionChecker(repository, FakeInstaller()),
            softwareCode = "ipos",
            currentVersion = "1.0.0",
            cacheDir = "/cache".toPath(),
            fileSystem = FakeFileSystem(),
            callbacks = VersionListCallbacks(onInstallRequested = { true })
        )

        val listed = withTimeout(5_000) {
            sut.combinedState.first { it.uiState is VersionListUiState.HasUpdates }.uiState
        }
        assertIs<VersionListUiState.HasUpdates>(listed)

        sut.downloadItem(listed.items.single())

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) { while (repository.downloadCount == 0) delay(1) }
        }
        assertEquals("upload/ipos-desktop/ipos-desktop-2.0.0.msi", repository.lastServerFilePath)
    }
}
