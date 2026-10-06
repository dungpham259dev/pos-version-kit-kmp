package com.vtcode.pos.version.presentation.install

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsMsiInstallTest {

    private val msi = """C:\Users\Dũng Phạm\AppData\Local\ipos\cache\update_abc.msi"""
    private val app = """C:\Program Files\iPOS\iPOS.exe"""

    // jpackage's Windows launcher re-spawns itself; the JVM runs in the child, so both must be gone.
    @Test fun waits_for_the_running_app_to_exit_before_touching_its_files() {
        val script = WindowsMsiInstall.script(msi, waitPids = listOf(4242L, 4241L), relaunchPath = app)
        val wait = script.indexOf("Wait-Process -Id 4242,4241")
        val install = script.indexOf("msiexec.exe")
        assertTrue(wait in 0 until install, script)
    }

    @Test fun installs_unattended_with_a_verbose_log_next_to_the_installer() {
        val script = WindowsMsiInstall.script(msi, waitPids = listOf(1L), relaunchPath = null)
        assertTrue("/i \"$msi\"" in script, script)
        assertTrue("/passive" in script, script)
        assertTrue("/norestart" in script, script)
        assertTrue("/l*v \"$msi.log\"" in script, script)
        assertTrue("-Wait" in script, script)
    }

    // A cancelled UAC prompt must not leave the POS with no app running at all.
    @Test fun relaunches_the_app_after_the_installer_exits_whatever_the_outcome() {
        val script = WindowsMsiInstall.script(msi, waitPids = listOf(1L), relaunchPath = app)
        val install = script.indexOf("msiexec.exe")
        val relaunch = script.indexOf("Start-Process -FilePath '$app'")
        assertTrue(relaunch > install, script)
        assertFalse("ExitCode" in script, script)
    }

    @Test fun no_relaunch_when_not_running_from_a_packaged_launcher() {
        val script = WindowsMsiInstall.script(msi, waitPids = listOf(1L), relaunchPath = null)
        assertEquals(1, Regex("Start-Process").findAll(script).count(), script)
    }

    @Test fun single_quotes_in_paths_are_escaped_for_powershell() {
        val script = WindowsMsiInstall.script("""C:\O'Neil\u.msi""", waitPids = listOf(1L), relaunchPath = """C:\O'Neil\app.exe""")
        assertTrue("""C:\O''Neil\u.msi""" in script, script)
        assertTrue("""C:\O''Neil\app.exe""" in script, script)
    }

    @Test fun command_carries_the_script_utf16_encoded_so_non_ascii_paths_survive() {
        val script = WindowsMsiInstall.script(msi, waitPids = listOf(1L), relaunchPath = app)
        val command = WindowsMsiInstall.command(script)
        assertEquals("powershell.exe", command.first())
        assertTrue("-NonInteractive" in command)
        // Java already starts it with CREATE_NO_WINDOW; a hidden-window flag only looks like malware to AV.
        assertFalse("-WindowStyle" in command)
        val encoded = command[command.indexOf("-EncodedCommand") + 1]
        assertEquals(script, String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE))
    }

    @Test fun recognises_an_msi_by_content_even_without_the_extension() {
        val file = File.createTempFile("update_abc", "")
        try {
            file.writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0))
            assertTrue(WindowsMsiInstall.isMsi(file))
        } finally {
            file.delete()
        }
    }

    @Test fun an_exe_is_not_an_msi() {
        val file = File.createTempFile("update_abc", ".exe")
        try {
            file.writeBytes(byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0))
            assertFalse(WindowsMsiInstall.isMsi(file))
        } finally {
            file.delete()
        }
    }
}
