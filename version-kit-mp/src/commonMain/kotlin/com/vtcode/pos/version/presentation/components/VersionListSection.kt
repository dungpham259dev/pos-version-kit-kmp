package com.vtcode.pos.version.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.usecase.DownloadProgress
import com.vtcode.pos.version.domain.usecase.DownloadProgressTracker
import com.vtcode.pos.version.presentation.VersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import okio.FileSystem
import okio.Path

// ===== CALLBACKS INTERFACE =====

interface VersionListCallbacks {
    /**
     * Host is asked to install the downloaded APK [file].
     *
     * Return `true` if the host handled the install itself (e.g. it has its own
     * permission flow). Return `false` to delegate to the kit, which will attempt
     * the install via [VersionChecker.installUpdate].
     */
    fun onInstallRequested(file: Path): Boolean
    fun onError(error: String, action: String? = null, onAction: (() -> Unit)? = null)

    companion object {
        operator fun invoke(
            onInstallRequested: (Path) -> Boolean,
            onError: (String, String?, (() -> Unit)?) -> Unit = { _, _, _ -> }
        ): VersionListCallbacks = object : VersionListCallbacks {
            override fun onInstallRequested(file: Path) = onInstallRequested(file)
            override fun onError(error: String, action: String?, onAction: (() -> Unit)?) =
                onError(error, action, onAction)
        }
    }
}

// ===== UI STATE =====

sealed class VersionListUiState {
    object Idle : VersionListUiState()
    object Checking : VersionListUiState()  // Initial load
    object Refreshing : VersionListUiState() // Background refresh (keep current list)
    object UpToDate : VersionListUiState()
    data class HasUpdates(
        val items: List<VersionListItemState>,
        val isAnyDownloading: Boolean = false,
        val downloadingVersion: String? = null,
        val isRefreshing: Boolean = false  // Background refresh indicator
    ) : VersionListUiState()

    data class Error(val message: String, val canRetry: Boolean) : VersionListUiState()
}

// ===== STATE HOLDER =====

/**
 * Platform-agnostic state holder driving [VersionListSection].
 *
 * Replaces the old Android `ViewModel`: it owns its own [CoroutineScope] which the
 * composable cancels on disposal (see [VersionListSection]'s `DisposableEffect`).
 */
internal class VersionListController(
    private val scope: CoroutineScope,
    private val versionChecker: VersionChecker,
    softwareCode: String? = null,
    private val currentVersion: String,
    private val cacheDir: Path,
    private val fileSystem: FileSystem,
    private val callbacks: VersionListCallbacks
) {

    private val softwareCode: String = softwareCode
        ?: versionChecker.defaultSoftwareCode
        ?: error("softwareCode not provided and VersionKitConfig.softwareCode is null")

    private val _uiState = MutableStateFlow<VersionListUiState>(VersionListUiState.Idle)
    private val _downloadingVersion = MutableStateFlow<String?>(null)

    // APK currently being installed by the kit — drives the install spinner.
    private val _installingFile = MutableStateFlow<Path?>(null)

    data class CombinedUiState(
        val uiState: VersionListUiState,
        val downloadingVersion: String?,
        val installingFile: Path? = null
    )

    val combinedState: StateFlow<CombinedUiState> = combine(
        _uiState,
        _downloadingVersion,
        _installingFile
    ) { ui, dl, installing ->
        CombinedUiState(ui, dl, installing)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CombinedUiState(VersionListUiState.Idle, null)
    )

    private var downloadTracker: DownloadProgressTracker? = null

    // Last APK handed to the installer — used to retry after an install failure.
    private var lastInstallFile: Path? = null

    init {
        checkForUpdates()
    }

    fun checkForUpdates(isRefresh: Boolean = false) {
        scope.launch {
            val currentState = _uiState.value

            if (isRefresh) {
                if (currentState is VersionListUiState.HasUpdates) {
                    _uiState.value = currentState.copy(isRefreshing = true)
                } else {
                    _uiState.value = VersionListUiState.Checking
                }
            } else {
                _uiState.value = VersionListUiState.Checking
            }

            val startMark = TimeSource.Monotonic.markNow()

            when (val result = versionChecker.check(softwareCode, currentVersion)) {
                is Result.Success -> {
                    val updates = result.value

                    if (isRefresh) {
                        ensureMinRefreshDelay(startMark)
                    }

                    if (updates.isEmpty()) {
                        _uiState.value = VersionListUiState.UpToDate
                    } else {
                        val items = updates.map { mapToItemState(it) }
                        _uiState.value = VersionListUiState.HasUpdates(
                            items = items,
                            isRefreshing = false
                        )
                    }
                }

                is Result.Error -> {
                    val errorMsg = result.error.message

                    if (isRefresh) {
                        ensureMinRefreshDelay(startMark)
                    }

                    if (isRefresh && currentState is VersionListUiState.HasUpdates) {
                        _uiState.value = currentState.copy(isRefreshing = false)
                        callbacks.onError(errorMsg, "Thử lại") {
                            checkForUpdates(isRefresh = true)
                        }
                    } else {
                        _uiState.value = VersionListUiState.Error(errorMsg, canRetry = true)
                        callbacks.onError(errorMsg, "Thử lại") { checkForUpdates() }
                    }
                }
            }
        }
    }

    private suspend fun ensureMinRefreshDelay(startMark: TimeSource.Monotonic.ValueTimeMark) {
        val elapsedMs = startMark.elapsedNow().inWholeMilliseconds
        val remainingDelay = (800 - elapsedMs).coerceAtLeast(0)
        if (remainingDelay > 0) {
            delay(remainingDelay.milliseconds)
        }
    }

    /** Cancels the controller's scope; called from the composable's onDispose. */
    fun cancelScope() {
        scope.cancel()
    }

    fun downloadUpdate(update: UpdateInfo) {
        if (!update.hasUpdate) return

        // Guard against double-taps / concurrent downloads.
        if (_downloadingVersion.value != null || downloadTracker?.isInProgress == true) return

        val tracker = DownloadProgressTracker()
        downloadTracker = tracker

        val currentState = _uiState.value
        if (currentState is VersionListUiState.HasUpdates) {
            _uiState.value = currentState.copy(
                isAnyDownloading = true,
                downloadingVersion = update.version
            )
        }
        _downloadingVersion.value = update.version

        scope.launch {
            tracker.progress.collectLatest { progress ->
                when (progress) {
                    is DownloadProgress.Completed -> {
                        updateItemToDownloaded(update.version, progress.file)
                        _downloadingVersion.value = null
                        installUpdate(progress.file)
                    }

                    is DownloadProgress.Failed -> {
                        val errorMsg = "Tải xuống thất bại: ${progress.error}"
                        _uiState.value = VersionListUiState.Error(errorMsg, canRetry = true)
                        callbacks.onError(errorMsg, "Thử lại") { checkForUpdates() }
                        _downloadingVersion.value = null
                    }

                    is DownloadProgress.Cancelled -> {
                        checkForUpdates()
                        _downloadingVersion.value = null
                    }

                    else -> {}
                }
            }
        }

        scope.launch {
            versionChecker.downloadUpdate(update, cacheDir, fileSystem, tracker)
        }
    }

    fun installUpdate(file: Path) {
        lastInstallFile = file
        val handledByHost = callbacks.onInstallRequested(file)
        if (handledByHost) return

        // Kit handles the install. Run off the main thread to avoid blocking the UI
        // on large APKs (installUpdate streams the APK on Android).
        _installingFile.value = file
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                versionChecker.installUpdate(file)
            }
            _installingFile.value = null
            when (result) {
                is Result.Success -> { /* system install UI takes over */ }

                is Result.Error -> {
                    val message = if (result.error is VersionError.InstallPermissionRequired) {
                        "Cần quyền cài đặt ứng dụng"
                    } else {
                        result.error.message
                    }
                    callbacks.onError(message, "Thử lại") { installUpdate(file) }
                }
            }
        }
    }

    private fun updateItemToDownloaded(version: String, file: Path) {
        val currentState = _uiState.value
        if (currentState is VersionListUiState.HasUpdates) {
            val updatedItems = currentState.items.map { item ->
                if (item.version == version) {
                    VersionListItemState.Downloaded(
                        version = item.version,
                        channel = item.channel,
                        releaseDate = item.releaseDate,
                        releaseNote = item.releaseNote,
                        fileSizeBytes = item.fileSizeBytes,
                        fileId = item.fileId,
                        file = file
                    )
                } else item
            }
            _uiState.value = currentState.copy(
                items = updatedItems,
                isAnyDownloading = false,
                downloadingVersion = null
            )
        }
    }

    companion object {
        private val ALPHA_REGEX = Regex("""[-._]?(alpha|a)[-._]?[0-9]*""", RegexOption.IGNORE_CASE)
        private val BETA_REGEX =
            Regex("""[-._]?(beta|b|preview|rc)[-._]?[0-9]*""", RegexOption.IGNORE_CASE)
        private val LEGACY_REGEX =
            Regex("""[-._]?(legacy|old|deprecat)[-._]?[0-9]*""", RegexOption.IGNORE_CASE)
    }

    private fun mapToItemState(update: UpdateInfo): VersionListItemState {
        val channel = when {
            ALPHA_REGEX.containsMatchIn(update.version) -> "alpha"
            BETA_REGEX.containsMatchIn(update.version) -> "beta"
            LEGACY_REGEX.containsMatchIn(update.version) -> "legacy"
            else -> "stable"
        }

        val isCurrent = update.version == currentVersion

        return when {
            isCurrent -> VersionListItemState.Current(
                version = update.version,
                channel = channel,
                releaseDate = update.releaseDate,
                releaseNote = update.releaseNote ?: "",
                fileSizeBytes = update.fileSizeBytes,
                fileId = update.fileId
            )

            !update.hasUpdate -> VersionListItemState.Disabled(
                version = update.version,
                channel = channel,
                releaseDate = update.releaseDate,
                releaseNote = update.releaseNote ?: "",
                fileSizeBytes = update.fileSizeBytes,
                fileId = update.fileId,
                reason = "Đã cài đặt"
            )

            else -> VersionListItemState.Available(
                version = update.version,
                channel = channel,
                releaseDate = update.releaseDate,
                releaseNote = update.releaseNote ?: "",
                fileSizeBytes = update.fileSizeBytes,
                fileId = update.fileId
            )
        }
    }
}

// ===== COMPOSABLE UI =====

/**
 * Renders the version-update list.
 *
 * @param cacheDir directory where downloaded APKs are written.
 * @param fileSystem file system used for the download (defaults to the host system).
 */
@Composable
fun VersionListSection(
    versionChecker: VersionChecker,
    currentVersion: String,
    callbacks: VersionListCallbacks,
    cacheDir: Path,
    modifier: Modifier = Modifier,
    softwareCode: String? = null,
    fileSystem: FileSystem = FileSystem.SYSTEM
) {
    val resolvedSoftwareCode = softwareCode
        ?: versionChecker.defaultSoftwareCode
        ?: error("softwareCode not provided and VersionKitConfig.softwareCode is null")

    // Controller owns a scope that we cancel on disposal — no leaked coroutines.
    val controller = remember(resolvedSoftwareCode) {
        VersionListController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            versionChecker = versionChecker,
            softwareCode = resolvedSoftwareCode,
            currentVersion = currentVersion,
            cacheDir = cacheDir,
            fileSystem = fileSystem,
            callbacks = callbacks
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.cancelScope() }
    }

    val combinedState by controller.combinedState.collectAsState()
    val uiState = combinedState.uiState
    val downloadingVersion = combinedState.downloadingVersion
    val installingFile = combinedState.installingFile

    val onDownloadCallback = remember(resolvedSoftwareCode) {
        { item: VersionListItemState ->
            val updateInfo = UpdateInfo(
                hasUpdate = true,
                versionId = "",
                version = item.version,
                releaseDate = "",
                softwareName = resolvedSoftwareCode,
                platform = "android",
                fileId = item.fileId,
                filePath = "",
                fileSize = item.fileSizeBytes.toString(),
                isDownloadable = true,
                releaseNote = item.releaseNote,
                description = null
            )
            controller.downloadUpdate(updateInfo)
        }
    }

    val onInstallCallback = remember(controller) {
        { file: Path -> controller.installUpdate(file) }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            val isInitialLoading = uiState is VersionListUiState.Checking
            val isRefreshing = when (uiState) {
                is VersionListUiState.HasUpdates -> uiState.isRefreshing
                else -> false
            }
            HeaderSection(
                currentVersion = currentVersion,
                isInitialLoading = isInitialLoading,
                isRefreshing = isRefreshing,
                onRefresh = { controller.checkForUpdates(isRefresh = true) }
            )

            Spacer(modifier = Modifier.height(20.dp))

            when (uiState) {
                is VersionListUiState.Idle -> {
                    CheckUpdateButton(onClick = { controller.checkForUpdates() })
                }

                is VersionListUiState.Checking -> {
                    CheckingIndicator()
                }

                is VersionListUiState.Refreshing -> {
                    CheckingIndicator()
                }

                is VersionListUiState.UpToDate -> {
                    UpToDateIndicator()
                }

                is VersionListUiState.HasUpdates -> {
                    UpdatesSection(
                        items = uiState.items,
                        isAnyDownloading = uiState.isAnyDownloading,
                        downloadingVersion = downloadingVersion,
                        installingFile = installingFile,
                        currentVersion = currentVersion,
                        onDownload = onDownloadCallback,
                        onInstall = onInstallCallback
                    )
                }

                is VersionListUiState.Error -> {
                    ErrorState(
                        message = uiState.message,
                        canRetry = uiState.canRetry,
                        onRetry = { controller.checkForUpdates() }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(
    currentVersion: String,
    isInitialLoading: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "refresh")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Phiên bản ứng dụng",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            ) {
                Text(
                    text = buildAnnotatedString {
                        append("Phiên bản hiện tại: ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(currentVersion)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            IconButton(
                onClick = onRefresh,
                enabled = !isInitialLoading,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Làm mới danh sách",
                    modifier = Modifier
                        .size(20.dp)
                        .then(
                            if (isRefreshing) {
                                Modifier.graphicsLayer { rotationZ = rotationAngle }
                            } else {
                                Modifier
                            }
                        ),
                    tint = when {
                        isRefreshing -> MaterialTheme.colorScheme.primary
                        isInitialLoading -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun UpdatesSection(
    items: List<VersionListItemState>,
    isAnyDownloading: Boolean,
    downloadingVersion: String?,
    installingFile: Path?,
    currentVersion: String,
    onDownload: (VersionListItemState) -> Unit,
    onInstall: (Path) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            val effectiveState = when {
                downloadingVersion == item.version && item !is VersionListItemState.Downloaded -> {
                    VersionListItemState.Downloading(
                        version = item.version,
                        channel = item.channel,
                        releaseDate = item.releaseDate,
                        releaseNote = item.releaseNote,
                        fileSizeBytes = item.fileSizeBytes,
                        fileId = item.fileId
                    )
                }

                else -> item
            }

            val isInstalling = item is VersionListItemState.Downloaded &&
                    installingFile == item.file

            Modifier.VersionListItem(
                state = effectiveState,
                isAnyDownloading = isAnyDownloading,
                isInstalling = isInstalling,
                currentAppVersion = currentVersion,
                onDownload = { onDownload(item) }
            ) {
                if (item is VersionListItemState.Downloaded) {
                    onInstall(item.file)
                }
            }
        }
    }
}

@Composable
private fun CheckUpdateButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Kiểm tra cập nhật")
    }
}

@Composable
private fun CheckingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = "Đang kiểm tra cập nhật…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UpToDateIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "Đã là phiên bản mới nhất",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (canRetry) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Thử lại")
            }
        }
    }
}
