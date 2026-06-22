package com.vtcode.pos.version.presentation.install

import android.content.Context
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.presentation.ApkInstaller
import okio.Path
import java.io.File

actual class VersionInstaller(private val context: Context) {
    actual fun install(file: Path): Result<Unit, VersionError> {
        val javaFile = File(file.toString())
        if (!javaFile.exists()) return Result.Error(VersionError.FileNotFound(javaFile.absolutePath))
        val installer = ApkInstaller(context)
        if (!installer.canInstallPackages()) return Result.Error(VersionError.InstallPermissionRequired)
        return when (val r = installer.installApk(javaFile) {}) {
            is ApkInstaller.InstallResult.Success -> Result.Success(Unit)
            is ApkInstaller.InstallResult.PermissionRequired -> Result.Error(VersionError.InstallPermissionRequired)
            is ApkInstaller.InstallResult.Failure -> Result.Error(VersionError.InstallError(r.error))
        }
    }
}
