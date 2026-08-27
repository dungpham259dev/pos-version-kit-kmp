package com.vtcode.pos.version.presentation.install

import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import okio.Path

/**
 * Hợp đồng cài đặt của nền tảng. Tách khỏi `expect class VersionInstaller` để
 * commonTest dựng được test double cho luồng install.
 */
interface IVersionInstaller {
    fun install(file: Path): Result<Unit, VersionError>

    /**
     * Mở luồng cấp quyền cài đặt của nền tảng (Android mở màn hình
     * "install unknown apps"). Trả `true` nếu đã mở được. Nền tảng không cần
     * quyền này thì no-op trả `true`.
     */
    fun requestInstallPermission(): Boolean
}
