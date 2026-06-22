package com.vtcode.pos.version.data.datasource

import com.vtcode.pos.version.data.model.VersionData
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.FileSystem
import okio.Path

interface IVersionRemoteDataSource {
    suspend fun checkForUpdate(softwareCode: String, currentVersion: String): Result<List<VersionData>, VersionError>
    suspend fun downloadFileWithProgress(
        fileId: String,
        destinationDir: Path,
        fileSystem: FileSystem,
        onProgress: (percentage: Int, totalBytes: Long, downloadedBytes: Long) -> Unit
    ): Result<Path, VersionError>
}
