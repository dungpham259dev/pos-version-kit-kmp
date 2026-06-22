package com.vtcode.pos.version.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GetStableVersionResponse(
    @SerialName("data") val data: List<VersionResponseItem>? = null,
    @SerialName("statusCode") val statusCode: String? = null,
    @SerialName("status") val status: Boolean? = null
)

@Serializable
data class VersionResponseItem(
    @SerialName("id") val id: String? = null,
    @SerialName("stt") val stt: Int? = null,
    @SerialName("version") val version: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("software") val software: String? = null,
    @SerialName("platform") val platform: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("size") val size: String? = null,
    @SerialName("is_download") val isDownload: Boolean? = null,
    @SerialName("download_text") val downloadText: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("release_note") val releaseNote: String? = null
)
