package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.requiredUpdate
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.presentation.VersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
class RequiredUpdateController(
    private val scope: CoroutineScope,
    private val versionChecker: VersionChecker,
    private val currentVersion: String,
    private val cacheDir: Path,
    softwareCode: String? = null,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val autoInstall: Boolean = true
) {

    private val softwareCode: String = softwareCode
        ?: versionChecker.defaultSoftwareCode
        ?: error("softwareCode not provided and VersionKitConfig.softwareCode is null")

    private val controllerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val _state = MutableStateFlow<RequiredUpdateState>(RequiredUpdateState.Checking)
    val state: StateFlow<RequiredUpdateState> = _state.asStateFlow()

    private var checkJob: Job? = null

    init {
        check()
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

    fun retry() {
        when (_state.value) {
            is RequiredUpdateState.CheckFailed -> check()
            else -> Unit
        }
    }

    /** Huỷ scope riêng của controller; không huỷ [scope] mà host truyền vào. */
    fun close() {
        controllerScope.cancel()
    }
}
