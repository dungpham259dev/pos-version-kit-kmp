package com.vtcode.pos.version.domain.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequiredUpdateTest {

    private fun update(version: String, isRequired: Boolean) = UpdateInfo(
        hasUpdate = true,
        versionId = version,
        version = version,
        releaseDate = "",
        softwareName = "ipos",
        platform = "android",
        fileId = "file-$version",
        filePath = "",
        fileSize = "1000",
        isDownloadable = true,
        releaseNote = null,
        description = null,
        isRequired = isRequired
    )

    @Test
    fun returns_null_when_no_required_update() {
        val list = listOf(update("2.0.0", false), update("3.0.0", false))
        assertNull(list.requiredUpdate())
    }

    @Test
    fun returns_null_for_empty_list() {
        assertNull(emptyList<UpdateInfo>().requiredUpdate())
    }

    @Test
    fun picks_highest_required_version() {
        val list = listOf(
            update("2.0.0", true),
            update("3.1.0", true),
            update("4.0.0", false)
        )
        assertEquals("3.1.0", list.requiredUpdate()?.version)
    }

    @Test
    fun ignores_non_required_even_if_higher() {
        val list = listOf(update("1.5.0", true), update("9.9.9", false))
        assertEquals("1.5.0", list.requiredUpdate()?.version)
    }
}
