package com.vtcode.pos.version.data.api

import com.vtcode.pos.version.data.model.GetStableVersionResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/**
 * JSON API surface. The streaming download is done by VersionRemoteDataSource directly via the
 * injected HttpClient, so this class only owns the JSON call.
 */
internal class VersionApi(
    private val client: HttpClient,
    private val baseUrl: String
) {
    private val base = baseUrl.trimEnd('/')

    suspend fun getStableVersion(softwareCode: String, currentVersion: String): GetStableVersionResponse =
        client.get("$base/api/versions/get-stable/$softwareCode/$currentVersion").body()
}
