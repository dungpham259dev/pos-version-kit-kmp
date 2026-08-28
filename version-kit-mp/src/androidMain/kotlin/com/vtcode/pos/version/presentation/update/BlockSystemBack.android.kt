package com.vtcode.pos.version.presentation.update

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun BlockSystemBack() {
    BackHandler(enabled = true) { }
}
