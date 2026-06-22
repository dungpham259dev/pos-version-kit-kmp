package com.vtcode.pos.version.presentation.util

/**
 * Utility functions for formatting data in the presentation layer.
 * These functions should NOT be in domain entities - they are UI concerns.
 *
 * Pure-Kotlin implementations (no java.* / String.format) so they work on every
 * Compose Multiplatform target.
 */
internal object FormatUtils {

    /**
     * Format file size bytes to human-readable string.
     *
     * @param bytes File size in bytes
     * @return Formatted string (e.g., "15.50 MB", "2.30 GB", "450 B")
     */
    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "${twoDecimals(bytes, 1_000_000_000.0)} GB"
        bytes >= 1_000_000 -> "${twoDecimals(bytes, 1_000_000.0)} MB"
        bytes >= 1_000 -> "${twoDecimals(bytes, 1_000.0)} KB"
        else -> "$bytes B"
    }

    /**
     * Parse and format file size from string.
     * Handles both raw byte strings and pre-formatted strings.
     */
    fun formatFileSizeString(sizeString: String): String {
        val trimmed = sizeString.trim()

        // If already formatted (contains letters), return as-is
        if (trimmed.any { it.isLetter() }) {
            return trimmed
        }

        val bytes = trimmed.toLongOrNull() ?: 0L
        return formatFileSize(bytes)
    }

    /**
     * Format download progress percentage.
     */
    fun formatProgress(downloadedBytes: Long, totalBytes: Long): String {
        val percentage = if (totalBytes > 0) {
            ((downloadedBytes * 100) / totalBytes).toInt()
        } else 0
        return "$percentage%"
    }

    /**
     * Format download speed.
     */
    fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1_000_000 -> "${oneDecimal(bytesPerSecond, 1_000_000.0)} MB/s"
        bytesPerSecond >= 1_000 -> "${oneDecimal(bytesPerSecond, 1_000.0)} KB/s"
        else -> "$bytesPerSecond B/s"
    }

    /** Render bytes/divisor with exactly two decimal places, no locale dependency. */
    private fun twoDecimals(bytes: Long, divisor: Double): String {
        val scaled = ((bytes / divisor) * 100.0).toLong() // round toward zero, 2 dp
        val whole = scaled / 100
        val frac = (scaled % 100).toString().padStart(2, '0')
        return "$whole.$frac"
    }

    /** Render bytes/divisor with exactly one decimal place, no locale dependency. */
    private fun oneDecimal(bytes: Long, divisor: Double): String {
        val scaled = ((bytes / divisor) * 10.0).toLong()
        val whole = scaled / 10
        val frac = (scaled % 10).toString()
        return "$whole.$frac"
    }
}
