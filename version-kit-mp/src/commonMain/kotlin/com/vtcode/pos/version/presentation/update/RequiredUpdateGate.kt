package com.vtcode.pos.version.presentation.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.presentation.VersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path

/**
 * Remembers a [RequiredUpdateController] and closes it when the composable leaves the tree.
 */
@Composable
fun rememberRequiredUpdateController(
    versionChecker: VersionChecker,
    currentVersion: String,
    cacheDir: Path,
    softwareCode: String? = null,
    fileSystem: FileSystem = FileSystem.SYSTEM,
    autoInstall: Boolean = true
): RequiredUpdateController {
    val controller = remember(versionChecker, currentVersion, cacheDir, softwareCode) {
        RequiredUpdateController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            versionChecker = versionChecker,
            currentVersion = currentVersion,
            cacheDir = cacheDir,
            softwareCode = softwareCode,
            fileSystem = fileSystem,
            autoInstall = autoInstall
        )
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}

/** What the host's blocking UI receives to render itself. */
interface RequiredUpdateScope {
    val update: UpdateInfo
    val stage: UpdateStage
    fun download()
    fun install()
    fun retry()
    fun requestInstallPermission(): Boolean

    /** Re-queried on each call, so a host can drop the grant prompt after the user returns from settings. */
    fun canInstallPackages(): Boolean
}

/**
 * Draws [content], then covers it while a mandatory update is pending. The kit draws nothing
 * inside the overlay — [requiredContent] is the host's.
 *
 * While blocked, the overlay swallows pointer input and back, and also clears focus from
 * [content], stops it taking focus back, and removes it from the accessibility tree — a POS
 * barcode scanner presents as a hardware keyboard, so touch alone is not enough.
 *
 * The gate owns its own stacking container, so it layers correctly wherever the host places it.
 *
 * @param checkFailedContent supplying this slot is what opts into blocking when the check fails;
 *   `null` means fail-open. There is deliberately no separate flag — opting in and providing the
 *   UI are the same act, so no host can produce a transparent overlay that blocks everything and
 *   draws nothing.
 */
@Composable
fun RequiredUpdateGate(
    controller: RequiredUpdateController,
    requiredContent: @Composable (RequiredUpdateScope) -> Unit,
    checkFailedContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val state by controller.state.collectAsState()

    val required = state as? RequiredUpdateState.Required
    val blockedByCheckFailure = checkFailedContent != null && state is RequiredUpdateState.CheckFailed
    val blocked = required != null || blockedByCheckFailure

    val focusManager = LocalFocusManager.current
    LaunchedEffect(blocked) {
        if (blocked) focusManager.clearFocus(force = true)
    }

    Box(propagateMinConstraints = true) {
        Box(
            propagateMinConstraints = true,
            modifier = if (blocked) {
                Modifier
                    .focusProperties { canFocus = false }
                    .clearAndSetSemantics {}
            } else {
                Modifier
            }
        ) {
            content()
        }

        if (blocked) {
            BlockSystemBack()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent()
                        }
                    }
            ) {
                if (required != null) {
                    requiredContent(rememberRequiredUpdateScope(controller, required))
                } else {
                    checkFailedContent?.invoke()
                }
            }
        }
    }
}

@Composable
private fun rememberRequiredUpdateScope(
    controller: RequiredUpdateController,
    state: RequiredUpdateState.Required
): RequiredUpdateScope = remember(controller, state) {
    object : RequiredUpdateScope {
        override val update = state.update
        override val stage = state.stage
        override fun download() = controller.download()
        override fun install() = controller.install()
        override fun retry() = controller.retry()
        override fun requestInstallPermission() = controller.requestInstallPermission()
        override fun canInstallPackages() = controller.canInstallPackages()
    }
}
