package com.vtcode.pos.version.di

import com.vtcode.pos.version.data.api.VersionApi
import com.vtcode.pos.version.data.api.createHttpClient
import com.vtcode.pos.version.data.datasource.IVersionRemoteDataSource
import com.vtcode.pos.version.data.datasource.VersionRemoteDataSource
import com.vtcode.pos.version.data.repository.VersionRepository
import com.vtcode.pos.version.domain.repository.IVersionRepository
import com.vtcode.pos.version.domain.service.IVersionService
import com.vtcode.pos.version.domain.service.VersionService
import com.vtcode.pos.version.domain.usecase.CheckUpdateUseCase
import com.vtcode.pos.version.domain.usecase.DownloadUpdateUseCase
import com.vtcode.pos.version.domain.usecase.InstallUpdateUseCase
import com.vtcode.pos.version.presentation.VersionChecker
import io.ktor.client.engine.HttpClientEngine
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module for Version Kit. The host must also provide an HttpClientEngine
 * (OkHttp on Android, CIO on Desktop) and a VersionInstaller via the platform module
 * (versionKitAndroidModule() / versionKitDesktopModule()).
 */
fun versionKitModule(config: VersionKitConfig): Module = module {
    single { config }
    single { createHttpClient(get<HttpClientEngine>()) }
    single { VersionApi(get(), config.baseUrl) }
    single<IVersionService> { VersionService() }
    single<IVersionRemoteDataSource> { VersionRemoteDataSource(get(), get(), config.baseUrl) }
    single<IVersionRepository> { VersionRepository(get()) }
    single { CheckUpdateUseCase(get(), get()) }
    single { DownloadUpdateUseCase(get(), get()) }
    single { InstallUpdateUseCase(get()) }
    single { VersionChecker(get(), get(), get(), get()) }
}
