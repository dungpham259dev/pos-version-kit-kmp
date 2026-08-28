package com.vtcode.pos.version.presentation.install

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// Desktop launches the installer and exits the process, so there is no
// asynchronous post-confirmation outcome to observe.
internal actual val platformInstallOutcomes: Flow<InstallOutcome> = emptyFlow()
