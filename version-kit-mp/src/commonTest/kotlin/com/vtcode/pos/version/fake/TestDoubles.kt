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
    /** Writes junk instead of the ZIP magic, to fake a corrupt download. */
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
    var permissionLaunched: Boolean = true,
    var canInstall: Boolean = true
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

    override fun canInstallPackages(): Boolean = canInstall
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
