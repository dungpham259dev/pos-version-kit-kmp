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
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin qualifier scoping all of Version Kit's own DI definitions that would otherwise collide
 * with a host app's definitions (notably HttpClient / HttpClientEngine). Keeping the kit's
 * HTTP stack behind this qualifier makes embedding order-independent: the host's unnamed
 * HttpClient and the kit's named one are distinct definitions, so neither overrides the other.
 */
internal val VersionKitQualifier = named("versionKit")

/**
 * Koin module for Version Kit. The host must also provide an HttpClientEngine
 * (OkHttp on Android, CIO on Desktop) and a VersionInstaller via the platform module
 * (versionKitAndroidModule() / versionKitDesktopModule()).
 */
fun versionKitModule(config: VersionKitConfig): Module = module {
    single { config }
    single(VersionKitQualifier) { createHttpClient(get<HttpClientEngine>(VersionKitQualifier)) }
    single { VersionApi(get(VersionKitQualifier), config.baseUrl) }
    single<IVersionService> { VersionService() }
    single<IVersionRemoteDataSource> {
        VersionRemoteDataSource(get(), get(VersionKitQualifier), config.baseUrl)
    }
    single<IVersionRepository> { VersionRepository(get()) }
    single { CheckUpdateUseCase(get(), get()) }
    single { DownloadUpdateUseCase(get(), get()) }
    single { InstallUpdateUseCase(get()) }
    single { VersionChecker(get(), get(), get(), get()) }
}
