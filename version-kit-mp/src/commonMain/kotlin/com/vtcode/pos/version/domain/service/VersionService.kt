package com.vtcode.pos.version.domain.service

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.entity.downloadTargetPath
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

    override fun existingDownload(
        updateInfo: UpdateInfo,
        destinationDir: Path,
        fileSystem: FileSystem
    ): Path? {
        if (updateInfo.fileId.isBlank()) return null
        val target = downloadTargetPath(updateInfo.fileId, updateInfo.filePath, destinationDir)
        return target.takeIf { isDownloadComplete(updateInfo, it, fileSystem) }
    }

    // Validate by content not server size; catches HTTP-200 error bodies saved as files.
    override fun isDownloadComplete(updateInfo: UpdateInfo, file: Path, fileSystem: FileSystem): Boolean {
        if (!fileSystem.exists(file)) return false
        val meta = fileSystem.metadataOrNull(file) ?: return false
        if ((meta.size ?: 0L) == 0L) return false
        return hasInstallerSignature(file, fileSystem)
    }

    private fun hasInstallerSignature(file: Path, fileSystem: FileSystem): Boolean = try {
        fileSystem.read(file) {
            val header = ByteArray(INSTALLER_SIGNATURES.maxOf { it.size })
            val read = read(header).coerceAtLeast(0)
            INSTALLER_SIGNATURES.any { signature ->
                read >= signature.size && signature.indices.all { header[it] == signature[it] }
            }
        }
    } catch (e: Exception) {
        false
    }

    private companion object {
        val INSTALLER_SIGNATURES: List<ByteArray> = listOf(
            // ZIP: .apk
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            // OLE compound file: .msi
            byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()),
            // PE "MZ": .exe
            byteArrayOf(0x4D, 0x5A),
        )
    }
}
