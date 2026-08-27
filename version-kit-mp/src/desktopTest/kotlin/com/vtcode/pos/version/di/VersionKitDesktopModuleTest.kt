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
 * Koin only fails when a definition is actually resolved, so compiling proves nothing about the
 * graph; this branch shipped a missing `single<IVersionInstaller>` binding that no test caught.
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

        // both pull the whole chain behind them, so resolving proves every binding lines up
        koin.get<VersionChecker>()
        koin.get<InstallUpdateUseCase>()
    }
}
