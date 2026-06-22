package com.vtcode.pos.version.domain.usecase

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.repository.IVersionRepository
import com.vtcode.pos.version.domain.service.IVersionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Use case: Download update directly using coroutines.
 *
 * This use case creates a new [DownloadProgressTracker] for each download operation,
 * ensuring proper isolation between concurrent downloads.
 *
 * Usage:
 * ```
 * val tracker = DownloadProgressTracker()
 * val result = downloadUpdateUseCase(updateInfo, cacheDir, fileSystem, tracker)
 *
 * // Observe progress
 * tracker.progress.collect { progress ->
 *     // Update UI
 * }
 * ```
 */
class DownloadUpdateUseCase(
    private val repository: IVersionRepository,
    private val versionService: IVersionService
) {

    /**
     * Download update file directly with progress tracking.
     *
     * @param updateInfo The update information
     * @param destinationDir Directory to save file
     * @param fileSystem File system used to write/validate the download
     * @param tracker Progress tracker for this download operation
     * @return Result containing the downloaded [Path], or VersionError
     */
    suspend operator fun invoke(
        updateInfo: UpdateInfo,
        destinationDir: Path,
        fileSystem: FileSystem,
        tracker: DownloadProgressTracker
    ): Result<Path, VersionError> = withContext(Dispatchers.Default) {
        tracker.updateProgress(0, 0, 0)

        val result = try {
            repository.downloadFileWithProgress(
                fileId = updateInfo.fileId,
                destinationDir = destinationDir,
                fileSystem = fileSystem,
                onProgress = { percentage, total, downloaded ->
                    tracker.updateProgress(percentage, total, downloaded)
                }
            )
        } catch (e: CancellationException) {
            tracker.cancel()
            throw e
        }

        when (result) {
            is Result.Success -> {
                val file = result.value
                // Guard against truncated/corrupt downloads (e.g. an HTTP 200 error
                // page saved as .apk) before handing the file to the installer.
                if (!versionService.isDownloadComplete(updateInfo, file, fileSystem)) {
                    if (fileSystem.exists(file)) fileSystem.delete(file)
                    val error = VersionError.UnknownError(
                        "Downloaded file is invalid or incomplete"
                    )
                    tracker.fail(error.message)
                    Result.Error(error)
                } else {
                    tracker.complete(file)
                    Result.Success(file)
                }
            }
            is Result.Error -> {
                tracker.fail(result.error.message)
                result
            }
        }
    }
}
