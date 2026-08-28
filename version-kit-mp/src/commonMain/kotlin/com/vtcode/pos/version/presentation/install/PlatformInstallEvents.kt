package com.vtcode.pos.version.presentation.install

import kotlinx.coroutines.flow.Flow

/**
 * Terminal outcome of a platform install that requires user confirmation
 * (notably the Android `PackageInstaller` flow, whose result arrives
 * asynchronously via a broadcast after the system dialog closes).
 */
sealed interface InstallOutcome {
    object Success : InstallOutcome
    data class Failure(val message: String?) : InstallOutcome
}

/**
 * Stream of post-confirmation install outcomes for the current platform.
 *
 * On Android this bridges the system `PackageInstaller` broadcast so the UI can
 * surface a failure (e.g. signature conflict, cancelled dialog) instead of hanging.
 * On platforms that install synchronously (Desktop exits the process) this is empty.
 */
internal expect val platformInstallOutcomes: Flow<InstallOutcome>
