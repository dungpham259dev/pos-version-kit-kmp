package com.vtcode.pos.version.domain.entity

import okio.Path

private val UNSAFE_FILE_ID_CHARS = Regex("[^A-Za-z0-9_-]")
private val SAFE_EXTENSION = Regex("[a-z0-9]{1,8}")

/**
 * One definition of where a download lands, shared by the code that writes it and the code that
 * looks for it later. Sanitising keeps a hostile `fileId` from escaping [destinationDir].
 * The server file's extension is kept because Windows picks the installer handler by it (`.msi`).
 */
fun downloadTargetPath(fileId: String, serverFilePath: String, destinationDir: Path): Path {
    val safeId = fileId.replace(UNSAFE_FILE_ID_CHARS, "_")
    val extension = serverFilePath.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    val suffix = if (SAFE_EXTENSION.matches(extension)) ".$extension" else ""
    return destinationDir / "update_$safeId$suffix"
}
