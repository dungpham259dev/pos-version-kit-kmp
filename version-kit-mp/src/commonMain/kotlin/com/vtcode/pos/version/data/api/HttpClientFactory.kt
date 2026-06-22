package com.vtcode.pos.version.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

internal fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        requestTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
}
