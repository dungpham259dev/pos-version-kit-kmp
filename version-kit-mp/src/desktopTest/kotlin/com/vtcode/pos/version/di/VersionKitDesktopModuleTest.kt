package com.vtcode.pos.version.di

import com.vtcode.pos.version.domain.usecase.InstallUpdateUseCase
import com.vtcode.pos.version.presentation.VersionChecker
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.test.check.checkModules
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Xác thực đồ thị Koin thật (versionKitModule + versionKitDesktopModule) tự resolve được,
 * chứ không chỉ biên dịch. Trước đây branch này từng phát hành một binding sai
 * (`single<IVersionInstaller> { get<VersionInstaller>() }` bị thiếu) mà không test nào bắt
 * được vì Koin chỉ báo lỗi khi thật sự get() một definition tại runtime.
 */
class VersionKitDesktopModuleTest {

    private val config = VersionKitConfig(baseUrl = "https://test.local", softwareCode = "ipos")

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun koin_graph_verifies_with_checkModules() {
        koinApplication {
            modules(versionKitModule(config) + versionKitDesktopModule())
            checkModules()
        }
    }

    @Test
    fun koin_graph_resolves_public_entry_points() {
        val koin = startKoin {
            modules(versionKitModule(config) + versionKitDesktopModule())
        }.koin

        // Cả hai đều kéo theo toàn bộ chuỗi phụ thuộc phía sau (api, datasource,
        // repository, use case, installer) — resolve thành công nghĩa là mọi binding khớp.
        koin.get<VersionChecker>()
        koin.get<InstallUpdateUseCase>()
    }
}
