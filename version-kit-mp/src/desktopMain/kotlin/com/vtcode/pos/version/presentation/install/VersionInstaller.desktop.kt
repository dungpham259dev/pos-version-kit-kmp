package com.vtcode.pos.version.presentation.install

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path
import java.io.File
import kotlin.system.exitProcess

actual class VersionInstaller {
    actual fun install(file: Path): Result<Unit, VersionError> {
        val installer = File(file.toString())
        if (!installer.exists()) return Result.Error(VersionError.FileNotFound(installer.absolutePath))
        return try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                installer.name.endsWith(".msi", true) ->
                    listOf("msiexec", "/i", installer.absolutePath)
                os.contains("win") ->
                    listOf("cmd", "/c", "start", "", installer.absolutePath)
                else -> listOf(installer.absolutePath)
            }
            ProcessBuilder(cmd).start()
            exitProcess(0)
            @Suppress("UNREACHABLE_CODE") Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(VersionError.InstallError(e.message ?: "Failed to launch installer"))
        }
    }
}
