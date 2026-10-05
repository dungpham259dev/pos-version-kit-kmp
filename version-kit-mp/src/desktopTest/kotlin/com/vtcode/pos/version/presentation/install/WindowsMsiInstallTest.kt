package com.vtcode.pos.version.presentation.install

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsMsiInstallTest {

    private val msi = """C:\Users\Dũng Phạm\AppData\Local\ipos\cache\update_abc.msi"""
    private val app = """C:\Program Files\iPOS\iPOS.exe"""

    @Test fun waits_for_the_running_app_to_exit_before_touching_its_files() {
        val script = WindowsMsiInstall.script(msi, parentPid = 4242, relaunchPath = app)
        val wait = script.indexOf("Wait-Process -Id 4242")
        val install = script.indexOf("msiexec.exe")
        assertTrue(wait in 0 until install, script)
    }

    @Test fun installs_unattended_with_a_verbose_log_next_to_the_installer() {
        val script = WindowsMsiInstall.script(msi, parentPid = 1, relaunchPath = null)
        assertTrue("/i \"$msi\"" in script, script)
        assertTrue("/passive" in script, script)
        assertTrue("/norestart" in script, script)
        assertTrue("/l*v \"$msi.log\"" in script, script)
        assertTrue("-Wait" in script, script)
    }

    // A cancelled UAC prompt must not leave the POS with no app running at all.
    @Test fun relaunches_the_app_after_the_installer_exits_whatever_the_outcome() {
        val script = WindowsMsiInstall.script(msi, parentPid = 1, relaunchPath = app)
        val install = script.indexOf("msiexec.exe")
        val relaunch = script.indexOf("Start-Process -FilePath '$app'")
        assertTrue(relaunch > install, script)
        assertFalse("ExitCode" in script, script)
    }

    @Test fun no_relaunch_when_not_running_from_a_packaged_launcher() {
        val script = WindowsMsiInstall.script(msi, parentPid = 1, relaunchPath = null)
        assertEquals(1, Regex("Start-Process").findAll(script).count(), script)
    }

    @Test fun single_quotes_in_paths_are_escaped_for_powershell() {
        val script = WindowsMsiInstall.script("""C:\O'Neil\u.msi""", parentPid = 1, relaunchPath = """C:\O'Neil\app.exe""")
        assertTrue("""C:\O''Neil\u.msi""" in script, script)
        assertTrue("""C:\O''Neil\app.exe""" in script, script)
    }

    @Test fun command_carries_the_script_utf16_encoded_so_non_ascii_paths_survive() {
        val script = WindowsMsiInstall.script(msi, parentPid = 1, relaunchPath = app)
        val command = WindowsMsiInstall.command(script)
        assertEquals("powershell.exe", command.first())
        assertTrue("-NonInteractive" in command)
        val encoded = command[command.indexOf("-EncodedCommand") + 1]
        assertEquals(script, String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE))
    }
}
