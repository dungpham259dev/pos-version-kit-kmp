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
 * Khi bị chặn, overlay còn thu hồi focus khỏi [content], không cho nó nhận focus trở
 * lại và gỡ nó khỏi cây accessibility, để bàn phím cứng (kể cả máy quét mã vạch giả
 * lập bàn phím) và trình đọc màn hình không thể chạm tới UI phía sau overlay.
 *
 * @param checkFailedContent UI hiện khi không hỏi được server. Truyền slot này để chặn luôn
 *   trong trường hợp đó; để `null` (mặc định) nghĩa là fail-open — máy POS mất mạng vẫn vào
 *   được app. Không có tham số bật/tắt riêng: việc chặn khi kiểm tra thất bại và việc cung
 *   cấp UI cho nó là cùng một hành động, nên host bật chặn mà quên vẽ UI là điều không thể
 *   xảy ra — sẽ không còn cách nào tạo ra một overlay trong suốt nuốt hết thao tác mà không
 *   vẽ gì, khoá cứng một máy POS không có lối thoát nào khác ngoài force-stop.
 *
 * Gate tự sở hữu container xếp lớp (stacking) của riêng nó, nên overlay luôn phủ đúng
 * lên trên [content] dù host đặt gate ở đâu trong cây (kể cả bên trong `Column`, `Row`
 * hay slot nội dung của `Scaffold`), không phụ thuộc vào parent của host có xếp lớp hay không.
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
    }
}
