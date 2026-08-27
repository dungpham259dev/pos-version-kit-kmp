package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path

/** Trạng thái của luồng cập nhật bắt buộc. */
sealed interface RequiredUpdateState {
    /** Đang hỏi server. */
    data object Checking : RequiredUpdateState

    /** Không có bản bắt buộc nào — app chạy bình thường. */
    data object NotRequired : RequiredUpdateState

    /** Không hỏi được server. Mặc định gate cho app chạy tiếp (fail-open). */
    data class CheckFailed(val error: VersionError) : RequiredUpdateState

    /** Có bản bắt buộc. [stage] cho biết đang ở bước nào của việc cài nó. */
    data class Required(val update: UpdateInfo, val stage: UpdateStage) : RequiredUpdateState
}

/** Bước hiện tại trong việc tải và cài bản bắt buộc. */
sealed interface UpdateStage {
    /** Chưa tải. */
    data object Ready : UpdateStage

    data class Downloading(
        val percentage: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateStage

    data class Downloaded(val file: Path) : UpdateStage

    /** Đã gọi installer, đang chờ nền tảng. */
    data object Installing : UpdateStage

    /** Android chưa được cấp quyền "cài ứng dụng không xác định". */
    data object InstallPermissionRequired : UpdateStage

    /** Luôn thử lại được bằng [RequiredUpdateController.retry]. */
    data class Failed(val message: String) : UpdateStage
}
