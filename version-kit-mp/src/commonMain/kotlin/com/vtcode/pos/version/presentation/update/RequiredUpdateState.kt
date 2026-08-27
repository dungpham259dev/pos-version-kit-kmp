package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path

sealed interface RequiredUpdateState {
    data object Checking : RequiredUpdateState

    data object NotRequired : RequiredUpdateState

    /** The gate lets the app run on this unless the host supplies blocking UI. */
    data class CheckFailed(val error: VersionError) : RequiredUpdateState

    data class Required(val update: UpdateInfo, val stage: UpdateStage) : RequiredUpdateState
}

sealed interface UpdateStage {
    data object Ready : UpdateStage

    data class Downloading(
        val percentage: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateStage

    data class Downloaded(val file: Path) : UpdateStage

    /** Installer called; the real result arrives asynchronously from the platform. */
    data object Installing : UpdateStage

    data object InstallPermissionRequired : UpdateStage

    /** Always recoverable via [RequiredUpdateController.retry]. */
    data class Failed(val message: String) : UpdateStage
}
