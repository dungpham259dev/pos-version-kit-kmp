package com.vtcode.pos.version.data.repository

import com.vtcode.pos.version.data.datasource.IVersionRemoteDataSource
import com.vtcode.pos.version.data.model.toUpdateInfo
import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.repository.IVersionRepository
import okio.FileSystem
import okio.Path

internal class VersionRepository(
    private val remote: IVersionRemoteDataSource
) : IVersionRepository {
    override suspend fun checkForUpdate(softwareCode: String, currentVersion: String):
        Result<List<UpdateInfo>, VersionError> =
        when (val r = remote.checkForUpdate(softwareCode, currentVersion)) {
            is Result.Success -> Result.Success(r.value.map { it.toUpdateInfo() })
            is Result.Error -> r
        }

    override suspend fun downloadFileWithProgress(
        fileId: String, destinationDir: Path, fileSystem: FileSystem,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Path, VersionError> =
        remote.downloadFileWithProgress(fileId, destinationDir, fileSystem, onProgress)
}
