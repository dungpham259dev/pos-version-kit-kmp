package com.vtcode.pos.version.presentation.install

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path

/** Split out of `expect class VersionInstaller` so commonTest can fake the install path. */
interface IVersionInstaller {
    fun install(file: Path): Result<Unit, VersionError>

    /** Returns true if a request flow was launched; platforms needing no permission return true. */
    fun requestInstallPermission(): Boolean
}
