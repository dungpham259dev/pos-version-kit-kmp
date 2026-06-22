package com.vtcode.pos.version.presentation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Install lifecycle events emitted by [PackageInstallerBroadcastReceiver].
 *
 * The receiver is instantiated by the system and has no direct link to the UI,
 * so it publishes terminal install outcomes here for the ViewModel to observe.
 */
internal sealed class InstallEvent {
    object Success : InstallEvent()
    data class Failure(val message: String?) : InstallEvent()
}

/**
 * Process-wide bus bridging the (system-instantiated) install broadcast receiver
 * and the observing ViewModel. No replay — a returning collector must not re-see a
 * stale outcome from a previous install; the buffer only absorbs a brief emit/collect
 * gap. The ViewModel collector stays active throughout an install, so live events land.
 */
internal object InstallEventBus {
    private val _events = MutableSharedFlow<InstallEvent>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val events: SharedFlow<InstallEvent> = _events.asSharedFlow()

    fun emit(event: InstallEvent) {
        _events.tryEmit(event)
    }
}
