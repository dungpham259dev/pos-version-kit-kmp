package com.vtcode.pos.version.domain.service

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.entity.compareVersions as compareSemver
import okio.FileSystem
import okio.Path

class VersionService : IVersionService {

    override fun compareVersions(remoteVersion: String, currentVersion: String): Int =
        compareSemver(remoteVersion, currentVersion)

    override fun shouldPresentUpdate(updateInfo: UpdateInfo, currentVersion: String): Boolean {
        if (!updateInfo.hasUpdate) return false
        if (!updateInfo.isDownloadable) return false
        return compareVersions(updateInfo.version, currentVersion) > 0
    }

    // Validate by content not server size: an APK/MSI(zip) starts with PK\x03\x04.
    // Catches HTTP-200 error bodies saved as files.
    override fun isDownloadComplete(updateInfo: UpdateInfo, file: Path, fileSystem: FileSystem): Boolean {
        if (!fileSystem.exists(file)) return false
        val meta = fileSystem.metadataOrNull(file) ?: return false
        if ((meta.size ?: 0L) == 0L) return false
        return isZipFile(file, fileSystem)
    }

    private fun isZipFile(file: Path, fileSystem: FileSystem): Boolean = try {
        fileSystem.read(file) {
            val header = ByteArray(4)
            val read = read(header)
            read == 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
        }
    } catch (e: Exception) {
        false
    }
}
