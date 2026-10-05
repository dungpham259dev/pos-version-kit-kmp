package com.vtcode.pos.version.presentation.install

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path
import java.io.File
import kotlin.system.exitProcess

// Set by the jpackage launcher; absent when running from an IDE or `./gradlew run`.
private const val JPACKAGE_APP_PATH = "jpackage.app-path"

actual class VersionInstaller : IVersionInstaller {
    actual override fun install(file: Path): Result<Unit, VersionError> {
        val installer = File(file.toString())
        if (!installer.exists()) return Result.Error(VersionError.FileNotFound(installer.absolutePath))
        return try {
            ProcessBuilder(launchCommand(installer)).start()
            exitProcess(0)
            @Suppress("UNREACHABLE_CODE") Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(VersionError.InstallError(e.message ?: "Failed to launch installer"))
        }
    }

    private fun launchCommand(installer: File): List<String> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        return when {
            isWindows && installer.name.endsWith(".msi", ignoreCase = true) -> {
                val script = WindowsMsiInstall.script(
                    msiPath = installer.absolutePath,
                    parentPid = ProcessHandle.current().pid(),
                    relaunchPath = System.getProperty(JPACKAGE_APP_PATH)?.takeIf { File(it).isFile },
                )
                WindowsMsiInstall.command(script)
            }
            isWindows -> listOf("cmd", "/c", "start", "", installer.absolutePath)
            else -> listOf(installer.absolutePath)
        }
    }

    // Desktop installs do not require a runtime "unknown sources" permission.
    actual override fun requestInstallPermission(): Boolean = true

    actual override fun canInstallPackages(): Boolean = true
}
