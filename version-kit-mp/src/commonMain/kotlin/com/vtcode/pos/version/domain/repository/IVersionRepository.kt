package com.vtcode.pos.version.domain.repository

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.FileSystem
import okio.Path

interface IVersionRepository {
    suspend fun checkForUpdate(softwareCode: String, currentVersion: String): Result<List<UpdateInfo>, VersionError>
    suspend fun downloadFileWithProgress(
        fileId: String, destinationDir: Path, fileSystem: FileSystem,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Path, VersionError>
}
