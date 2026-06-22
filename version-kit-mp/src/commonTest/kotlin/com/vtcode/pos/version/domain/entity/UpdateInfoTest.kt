package com.vtcode.pos.version.domain.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateInfoTest {
    @Test fun compares_semver() {
        assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(compareVersions("v1.0.0", "1.0.0") == 0)
        assertTrue(compareVersions("1.0.0-alpha", "1.0.0-beta") == 0)
    }
    @Test fun parses_file_size() {
        assertEquals(89544000L, parseFileSize("89544000"))
        assertEquals(85_400_000L, parseFileSize("85.4 MB"))
        assertEquals(0L, parseFileSize("garbage"))
    }
}
