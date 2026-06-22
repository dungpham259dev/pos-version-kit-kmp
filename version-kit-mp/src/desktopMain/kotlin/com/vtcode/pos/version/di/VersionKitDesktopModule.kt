package com.vtcode.pos.version.di

import com.vtcode.pos.version.presentation.install.VersionInstaller
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import org.koin.core.module.Module
import org.koin.dsl.module

fun versionKitDesktopModule(): Module = module {
    single<HttpClientEngine> { CIO.create() }
    single { VersionInstaller() }
}
