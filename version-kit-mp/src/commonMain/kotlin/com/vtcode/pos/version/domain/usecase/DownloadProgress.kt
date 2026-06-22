package com.vtcode.pos.version.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Path

/**
 * Sealed class representing download progress states.
 */
sealed class DownloadProgress {
    /** Initial idle state before any download starts. */
    object Idle : DownloadProgress()

    /**
     * Download is in progress.
     *
     * @param percentage Download percentage (0-100)
     * @param totalBytes Total file size in bytes
     * @param downloadedBytes Bytes downloaded so far
     */
    data class Downloading(
        val percentage: Int,
        val totalBytes: Long,
        val downloadedBytes: Long
    ) : DownloadProgress()

    /**
     * Download completed successfully.
     *
     * @param file Downloaded APK file on disk
     */
    data class Completed(val file: Path) : DownloadProgress()

    /** Download was cancelled by user. */
    object Cancelled : DownloadProgress()

    /**
     * Download failed with error.
     *
     * @param error Error message
     */
    data class Failed(val error: String) : DownloadProgress()
}

/**
 * Tracks download progress for a single download operation.
 * NOT a singleton — create a new instance for each download.
 */
class DownloadProgressTracker {
    private val _progress = MutableStateFlow<DownloadProgress>(DownloadProgress.Idle)
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    /** Update download progress. Clamps percentage to 0..100. */
    fun updateProgress(percentage: Int, totalBytes: Long, downloadedBytes: Long) {
        _progress.value = DownloadProgress.Downloading(
            percentage = percentage.coerceIn(0, 100),
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes
        )
    }

    /** Mark download as completed with the resulting [file]. */
    fun complete(file: Path) {
        _progress.value = DownloadProgress.Completed(file)
    }

    /** Mark download as failed. */
    fun fail(error: String) {
        _progress.value = DownloadProgress.Failed(error)
    }

    /** Mark download as cancelled. */
    fun cancel() {
        _progress.value = DownloadProgress.Cancelled
    }

    /** Reset to idle state. */
    fun reset() {
        _progress.value = DownloadProgress.Idle
    }

    /** Get current progress value. */
    fun current(): DownloadProgress = _progress.value

    /** True while a [DownloadProgress.Downloading] is active. */
    val isInProgress: Boolean
        get() = _progress.value is DownloadProgress.Downloading
}
