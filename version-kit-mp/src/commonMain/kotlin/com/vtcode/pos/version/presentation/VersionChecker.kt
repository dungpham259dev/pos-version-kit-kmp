package com.vtcode.pos.version.presentation

import com.vtcode.pos.version.di.VersionKitConfig
import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.usecase.CheckUpdateUseCase
import com.vtcode.pos.version.domain.usecase.DownloadProgressTracker
import com.vtcode.pos.version.domain.usecase.DownloadUpdateUseCase
import com.vtcode.pos.version.domain.usecase.InstallUpdateUseCase
import okio.FileSystem
import okio.Path

class VersionChecker(
    private val checkUpdateUseCase: CheckUpdateUseCase,
    private val downloadUpdateUseCase: DownloadUpdateUseCase,
    private val installUpdateUseCase: InstallUpdateUseCase,
    private val config: VersionKitConfig
) {
    val defaultSoftwareCode: String? get() = config.softwareCode

    suspend fun check(softwareCode: String? = null, currentVersion: String):
        Result<List<UpdateInfo>, VersionError> {
        val code = softwareCode ?: config.softwareCode
            ?: error("softwareCode not provided and VersionKitConfig.softwareCode is null")
        return checkUpdateUseCase(code, currentVersion)
    }

    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        destinationDir: Path,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        tracker: DownloadProgressTracker
    ): Result<Path, VersionError> =
        downloadUpdateUseCase(updateInfo, destinationDir, fileSystem, tracker)

    fun installUpdate(file: Path, onComplete: () -> Unit = {}): Result<Unit, VersionError> =
        installUpdateUseCase(file, onComplete)
}
