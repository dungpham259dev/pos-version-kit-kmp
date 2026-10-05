package com.vtcode.pos.version.presentation.install

import java.util.Base64

/**
 * Runs an MSI upgrade from a detached PowerShell so the app can exit first and come back afterwards.
 * The script goes in UTF-16 via `-EncodedCommand`, which avoids cmd's codepage and quoting traps
 * on paths like `C:\Users\Dũng Phạm\...`.
 */
internal object WindowsMsiInstall {

    private const val PARENT_EXIT_TIMEOUT_SECONDS = 60

    fun script(msiPath: String, parentPid: Long, relaunchPath: String?): String {
        val msiArgs = "/i \"$msiPath\" /passive /norestart /l*v \"$msiPath.log\""
        return buildString {
            appendLine("Wait-Process -Id $parentPid -Timeout $PARENT_EXIT_TIMEOUT_SECONDS -ErrorAction SilentlyContinue")
            appendLine("Start-Process -FilePath 'msiexec.exe' -ArgumentList ${quote(msiArgs)} -Wait")
            if (relaunchPath != null) appendLine("Start-Process -FilePath ${quote(relaunchPath)}")
        }
    }

    fun command(script: String): List<String> = listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden",
        "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
    )

    private fun quote(value: String): String = "'" + value.replace("'", "''") + "'"
}
