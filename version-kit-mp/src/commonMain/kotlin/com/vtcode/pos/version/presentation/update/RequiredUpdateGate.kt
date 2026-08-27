package com.vtcode.pos.version.presentation.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.presentation.VersionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path

/**
 * Tạo và ghi nhớ một [RequiredUpdateController]; huỷ scope của nó khi composable rời khỏi cây.
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

/** Những gì UI của host nhận được để vẽ màn hình bắt buộc cập nhật. */
interface RequiredUpdateScope {
    val update: UpdateInfo
    val stage: UpdateStage
    fun download()
    fun install()
    fun retry()
    fun requestInstallPermission(): Boolean
}

/**
 * Vẽ [content], rồi phủ lên trên UI của host khi có bản bắt buộc phải cài.
 *
 * Overlay nuốt toàn bộ chạm và chặn nút back, nên [content] không thao tác được cho tới
 * khi cài xong. Kit không vẽ gì bên trong overlay — [requiredContent] là của host.
 *
 * @param blockOnCheckFailure chặn luôn khi không hỏi được server. Mặc định `false`
 *   (fail-open): máy POS mất mạng vẫn vào được app.
 * @param checkFailedContent UI hiện khi [blockOnCheckFailure] bật và không hỏi được
 *   server. Bỏ qua khi `blockOnCheckFailure = false`.
 */
@Composable
fun RequiredUpdateGate(
    controller: RequiredUpdateController,
    blockOnCheckFailure: Boolean = false,
    requiredContent: @Composable (RequiredUpdateScope) -> Unit,
    checkFailedContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    val state by controller.state.collectAsState()

    content()

    val required = state as? RequiredUpdateState.Required
    val blockedByCheckFailure = blockOnCheckFailure && state is RequiredUpdateState.CheckFailed
    if (required == null && !blockedByCheckFailure) return

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
            checkFailedContent()
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
    }
}
