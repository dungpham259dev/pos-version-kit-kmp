package com.vtcode.pos.version.data

import com.vtcode.pos.version.data.api.VersionApi
import com.vtcode.pos.version.data.api.createHttpClient
import com.vtcode.pos.version.data.datasource.VersionRemoteDataSource
import com.vtcode.pos.version.domain.error.Result
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionRemoteDataSourceTest {
    private val json = """{"status":true,"statusCode":"200","data":[
        {"id":"1","version":"2.0.0","software":"ipos","platform":"android",
         "file_id":"abc","is_download":true,"size":"100"}]}"""

    @Test fun parses_versions() = runTest {
        val engine = MockEngine { respond(json, HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json")) }
        val client = createHttpClient(engine)
        val ds = VersionRemoteDataSource(VersionApi(client, "http://x"), client, "http://x")
        val r = ds.checkForUpdate("ipos", "1.0.0")
        assertTrue(r is Result.Success)
        assertEquals("2.0.0", (r as Result.Success).value.first().version)
    }
}
