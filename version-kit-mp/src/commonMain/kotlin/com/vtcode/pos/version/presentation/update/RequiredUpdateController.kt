package com.vtcode.pos.version.presentation.update

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * State holder headless cho luồng cập nhật bắt buộc.
 *
 * Nhận [scope] từ ngoài nên dùng được cả trong Compose (qua
 * [rememberRequiredUpdateController]) lẫn trong ViewModel riêng của host. Controller tự tạo
 * một child scope từ [scope] để chạy công việc của mình; huỷ [scope] của host (cha) vẫn sẽ
 * huỷ theo child scope này (structured concurrency), nhưng [close] chỉ huỷ child scope nên
 * không ảnh hưởng tới các coroutine khác đang chạy trên [scope] của host. Gọi [close] khi
 * không dùng nữa.
 */
class RequiredUpdateController internal constructor(
    private val scope: CoroutineScope,
    private val versionChecker: VersionChecker,
    private val currentVersion: String,
    private val cacheDir: Path,
    softwareCode: String? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val autoInstall: Boolean = true,
    private val installOutcomes: Flow<InstallOutcome>
) {

    constructor(
        scope: CoroutineScope,
        versionChecker: VersionChecker,
        currentVersion: String,
        cacheDir: Path,
        softwareCode: String? = null,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        autoInstall: Boolean = true
    ) : this(
        scope = scope,
        versionChecker = versionChecker,
        currentVersion = currentVersion,
        cacheDir = cacheDir,
        softwareCode = softwareCode,
        fileSystem = fileSystem,
        autoInstall = autoInstall,
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

    init {
        check()
        observeInstallOutcomes()
    }

    /**
     * Android trả kết quả cài đặt bất đồng bộ qua broadcast sau khi dialog hệ thống đóng.
     * Không nghe thì stage kẹt ở [UpdateStage.Installing] khi người dùng bấm Huỷ.
     *
     * [InstallOutcome] đến từ `InstallEventBus` toàn tiến trình, không mang token liên kết
     * với một lần gọi [install] cụ thể. Vì vậy chỉ áp dụng [InstallOutcome.Failure] khi
     * controller thực sự đang chờ kết quả cài đặt (stage [UpdateStage.Installing]); nếu không,
     * bỏ qua — nếu không một outcome trễ/trùng từ lượt cài trước có thể ghi đè lên stage của
     * chu kỳ mới (ví dụ sau khi người dùng đã [retry]).
     */
    private fun observeInstallOutcomes() {
        controllerScope.launch {
            installOutcomes.collect { outcome ->
                when (outcome) {
                    // Tiến trình sắp bị thay thế; giữ nguyên Installing.
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

    fun check() {
        checkJob?.cancel()
        checkJob = controllerScope.launch {
            _state.value = RequiredUpdateState.Checking
            when (val result = versionChecker.check(softwareCode, currentVersion)) {
                is Result.Success -> {
                    val required = result.value.requiredUpdate()
                    _state.value = if (required == null) {
                        RequiredUpdateState.NotRequired
                    } else {
                        RequiredUpdateState.Required(required, UpdateStage.Ready)
                    }
                }

                is Result.Error -> {
                    _state.value = RequiredUpdateState.CheckFailed(result.error)
                }
            }
        }
    }

    /** Tải bản bắt buộc. No-op nếu không ở [UpdateStage.Ready] hoặc [UpdateStage.Failed]. */
    fun download() {
        val current = _state.value as? RequiredUpdateState.Required ?: return
        if (current.stage !is UpdateStage.Ready && current.stage !is UpdateStage.Failed) return
        if (downloadTracker?.isInProgress == true) return

        val update = current.update
        val tracker = DownloadProgressTracker()
        downloadTracker = tracker
        setStage(UpdateStage.Downloading(0, 0L, 0L))

        controllerScope.launch {
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

        controllerScope.launch {
            versionChecker.downloadUpdate(update, cacheDir, fileSystem, tracker)
        }
    }

    /** Cài file đã tải gần nhất. No-op nếu chưa tải xong. */
    fun install() {
        val file = lastDownloadedFile ?: return
        if (_state.value !is RequiredUpdateState.Required) return

        setStage(UpdateStage.Installing)
        controllerScope.launch {
            val result = withContext(Dispatchers.Default) { versionChecker.installUpdate(file) }
            when (result) {
                // UI hệ thống tiếp quản; kết quả thật về qua platformInstallOutcomes.
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

    /** Mở màn hình cấp quyền cài đặt của nền tảng. */
    fun requestInstallPermission(): Boolean = versionChecker.requestInstallPermission()

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

    /** Huỷ scope riêng của controller; không huỷ [scope] mà host truyền vào. */
    fun close() {
        controllerScope.cancel()
    }
}
