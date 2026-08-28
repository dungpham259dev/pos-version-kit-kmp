package com.vtcode.pos.version.domain.entity

import okio.Path

/**
 * One definition of where a download lands, shared by the code that writes it and the code that
 * looks for it later. Sanitising keeps a hostile `fileId` from escaping [destinationDir].
 */
fun downloadTargetPath(fileId: String, destinationDir: Path): Path {
    val safe = fileId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return destinationDir / "update_$safe"
}
