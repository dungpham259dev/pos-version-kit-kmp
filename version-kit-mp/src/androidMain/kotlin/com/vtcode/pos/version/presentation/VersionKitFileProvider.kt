package com.vtcode.pos.version.presentation

import androidx.core.content.FileProvider

/**
 * Dedicated [FileProvider] subclass for the version kit.
 *
 * A library must NOT register the bare `androidx.core.content.FileProvider`
 * class in its manifest: the manifest merger keys `<provider>` elements by
 * `android:name`, so it would collide with the host app's own FileProvider and
 * the kit's authority (`<applicationId>.versionkit.fileprovider`) would be
 * dropped at merge time — causing `getUriForFile` to fail with
 * "Couldn't find meta-data for provider with authority …".
 *
 * Using a unique subclass name guarantees the kit's provider survives the merge
 * alongside the host's.
 */
internal class VersionKitFileProvider : FileProvider()
