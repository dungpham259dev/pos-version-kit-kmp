package com.vtcode.pos.version.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VersionDataTest {

    private fun item(isRequired: Boolean? = null) = VersionResponseItem(
        id = "v1",
        version = "2.0.0",
        fileId = "file-1",
        isDownload = true,
        isRequired = isRequired
    )

    @Test
    fun maps_is_required_true() {
        val data = VersionData.fromResponseItem(item(isRequired = true))
        assertNotNull(data)
        assertTrue(data.isRequired)
        assertTrue(data.toUpdateInfo().isRequired)
    }

    @Test
    fun maps_is_required_false() {
        val data = VersionData.fromResponseItem(item(isRequired = false))
        assertNotNull(data)
        assertFalse(data.isRequired)
    }

    @Test
    fun missing_is_required_defaults_to_false() {
        val data = VersionData.fromResponseItem(item(isRequired = null))
        assertNotNull(data)
        assertFalse(data.isRequired)
        assertFalse(data.toUpdateInfo().isRequired)
    }

    @Test
    fun drops_item_without_id_or_version() {
        assertEquals(null, VersionData.fromResponseItem(VersionResponseItem(id = null, version = "2.0.0")))
        assertEquals(null, VersionData.fromResponseItem(VersionResponseItem(id = "v1", version = null)))
    }
}
