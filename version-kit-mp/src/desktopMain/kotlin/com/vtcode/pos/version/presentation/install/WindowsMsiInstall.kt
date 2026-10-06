package com.vtcode.pos.version.presentation.install

import java.io.File
import java.util.Base64

/**
 * Runs an MSI upgrade from a detached PowerShell so the app can exit first and come back afterwards.
 * The script goes in UTF-16 via `-EncodedCommand`, which avoids cmd's codepage and quoting traps
 * on paths like `C:\Users\Dũng Phạm\...`.
 */
internal object WindowsMsiInstall {

    private const val APP_EXIT_TIMEOUT_SECONDS = 60
    private val OLE_COMPOUND_FILE_SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    // By content too, so a file that lost its extension still goes to msiexec instead of an "Open with" prompt.
    fun isMsi(file: File): Boolean {
        if (file.name.endsWith(".msi", ignoreCase = true)) return true
        val header = ByteArray(OLE_COMPOUND_FILE_SIGNATURE.size)
        val read = runCatching { file.inputStream().use { it.readNBytes(header, 0, header.size) } }.getOrDefault(0)
        return read == header.size && header.contentEquals(OLE_COMPOUND_FILE_SIGNATURE)
    }

    fun script(msiPath: String, waitPids: List<Long>, relaunchPath: String?): String {
        val msiArgs = "/i \"$msiPath\" /passive /norestart /l*v \"$msiPath.log\""
        return buildString {
            appendLine("Wait-Process -Id ${waitPids.joinToString(",")} -Timeout $APP_EXIT_TIMEOUT_SECONDS -ErrorAction SilentlyContinue")
            appendLine("Start-Process -FilePath 'msiexec.exe' -ArgumentList ${quote(msiArgs)} -Wait")
            if (relaunchPath != null) appendLine("Start-Process -FilePath ${quote(relaunchPath)}")
        }
    }

    fun command(script: String): List<String> = listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)),
    )

    private fun quote(value: String): String = "'" + value.replace("'", "''") + "'"
}
