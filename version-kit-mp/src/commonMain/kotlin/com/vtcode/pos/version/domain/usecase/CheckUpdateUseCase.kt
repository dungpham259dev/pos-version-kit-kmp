package com.vtcode.pos.version.domain.usecase

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.repository.IVersionRepository
import com.vtcode.pos.version.domain.service.IVersionService

/**
 * Use case: Check for available updates.
 * Coordinates between repository and service to determine which updates should be presented.
 */
class CheckUpdateUseCase(
    private val repository: IVersionRepository,
    private val versionService: IVersionService
) {

    /**
     * Check for updates from remote server.
     *
     * @param softwareCode The software identifier (e.g., "ipos", "kpos")
     * @param currentVersion The current app version
     * @return Result containing List of UpdateInfo that should be presented,
     *         empty list if no updates needed, or VersionError
     */
    suspend operator fun invoke(
        softwareCode: String,
        currentVersion: String
    ): Result<List<UpdateInfo>, VersionError> {
        return when (val result = repository.checkForUpdate(softwareCode, currentVersion)) {
            is Result.Success -> {
                val filteredList = result.value.filter { updateInfo ->
                    versionService.shouldPresentUpdate(updateInfo, currentVersion)
                }
                Result.Success(filteredList)
            }
            is Result.Error -> result
        }
    }
}
