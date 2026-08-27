package com.vtcode.pos.version.presentation.install

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path

/** Platform installer: Android = PackageInstaller, Desktop = launch .msi/.exe then exit. */
expect class VersionInstaller {
    fun install(file: Path): Result<Unit, VersionError>

    /**
     * Ask the platform to let the user grant install permission (Android opens the
     * "install unknown apps" settings screen). Returns `true` if a request flow was
     * launched. On platforms that need no such permission this is a no-op returning `true`.
     */
    fun requestInstallPermission(): Boolean
}
