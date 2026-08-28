package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.entity.requiredUpdate
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.usecase.DownloadProgress
import com.vtcode.pos.version.domain.usecase.DownloadProgressTracker
import com.vtcode.pos.version.presentation.VersionChecker
import com.vtcode.pos.version.presentation.install.InstallOutcome
import com.vtcode.pos.version.presentation.install.platformInstallOutcomes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Headless state holder for the mandatory-update flow.
 *
 * [scope] **must be confined to the main dispatcher** (a `viewModelScope` or
 * `CoroutineScope(SupervisorJob() + Dispatchers.Main)`): the controller's internal state is not
 * safe against concurrent mutation, so an IO/Default-backed scope can let two calls pass the
 * same guard. The controller runs its work on a child scope, so [close] leaves the host's
 * [scope] alive while cancelling [scope] still cancels the controller.
 *
 * [recheckIntervalMs] re-runs [check] after the state settles on [RequiredUpdateState.NotRequired]
 * or [RequiredUpdateState.CheckFailed] — without it, fail-open becomes fail-permanently-open for
 * a terminal that booted offline. Never re-checks while [RequiredUpdateState.Required], since
 * [check] abandons an in-flight download. `null` disables it.
 */
class RequiredUpdateController internal constructor(
    private val scope: CoroutineScope,
    private val versionChecker: VersionChecker,
    private val currentVersion: String,
    private val cacheDir: Path,
    softwareCode: String? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val autoInstall: Boolean = true,
    private val recheckIntervalMs: Long? = 15 * 60 * 1000L,
    private val installOutcomes: Flow<InstallOutcome>
) {

    constructor(
        scope: CoroutineScope,
        versionChecker: VersionChecker,
        currentVersion: String,
        cacheDir: Path,
        softwareCode: String? = null,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        autoInstall: Boolean = true,
        recheckIntervalMs: Long? = 15 * 60 * 1000L
    ) : this(
        scope = scope,
        versionChecker = versionChecker,
        currentVersion = currentVersion,
        cacheDir = cacheDir,
        softwareCode = softwareCode,
        fileSystem = fileSystem,
        autoInstall = autoInstall,
        recheckIntervalMs = recheckIntervalMs,
        installOutcomes = platformInstallOutcomes
    )

    private val softwareCode: String = softwareCode
        ?: versionChecker.defaultSoftwareCode
        ?: error("softwareCode not provided and VersionKitConfig.softwareCode is null")

    private val controllerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val _state = MutableStateFlow<RequiredUpdateState>(RequiredUpdateState.Checking)
    val state: StateFlow<RequiredUpdateState> = _state.asStateFlow()

    private var lastDownloadedFile: Path? = null
    private var downloadTracker: DownloadProgressTracker? = null

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var recheckJob: Job? = null

    init {
        check()
        observeInstallOutcomes()
    }

    /** Bus is process-wide with no per-install token, so only a Failure while Installing counts. */
    private fun observeInstallOutcomes() {
        controllerScope.launch {
            installOutcomes.collect { outcome ->
                when (outcome) {
                    // process is about to be replaced; stay on Installing
                    is InstallOutcome.Success -> Unit

                    is InstallOutcome.Failure -> {
                        val current = _state.value as? RequiredUpdateState.Required ?: return@collect
                        if (current.stage !is UpdateStage.Installing) return@collect
                        setStage(
                            UpdateStage.Failed(
                                outcome.message?.takeIf { it.isNotBlank() } ?: "Cài đặt thất bại"
                            )
                        )
                    }
                }
            }
        }
    }

    /** Abandons an in-flight download and any pending re-check, so no stale cycle can drive the new one. */
    fun check() {
        checkJob?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        downloadTracker = null
        lastDownloadedFile = null
        recheckJob?.cancel()
        recheckJob = null
        checkJob = controllerScope.launch {
            _state.value = RequiredUpdateState.Checking
            val result = versionChecker.check(softwareCode, currentVersion)
            // belt-and-braces: a superseded cycle must not write state nor schedule a re-check
            if (!coroutineContext.isActive) return@launch
            when (result) {
                is Result.Success -> {
                    val required = result.value.requiredUpdate()
                    _state.value = if (required == null) {
                        RequiredUpdateState.NotRequired
                    } else {
                        RequiredUpdateState.Required(required, resumeStageFor(required))
                    }
                }

                is Result.Error -> {
                    _state.value = RequiredUpdateState.CheckFailed(result.error)
                }
            }
            scheduleRecheck()
        }
    }

    /** The timer job clears its own reference before calling [check], so [check] cannot cancel it mid-run. */
    private fun scheduleRecheck() {
        val interval = recheckIntervalMs ?: return
        when (_state.value) {
            is RequiredUpdateState.NotRequired, is RequiredUpdateState.CheckFailed -> Unit
            else -> return
        }
        // belt-and-braces: never leave two timers alive if this is somehow reached twice
        recheckJob?.cancel()
        recheckJob = controllerScope.launch {
            delay(interval)
            recheckJob = null
            check()
        }
    }

    /**
     * Picks up an installer already sitting in [cacheDir] instead of downloading it again — granting
     * Android's install permission can kill the process, and the user should not pay for the APK twice.
     * Deliberately does not auto-install: firing the system dialog straight off a cold start is not
     * something the user asked for here.
     */
    private fun resumeStageFor(update: UpdateInfo): UpdateStage {
        val existing = versionChecker.existingDownload(update, cacheDir, fileSystem) ?: return UpdateStage.Ready
        lastDownloadedFile = existing
        return UpdateStage.Downloaded(existing)
    }

    /** No-op unless the stage is [UpdateStage.Ready] or [UpdateStage.Failed]. */
    fun download() {
        val current = _state.value as? RequiredUpdateState.Required ?: return
        if (current.stage !is UpdateStage.Ready && current.stage !is UpdateStage.Failed) return
        if (downloadTracker?.isInProgress == true) return

        val update = current.update
        val tracker = DownloadProgressTracker()
        downloadTracker = tracker
        setStage(UpdateStage.Downloading(0, 0L, 0L))

        // one Job over both the collector and the download, so check() can cancel the lot at once
        downloadJob = controllerScope.launch {
            launch {
                tracker.progress.collectLatest { progress ->
                    when (progress) {
                        is DownloadProgress.Downloading -> setStage(
                            UpdateStage.Downloading(
                                percentage = progress.percentage,
                                downloadedBytes = progress.downloadedBytes,
                                totalBytes = progress.totalBytes
                            )
                        )

                        is DownloadProgress.Completed -> {
                            lastDownloadedFile = progress.file
                            downloadTracker = null
                            setStage(UpdateStage.Downloaded(progress.file))
                            if (autoInstall) install()
                        }

                        is DownloadProgress.Failed -> {
                            downloadTracker = null
                            setStage(UpdateStage.Failed("Tải xuống thất bại: ${progress.error}"))
                        }

                        is DownloadProgress.Cancelled -> {
                            downloadTracker = null
                            setStage(UpdateStage.Ready)
                        }

                        DownloadProgress.Idle -> Unit
                    }
                }
            }

            launch {
                versionChecker.downloadUpdate(update, cacheDir, fileSystem, tracker)
            }
        }
    }

    /** Installs the last downloaded file; no-op if nothing is downloaded or an install is in flight. */
    fun install() {
        val file = lastDownloadedFile ?: return
        val current = _state.value as? RequiredUpdateState.Required ?: return
        if (current.stage is UpdateStage.Installing) return

        setStage(UpdateStage.Installing)
        controllerScope.launch {
            val result = withContext(Dispatchers.Default) { versionChecker.installUpdate(file) }
            when (result) {
                // system UI takes over; the real result arrives via platformInstallOutcomes
                is Result.Success -> Unit

                is Result.Error -> setStage(
                    if (result.error is VersionError.InstallPermissionRequired) {
                        UpdateStage.InstallPermissionRequired
                    } else {
                        UpdateStage.Failed(result.error.message)
                    }
                )
            }
        }
    }

    fun requestInstallPermission(): Boolean = versionChecker.requestInstallPermission()

    fun canInstallPackages(): Boolean = versionChecker.canInstallPackages()

    private fun setStage(stage: UpdateStage) {
        val current = _state.value as? RequiredUpdateState.Required ?: return
        _state.value = current.copy(stage = stage)
    }

    fun retry() {
        when (val current = _state.value) {
            is RequiredUpdateState.CheckFailed -> check()
            is RequiredUpdateState.Required -> when (current.stage) {
                is UpdateStage.Ready, is UpdateStage.Failed -> download()
                is UpdateStage.Downloaded, UpdateStage.InstallPermissionRequired -> install()
                else -> Unit
            }
            else -> Unit
        }
    }

    /** Cancels only the controller's own scope, leaving the host's [scope] alive. */
    fun close() {
        controllerScope.cancel()
    }
}
