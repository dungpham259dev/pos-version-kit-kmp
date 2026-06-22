package com.vtcode.pos.version.domain.entity

/**
 * Domain entity representing update information.
 * This is the clean representation used across the domain layer.
 *
 * Note: Formatting (like file size display) is a presentation concern
 * and should be handled by [com.vtcode.pos.version.presentation.util.FormatUtils].
 */
data class UpdateInfo(
    val hasUpdate: Boolean,
    val versionId: String,
    val version: String,
    val releaseDate: String,
    val softwareName: String,
    val platform: String,
    val fileId: String,
    val filePath: String,
    val fileSize: String,
    val isDownloadable: Boolean,
    val releaseNote: String?,
    val description: String?
) {
    /**
     * Gets the file size in bytes as a Long.
     * Handles both raw byte strings (e.g., "89544000") and formatted strings (e.g., "85.4 MB").
     * Returns 0 if parsing fails.
     */
    val fileSizeBytes: Long
        get() = parseFileSize(fileSize)

    /**
     * Checks if this version is newer than the provided version.
     * Uses semantic versioning comparison.
     */
    fun isNewerThan(currentVersion: String): Boolean {
        return compareVersions(version, currentVersion) > 0
    }
}

/**
 * Semantic Version regex pattern.
 * Matches: 1.2.3, v1.2.3, 2.0.0-alpha, etc.
 */
private val SEMVER_REGEX = Regex("""^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[\w.]+)?$""")

/**
 * Extracts numeric version parts from a version string.
 * Falls back to splitting by '.' if regex doesn't match.
 */
private fun extractVersionParts(version: String): List<Int> {
    val match = SEMVER_REGEX.find(version.trim())
    return if (match != null) {
        // Regex matched - extract major.minor.patch
        listOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt()
        )
    } else {
        // Fallback: split by dot and convert to int
        version.split(".")
            .map { it.filter { c -> c.isDigit() } }
            .map { it.toIntOrNull() ?: 0 }
    }
}

/**
 * Compares two version strings using semantic versioning.
 * Returns: positive if v1 > v2, negative if v1 < v2, 0 if equal
 *
 * Supports: 1.2.3, v1.2.3, and falls back to simple dot-splitting
 */
fun compareVersions(v1: String, v2: String): Int {
    val parts1 = extractVersionParts(v1)
    val parts2 = extractVersionParts(v2)

    val maxLength = maxOf(parts1.size, parts2.size)

    for (i in 0 until maxLength) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }

        when {
            p1 > p2 -> return 1
            p1 < p2 -> return -1
        }
    }

    return 0
}

/**
 * Parse file size string to bytes.
 * Handles:
 * - Raw bytes: "89544000" → 89544000
 * - Formatted: "85.4 MB", "92MB", "1.2 GB" → bytes
 * - Invalid/null → 0
 */
internal fun parseFileSize(sizeString: String): Long {
    if (sizeString.isBlank()) return 0L

    // Try raw bytes first
    sizeString.trim().toLongOrNull()?.let { return it }

    // Parse formatted size (e.g., "85.4 MB", "1.2 GB")
    val regex = Regex("""([\d.]+)\s*([KMGT]?B)""", RegexOption.IGNORE_CASE)
    val match = regex.find(sizeString.trim()) ?: return 0L

    val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
    val unit = match.groupValues[2].uppercase()

    return when (unit) {
        "B" -> value.toLong()
        "KB" -> (value * 1_000).toLong()
        "MB" -> (value * 1_000_000).toLong()
        "GB" -> (value * 1_000_000_000).toLong()
        "TB" -> (value * 1_000_000_000_000).toLong()
        else -> 0L
    }
}
