package com.vtcode.pos.version.di

import com.vtcode.pos.version.presentation.install.VersionInstaller
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

fun versionKitAndroidModule(): Module = module {
    single<HttpClientEngine> { OkHttp.create() }
    single { VersionInstaller(androidContext()) }
}
