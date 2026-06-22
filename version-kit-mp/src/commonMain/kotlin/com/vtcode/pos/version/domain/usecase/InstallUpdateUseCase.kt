package com.vtcode.pos.version.domain.usecase

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.presentation.install.VersionInstaller
import okio.Path

class InstallUpdateUseCase(
    private val installer: VersionInstaller
) {
    operator fun invoke(file: Path, onComplete: () -> Unit = {}): Result<Unit, VersionError> {
        val result = installer.install(file)
        if (result is Result.Success) onComplete()
        return result
    }
}
