package com.vtcode.pos.version.domain.service

import com.vtcode.pos.version.domain.entity.UpdateInfo
import okio.FileSystem
import okio.Path

interface IVersionService {
    fun compareVersions(remoteVersion: String, currentVersion: String): Int
    fun shouldPresentUpdate(updateInfo: UpdateInfo, currentVersion: String): Boolean
    /** Validate a downloaded file is a real APK/installer (ZIP magic), not an error body. */
    fun isDownloadComplete(updateInfo: UpdateInfo, file: Path, fileSystem: FileSystem): Boolean
}
