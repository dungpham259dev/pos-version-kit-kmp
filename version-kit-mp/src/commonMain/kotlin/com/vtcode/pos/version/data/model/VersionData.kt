package com.vtcode.pos.version.data.model

import com.vtcode.pos.version.domain.entity.UpdateInfo

/**
 * Internal data transfer object representing version information.
 * This is used within the data layer before mapping to domain entities.
 */
data class VersionData(
    val id: String,
    val version: String,
    val releaseDate: String,
    val softwareCode: String,
    val softwareName: String,
    val platform: String,
    val fileId: String,
    val filePath: String,
    val fileSize: String,
    val isDownloadable: Boolean,
    val status: String,
    val releaseNote: String?,
    val description: String?
) {
    companion object {
        /**
         * Create VersionData from VersionResponseItem (server API format).
         * Maps server field names to internal data class.
         *
         * Returns null if required fields (id, version) missing.
         * If downloadable but no fileId, mark not downloadable.
         */
        fun fromResponseItem(item: VersionResponseItem): VersionData? {
            if (item.id == null || item.version == null) {
                return null
            }

            val isDownloadable = item.isDownload ?: false
            val fileId = item.fileId ?: ""

            return VersionData(
                id = item.id,
                version = item.version,
                releaseDate = item.releaseDate ?: "",
                softwareCode = "", // Server returns "software" not "software_code"
                softwareName = item.software ?: "",
                platform = item.platform ?: "android",
                fileId = fileId,
                filePath = item.filePath ?: "",
                fileSize = item.size ?: "0", // Server returns "size" not "file_size"
                isDownloadable = isDownloadable && fileId.isNotBlank(), // Only downloadable if has fileId
                status = "unknown",
                releaseNote = item.releaseNote,
                description = item.description
            )
        }
    }
}

/**
 * Maps VersionData DTO to domain entity UpdateInfo.
 */
fun VersionData.toUpdateInfo(): UpdateInfo {
    return UpdateInfo(
        hasUpdate = true, // This is set when data exists
        versionId = this.id,
        version = this.version,
        releaseDate = this.releaseDate,
        softwareName = this.softwareName,
        platform = this.platform,
        fileId = this.fileId,
        filePath = this.filePath,
        fileSize = this.fileSize,
        isDownloadable = this.isDownloadable,
        releaseNote = this.releaseNote,
        description = this.description
    )
}
