package com.vtcode.pos.version.domain.entity

/** Callers pass an already-filtered list, so a required version with no file can never lock the app. */
fun List<UpdateInfo>.requiredUpdate(): UpdateInfo? =
    filter { it.isRequired }
        .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
