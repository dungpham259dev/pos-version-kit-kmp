package com.vtcode.pos.version.domain.entity

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadTargetTest {
    private val dir = "/cache".toPath()

    @Test fun keeps_server_file_extension_so_the_os_can_open_it() {
        assertEquals(dir / "update_abc.msi", downloadTargetPath("abc", "upload/ipos-desktop/ipos-desktop-1.0.13.msi", dir))
    }
    @Test fun extension_is_lowercased() {
        assertEquals(dir / "update_abc.apk", downloadTargetPath("abc", "upload/ipos/ipos.APK", dir))
    }
    @Test fun no_extension_when_server_path_has_none() {
        assertEquals(dir / "update_abc", downloadTargetPath("abc", "", dir))
        assertEquals(dir / "update_abc", downloadTargetPath("abc", "upload/v1.0/installer", dir))
    }
    @Test fun hostile_extension_is_dropped() {
        assertEquals(dir / "update_abc", downloadTargetPath("abc", "x.ms/../../evil", dir))
        assertEquals(dir / "update_abc", downloadTargetPath("abc", "x.m\\si", dir))
    }
    @Test fun file_id_is_still_sanitised() {
        assertEquals(dir / "update____x.msi", downloadTargetPath("../x", "a.msi", dir))
    }
}
