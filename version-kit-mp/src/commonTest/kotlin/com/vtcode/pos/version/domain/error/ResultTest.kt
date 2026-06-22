package com.vtcode.pos.version.domain.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResultTest {
    @Test fun map_transforms_success() {
        val r: Result<Int, VersionError> = Result.Success(2)
        assertEquals(Result.Success(4), r.map { it * 2 })
    }
    @Test fun map_passes_through_error() {
        val r: Result<Int, VersionError> = Result.Error(VersionError.TimeoutError)
        assertEquals(null, r.map { it * 2 }.getOrNull())
    }
    @Test fun getOrNull_on_error_is_null() {
        val r: Result<Int, VersionError> = Result.Error(VersionError.DownloadCancelled)
        assertNull(r.getOrNull())
    }
}
