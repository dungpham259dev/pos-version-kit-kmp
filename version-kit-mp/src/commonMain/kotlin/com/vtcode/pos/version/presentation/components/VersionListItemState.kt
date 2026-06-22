package com.vtcode.pos.version.presentation.components

import okio.Path

/**
 * Sealed class representing all possible states of a version list item.
 *
 * Each state determines the visual presentation and available actions.
 *
 * @param version Version string (e.g., "2.4.1")
 * @param channel Release channel (e.g., "stable", "beta")
 * @param releaseDate Release date string (e.g., "15/01/2024")
 * @param releaseNote Release note text
 * @param fileSizeBytes File size in bytes
 * @param fileId File identifier from server (for download)
 */
sealed class VersionListItemState(
    open val version: String,
    open val channel: String,
    open val releaseDate: String,
    open val releaseNote: String,
    open val fileSizeBytes: Long,
    open val fileId: String
) {
    /** Version is available for download. */
    data class Available(
        override val version: String,
        override val channel: String,
        override val releaseDate: String,
        override val releaseNote: String,
        override val fileSizeBytes: Long,
        override val fileId: String
    ) : VersionListItemState(version, channel, releaseDate, releaseNote, fileSizeBytes, fileId)

    /** Download is in progress for this version. */
    data class Downloading(
        override val version: String,
        override val channel: String,
        override val releaseDate: String,
        override val releaseNote: String,
        override val fileSizeBytes: Long,
        override val fileId: String
    ) : VersionListItemState(version, channel, releaseDate, releaseNote, fileSizeBytes, fileId)

    /** Download completed, ready to install. */
    data class Downloaded(
        override val version: String,
        override val channel: String,
        override val releaseDate: String,
        override val releaseNote: String,
        override val fileSizeBytes: Long,
        override val fileId: String,
        val file: Path
    ) : VersionListItemState(version, channel, releaseDate, releaseNote, fileSizeBytes, fileId)

    /** Currently installed version. */
    data class Current(
        override val version: String,
        override val channel: String,
        override val releaseDate: String,
        override val releaseNote: String,
        override val fileSizeBytes: Long,
        override val fileId: String
    ) : VersionListItemState(version, channel, releaseDate, releaseNote, fileSizeBytes, fileId)

    /** Disabled (downgrade not allowed or other constraint). */
    data class Disabled(
        override val version: String,
        override val channel: String,
        override val releaseDate: String,
        override val releaseNote: String,
        override val fileSizeBytes: Long,
        override val fileId: String,
        val reason: String? = null
    ) : VersionListItemState(version, channel, releaseDate, releaseNote, fileSizeBytes, fileId)
}
