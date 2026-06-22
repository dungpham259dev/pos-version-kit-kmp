package com.vtcode.pos.version.presentation.install

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path

/** Platform installer: Android = PackageInstaller, Desktop = launch .msi/.exe then exit. */
expect class VersionInstaller {
    fun install(file: Path): Result<Unit, VersionError>
}
