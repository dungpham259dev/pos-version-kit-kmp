package com.vtcode.pos.version.data.datasource

import com.vtcode.pos.version.data.api.VersionApi
import com.vtcode.pos.version.data.model.VersionData
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import okio.FileSystem
import okio.Path
import okio.buffer

internal class VersionRemoteDataSource(
    private val api: VersionApi,
    private val client: HttpClient,
    private val baseUrl: String
) : IVersionRemoteDataSource {

    override suspend fun checkForUpdate(softwareCode: String, currentVersion: String):
        Result<List<VersionData>, VersionError> = try {
        val response = api.getStableVersion(softwareCode, currentVersion)
        val list = response.data?.mapNotNull { VersionData.fromResponseItem(it) } ?: emptyList()
        Result.Success(list)
    } catch (e: HttpRequestTimeoutException) {
        Result.Error(VersionError.TimeoutError)
    } catch (e: ResponseException) {
        Result.Error(VersionError.ApiError(e.response.status.value, e.message))
    } catch (e: Exception) {
        Result.Error(VersionError.NetworkError(e.message ?: "Network error"))
    }

    override suspend fun downloadFileWithProgress(
        fileId: String,
        destinationDir: Path,
        fileSystem: FileSystem,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Path, VersionError> {
        if (fileId.isBlank()) return Result.Error(VersionError.UnknownError("Invalid fileId"))
        val safe = fileId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val target = destinationDir / "update_$safe"
        return try {
            fileSystem.createDirectories(destinationDir)
            val base = baseUrl.trimEnd('/')
            client.prepareGet("$base/api/upload/download/$fileId").execute { response ->
                val total = response.contentLength() ?: -1L
                var downloaded = 0L
                val channel = response.bodyAsChannel()
                fileSystem.sink(target).buffer().use { sink ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n == -1) break
                        sink.write(buf, 0, n)
                        downloaded += n
                        val pct = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                        onProgress(pct, total, downloaded)
                    }
                }
            }
            Result.Success(target)
        } catch (e: HttpRequestTimeoutException) {
            if (fileSystem.exists(target)) fileSystem.delete(target)
            Result.Error(VersionError.TimeoutError)
        } catch (e: Exception) {
            if (fileSystem.exists(target)) fileSystem.delete(target)
            Result.Error(VersionError.NetworkError(e.message ?: "Download failed"))
        }
    }
}
