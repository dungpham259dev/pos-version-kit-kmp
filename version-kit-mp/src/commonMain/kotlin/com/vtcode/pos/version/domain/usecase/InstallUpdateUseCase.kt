package com.vtcode.pos.version.domain.usecase

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.presentation.install.IVersionInstaller
import okio.Path

class InstallUpdateUseCase(
    private val installer: IVersionInstaller
) {
    operator fun invoke(file: Path, onComplete: () -> Unit = {}): Result<Unit, VersionError> {
        val result = installer.install(file)
        if (result is Result.Success) onComplete()
        return result
    }

    /** Launch the platform flow to grant install permission. See [IVersionInstaller.requestInstallPermission]. */
    fun requestInstallPermission(): Boolean = installer.requestInstallPermission()
}
