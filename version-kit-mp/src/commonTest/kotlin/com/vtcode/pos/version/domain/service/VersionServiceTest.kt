package com.vtcode.pos.version.domain.service

import com.vtcode.pos.version.domain.entity.UpdateInfo
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun update(downloadable: Boolean = true, version: String = "2.0.0") = UpdateInfo(
    hasUpdate = true, versionId = "1", version = version, releaseDate = "", softwareName = "ipos",
    platform = "android", fileId = "f1", filePath = "", fileSize = "100", isDownloadable = downloadable,
    releaseNote = null, description = null
)

class VersionServiceTest {
    private val svc = VersionService()

    @Test fun should_present_newer_downloadable() {
        assertTrue(svc.shouldPresentUpdate(update(version = "2.0.0"), "1.0.0"))
    }
    @Test fun should_not_present_when_not_downloadable() {
        assertFalse(svc.shouldPresentUpdate(update(downloadable = false), "1.0.0"))
    }
    @Test fun zip_magic_file_is_complete() {
        val fs = FakeFileSystem()
        val p = "update.apk".toPath()
        fs.write(p) { write(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)) }
        assertTrue(svc.isDownloadComplete(update(), p, fs))
    }
    @Test fun msi_compound_file_is_complete() {
        val fs = FakeFileSystem()
        val p = "update.msi".toPath()
        fs.write(p) {
            write(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0x00))
        }
        assertTrue(svc.isDownloadComplete(update(), p, fs))
    }
    @Test fun windows_executable_is_complete() {
        val fs = FakeFileSystem()
        val p = "update.exe".toPath()
        fs.write(p) { write(byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00)) }
        assertTrue(svc.isDownloadComplete(update(), p, fs))
    }
    @Test fun non_zip_file_is_incomplete() {
        val fs = FakeFileSystem()
        val p = "error.html".toPath()
        fs.write(p) { write("<html>".encodeToByteArray()) }
        assertFalse(svc.isDownloadComplete(update(), p, fs))
    }
}
