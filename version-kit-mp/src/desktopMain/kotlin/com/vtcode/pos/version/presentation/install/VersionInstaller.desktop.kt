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
            processBuilder(installer).start()
            exitProcess(0)
            @Suppress("UNREACHABLE_CODE") Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(VersionError.InstallError(e.message ?: "Failed to launch installer"))
        }
    }

    private fun processBuilder(installer: File): ProcessBuilder {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        return when {
            isWindows && WindowsMsiInstall.isMsi(installer) -> {
                val script = WindowsMsiInstall.script(
                    msiPath = installer.absolutePath,
                    waitPids = appProcessIds(),
                    relaunchPath = System.getProperty(JPACKAGE_APP_PATH)?.takeIf { File(it).isFile },
                )
                // Pipes to an exited parent break on the first write; a file keeps the updater alive and debuggable.
                ProcessBuilder(WindowsMsiInstall.command(script))
                    .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(File("${installer.absolutePath}.updater.log")))
            }
            isWindows -> ProcessBuilder("cmd", "/c", "start", "", installer.absolutePath)
            else -> ProcessBuilder(installer.absolutePath)
        }
    }

    // jpackage's Windows launcher re-spawns itself and runs the JVM in the child; the parent holds the .exe too.
    private fun appProcessIds(): List<Long> {
        val current = ProcessHandle.current()
        val command = current.info().command().orElse(null)
        val launcherParent = current.parent()
            .filter { command != null && it.info().command().orElse(null) == command }
        return listOfNotNull(current.pid(), launcherParent.map { it.pid() }.orElse(null))
    }

    // Desktop installs do not require a runtime "unknown sources" permission.
    actual override fun requestInstallPermission(): Boolean = true

    actual override fun canInstallPackages(): Boolean = true
}
