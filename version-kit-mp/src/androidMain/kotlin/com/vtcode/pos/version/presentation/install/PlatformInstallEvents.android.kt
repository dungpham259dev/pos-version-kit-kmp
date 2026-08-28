package com.vtcode.pos.version.presentation.install

import com.vtcode.pos.version.presentation.InstallEvent
import com.vtcode.pos.version.presentation.InstallEventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal actual val platformInstallOutcomes: Flow<InstallOutcome> =
    InstallEventBus.events.map { event ->
        when (event) {
            is InstallEvent.Success -> InstallOutcome.Success
            is InstallEvent.Failure -> InstallOutcome.Failure(event.message)
        }
    }
