package com.vtcode.pos.version.presentation.update

import com.vtcode.pos.version.domain.entity.UpdateInfo
import com.vtcode.pos.version.domain.error.Result
import com.vtcode.pos.version.domain.error.VersionError
import com.vtcode.pos.version.domain.repository.IVersionRepository
import com.vtcode.pos.version.fake.FakeInstaller
import com.vtcode.pos.version.fake.FakeVersionRepository
import com.vtcode.pos.version.fake.testUpdate
import com.vtcode.pos.version.fake.testVersionChecker
import com.vtcode.pos.version.presentation.install.InstallOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Bọc [FakeVersionRepository] và chèn một điểm suspend thật (`yield()`) trước khi trả kết
 * quả `checkForUpdate`, để test có thể quan sát chắc chắn trạng thái [RequiredUpdateState.Checking]
 * trước khi nó bị `StateFlow` conflate mất bởi trạng thái kết thúc.
 */
private class YieldingVersionRepository(
    private val delegate: FakeVersionRepository
) : IVersionRepository {

    override suspend fun checkForUpdate(
        softwareCode: String,
        currentVersion: String
    ): Result<List<UpdateInfo>, VersionError> {
        yield()
        return delegate.checkForUpdate(softwareCode, currentVersion)
    }

    override suspend fun downloadFileWithProgress(
        fileId: String,
        destinationDir: Path,
        fileSystem: FileSystem,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Path, VersionError> = delegate.downloadFileWithProgress(fileId, destinationDir, fileSystem, onProgress)
}

/**
 * Bọc [FakeVersionRepository] và `delay()` một khoảng thời gian thật trước khi tải thật sự
 * chạm vào delegate, để test có một cửa sổ đáng tin cậy để can thiệp (ví dụ gọi [check])
 * trong khi lượt tải đang "mồ côi" trên [kotlinx.coroutines.Dispatchers.Default].
 */
private class DelayedDownloadVersionRepository(
    private val delegate: FakeVersionRepository,
    private val delayMs: Long = 200
) : IVersionRepository {

    override suspend fun checkForUpdate(
        softwareCode: String,
        currentVersion: String
    ): Result<List<UpdateInfo>, VersionError> = delegate.checkForUpdate(softwareCode, currentVersion)

    override suspend fun downloadFileWithProgress(
        fileId: String,
        destinationDir: Path,
        fileSystem: FileSystem,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Path, VersionError> {
        delay(delayMs)
        return delegate.downloadFileWithProgress(fileId, destinationDir, fileSystem, onProgress)
    }
}

/**
 * Tái hiện đúng lỗi mà B1 đã sửa ở tầng data (`VersionRemoteDataSource`): công việc thật bên
 * trong (`delay` + gọi `delegate`) chạy trong `withContext(NonCancellable)`, nên bị `check()`
 * khác huỷ giữa chừng cũng KHÔNG dừng lại — nó vẫn tự chạy tới cùng theo lịch ảo của chính nó,
 * độc lập với thời điểm bị huỷ, y hệt một cuộc gọi mạng thật đã gửi đi rồi thì huỷ Job phía
 * caller cũng không rút lại được request. Khi `withContext` trả kết quả về cho coroutine cha
 * đã bị huỷ, lần resume đó tự ném `CancellationException` (cancellation "nhanh" tiêu chuẩn của
 * kotlinx.coroutines) — và `catch (e: Exception)` ở đây nuốt luôn ngoại lệ đó, trả về
 * `Result.Error` như một lỗi mạng bình thường thay vì để nó truyền tiếp.
 *
 * Nhờ vậy, lượt `check()` cũ dù đã bị huỷ vẫn "sống sót" tới tận dòng `_state.value = ...` và
 * `scheduleRecheck()` ở `RequiredUpdateController`, cho bài test bên dưới kiểm chứng lớp
 * phòng vệ thứ hai của controller (`isActive` guard + huỷ `recheckJob` cũ trong
 * `scheduleRecheck()`) — lớp phòng vệ đúng ra không bao giờ cần tới nếu tầng data luôn hợp
 * tác đúng, nhưng vẫn phải đúng ngay cả khi không.
 *
 * [delaysMs] cho một độ trễ riêng theo THỨ TỰ VÀO (không phải thứ tự hoàn tất) của mỗi lần gọi
 * `checkForUpdate` — chỉ số [callIndex] tăng ngay khi lệnh gọi bắt đầu (trước `delay`), nên gán
 * đúng cấu hình cho "chu kỳ cũ" (vào trước) so với "chu kỳ mới" (vào sau) dù độ trễ khác nhau
 * khiến chúng hoàn tất không theo thứ tự đó. [resultsOverride] cho một `Result` khác nhau theo
 * cùng chỉ số đó, để phân biệt danh tính lỗi giữa hai chu kỳ; chỉ số vượt quá kích thước danh
 * sách sẽ rơi về độ trễ/`Result` cuối cùng và [delegate] tương ứng.
 */
private class DelayedCheckVersionRepository(
    private val delegate: FakeVersionRepository,
    private val delaysMs: List<Long> = listOf(1_000L),
    private val resultsOverride: List<Result<List<UpdateInfo>, VersionError>?> = emptyList()
) : IVersionRepository {

    private var callIndex = 0

    override suspend fun checkForUpdate(
        softwareCode: String,
        currentVersion: String
    ): Result<List<UpdateInfo>, VersionError> {
        val index = callIndex++
        val delayMs = delaysMs.getOrElse(index) { delaysMs.last() }
        return try {
            withContext(NonCancellable) {
                delay(delayMs)
                val delegateResult = delegate.checkForUpdate(softwareCode, currentVersion)
                resultsOverride.getOrNull(index) ?: delegateResult
            }
        } catch (e: Exception) {
            Result.Error(VersionError.NetworkError(e.message ?: "cancelled"))
        }
    }

    override suspend fun downloadFileWithProgress(
        fileId: String,
        destinationDir: Path,
        fileSystem: FileSystem,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<Path, VersionError> = delegate.downloadFileWithProgress(fileId, destinationDir, fileSystem, onProgress)
}

class RequiredUpdateControllerTest {

    private val cacheDir = "/cache".toPath()

    private fun controller(
        scope: CoroutineScope,
        repository: FakeVersionRepository,
        installer: FakeInstaller = FakeInstaller(),
        fileSystem: FakeFileSystem = FakeFileSystem(),
        autoInstall: Boolean = true,
        recheckIntervalMs: Long? = 15 * 60 * 1000L,
        installOutcomes: Flow<InstallOutcome> = MutableSharedFlow()
    ): RequiredUpdateController {
        fileSystem.createDirectories(cacheDir)
        return RequiredUpdateController(
            scope = scope,
            versionChecker = testVersionChecker(repository, installer),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem,
            autoInstall = autoInstall,
            recheckIntervalMs = recheckIntervalMs,
            installOutcomes = installOutcomes
        )
    }

    private suspend fun RequiredUpdateController.awaitState(
        predicate: (RequiredUpdateState) -> Boolean
    ): RequiredUpdateState = withTimeout(5_000) { state.first(predicate) }

    /**
     * `download()`/`install()` chạy `DownloadUpdateUseCase`/`installUpdate` bên trong
     * `withContext(Dispatchers.Default)` — công việc thật, không nằm trên `TestDispatcher` của
     * `runTest`. Timeout ảo của [awaitState] có thể fire ngay lập tức trước khi coroutine đó kịp
     * trả kết quả, nên các test download/install chờ bằng thời gian thật thay vì thời gian ảo.
     */
    private suspend fun RequiredUpdateController.awaitStateAfterDownloadOrInstall(
        predicate: (RequiredUpdateState) -> Boolean
    ): RequiredUpdateState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(5_000) { state.first(predicate) }
    }

    /**
     * `install()` chuyển trạng thái sang [UpdateStage.Installing] *trước khi* coroutine thật
     * gọi `installer.install(file)` trên [Dispatchers.Default], nên riêng việc thấy trạng thái
     * đó không đảm bảo installer đã được gọi. Poll bằng thời gian thật cho tới khi side effect
     * thật sự xảy ra.
     */
    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5_000) {
                while (!predicate()) delay(1)
            }
        }
    }

    /**
     * Cửa sổ chờ cho khẳng định phủ định ("trạng thái này KHÔNG đổi"). Khác với các wait dương
     * tính ở trên (chờ tới khi điều kiện đúng, kết thúc ngay khi thoả), một wait âm tính không
     * bao giờ tự kết thúc sớm nếu điều kiện không xảy ra — nên nó phải dùng cửa sổ ngắn, có tên
     * riêng, thay vì mượn timeout 5000ms vốn dành cho việc chờ một kết quả sẽ tới.
     */
    private val STALE_OUTCOME_WINDOW_MS = 300L

    /**
     * Poll [predicate] bằng thời gian thật trong tối đa [timeoutMs] và trả về liệu nó có bao giờ
     * đúng hay không, thay vì ném lỗi khi hết giờ — phù hợp cho khẳng định phủ định nơi hết giờ
     * chính là kết quả mong đợi.
     */
    private suspend fun awaitConditionOrTimeout(
        timeoutMs: Long = STALE_OUTCOME_WINDOW_MS,
        predicate: () -> Boolean
    ): Boolean = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeoutOrNull(timeoutMs) {
            while (!predicate()) delay(1)
        } != null
    }

    @Test
    fun no_required_update_yields_NotRequired() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate(isRequired = false)))
        val sut = controller(backgroundScope, repository)

        assertEquals(RequiredUpdateState.NotRequired, sut.awaitState { it is RequiredUpdateState.NotRequired })
    }

    @Test
    fun empty_update_list_yields_NotRequired() = runTest(UnconfinedTestDispatcher()) {
        val sut = controller(backgroundScope, FakeVersionRepository(updates = emptyList()))

        assertEquals(RequiredUpdateState.NotRequired, sut.awaitState { it is RequiredUpdateState.NotRequired })
    }

    @Test
    fun required_update_not_downloadable_yields_NotRequired() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(
            updates = listOf(testUpdate(isRequired = true, isDownloadable = false))
        )
        val sut = controller(backgroundScope, repository)

        assertEquals(RequiredUpdateState.NotRequired, sut.awaitState { it is RequiredUpdateState.NotRequired })
    }

    @Test
    fun required_update_yields_Required_Ready() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate(version = "2.0.0")))
        val sut = controller(backgroundScope, repository)

        val state = sut.awaitState { it is RequiredUpdateState.Required }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals("2.0.0", state.update.version)
        assertEquals(UpdateStage.Ready, state.stage)
    }

    @Test
    fun check_error_yields_CheckFailed() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository)

        val state = sut.awaitState { it is RequiredUpdateState.CheckFailed }
        assertIs<RequiredUpdateState.CheckFailed>(state)
        assertEquals(VersionError.TimeoutError, state.error)
    }

    @Test
    fun retry_after_check_failure_checks_again() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository)
        sut.awaitState { it is RequiredUpdateState.CheckFailed }

        repository.checkError = null
        repository.updates = listOf(testUpdate())
        sut.retry()

        assertIs<RequiredUpdateState.Required>(sut.awaitState { it is RequiredUpdateState.Required })
        assertTrue(repository.checkCount >= 2)
    }

    @Test
    fun check_emits_Checking_before_terminal_state() = runTest(UnconfinedTestDispatcher()) {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = RequiredUpdateController(
            scope = backgroundScope,
            versionChecker = testVersionChecker(YieldingVersionRepository(repository), FakeInstaller()),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem
        )
        sut.awaitState { it is RequiredUpdateState.CheckFailed }

        repository.checkError = null
        repository.updates = listOf(testUpdate())

        val recordedStates = mutableListOf<RequiredUpdateState>()
        backgroundScope.launch {
            sut.state.collect { recordedStates.add(it) }
        }

        sut.retry()
        withTimeout(5_000) {
            while (recordedStates.none { it is RequiredUpdateState.Required }) {
                yield()
            }
        }

        val retryIndex = recordedStates.indexOfFirst { it is RequiredUpdateState.CheckFailed }
        val checkingIndex = recordedStates.indexOfLast { it is RequiredUpdateState.Checking }
        val requiredIndex = recordedStates.indexOfFirst { it is RequiredUpdateState.Required }

        assertTrue(checkingIndex > retryIndex, "Checking phải xuất hiện sau CheckFailed: $recordedStates")
        assertTrue(checkingIndex < requiredIndex, "Checking phải xuất hiện trước trạng thái Required: $recordedStates")
    }

    /**
     * `check()` không chỉ chạy một lần: sau khi ổn định ở `CheckFailed`, controller tự đặt
     * lịch gọi lại sau `recheckIntervalMs`. `delay()` bên trong chạy trên `controllerScope`,
     * vốn thừa hưởng dispatcher ảo của `backgroundScope` (cùng `TestCoroutineScheduler` với
     * `runTest`), nên `advanceTimeBy` đủ để kích hoạt nó — không cần sleep thời gian thật.
     */
    @Test
    fun recheck_after_check_failure_picks_up_update_that_appeared() = runTest(UnconfinedTestDispatcher()) {
        val recheckInterval = 15 * 60 * 1000L
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository, recheckIntervalMs = recheckInterval)
        sut.awaitState { it is RequiredUpdateState.CheckFailed }
        assertEquals(1, repository.checkCount)

        repository.checkError = null
        repository.updates = listOf(testUpdate())

        advanceTimeBy(recheckInterval + 1)
        runCurrent()

        val state = sut.awaitState { it is RequiredUpdateState.Required }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals(2, repository.checkCount)
    }

    /**
     * `check()` từ `init()` còn dở dang (mắc kẹt tại `delay()` bên trong
     * [DelayedCheckVersionRepository]) khi ta gọi `check()` lần nữa — mô phỏng "gọi check()
     * trong khi một lượt check khác đang chạy". Lượt cũ (A, từ `init()`) dùng độ trễ DÀI HƠN
     * lượt mới (B, từ lệnh gọi `check()` thủ công) — [staleCheckDelayMs] > [freshCheckDelayMs]
     * — để B ổn định TRƯỚC rồi A mới cố ghi đè lên sau, đúng thứ tự nguy hiểm thật sự: một chu
     * kỳ cũ trả lời trễ sau khi chu kỳ mới đã chốt trạng thái. A và B cũng trả về hai
     * [VersionError] khác nhau (qua `resultsOverride`) để có thể khẳng định trạng thái sống
     * sót bằng chính danh tính lỗi, không chỉ bằng loại (type) của [RequiredUpdateState].
     *
     * Lượt cũ dù bị huỷ vẫn tự chạy xong theo lịch ảo riêng của nó (xem KDoc của
     * [DelayedCheckVersionRepository]) và có thể tự đặt thêm một timer hỏi lại mồ côi nếu
     * `check()`/`scheduleRecheck()` không tự phòng vệ (`isActive` guard, huỷ `recheckJob` cũ
     * trước khi gán job mới) — hai timer sống song song sẽ khiến `checkCount` tăng nhanh hơn
     * tốc độ một-timer-mỗi-`recheckIntervalMs` mong đợi.
     */
    @Test
    fun check_call_while_check_in_flight_does_not_stack_recheck_timers() = runTest(UnconfinedTestDispatcher()) {
        val recheckInterval = 15 * 60 * 1000L
        val staleCheckDelayMs = 1_000L
        val freshCheckDelayMs = 200L
        val staleError = VersionError.NetworkError("stale-cycle-must-not-win")
        val freshError = VersionError.TimeoutError
        val repository = FakeVersionRepository(checkError = freshError)
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        val sut = RequiredUpdateController(
            scope = backgroundScope,
            versionChecker = testVersionChecker(
                DelayedCheckVersionRepository(
                    repository,
                    delaysMs = listOf(staleCheckDelayMs, freshCheckDelayMs),
                    resultsOverride = listOf(Result.Error(staleError), Result.Error(freshError))
                ),
                FakeInstaller()
            ),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem,
            recheckIntervalMs = recheckInterval
        )

        // check() từ init() (chu kỳ A, "cũ") còn "treo" trong delay() ảo bên trong
        // withContext(NonCancellable), chưa chạm tới repository thật — đây chính là "gọi
        // check() trong khi một lượt check khác đang chạy".
        assertEquals(0, repository.checkCount)
        sut.check()
        assertEquals(0, repository.checkCount)

        // Chỉ tua đủ để chu kỳ B ("mới", độ trễ ngắn hơn) hoàn tất và chốt trạng thái trước.
        advanceTimeBy(freshCheckDelayMs + 1)
        runCurrent()

        assertEquals(1, repository.checkCount)
        assertEquals(
            freshError,
            (sut.state.value as? RequiredUpdateState.CheckFailed)?.error,
            "Chu kỳ mới (B) phải chốt trạng thái trước"
        )

        // Giờ mới tua tiếp để chu kỳ A ("cũ") — đã bị huỷ khi B gọi check() — hoàn tất trễ.
        // isActive guard phải chặn nó ghi đè lên trạng thái B vừa chốt.
        advanceTimeBy(staleCheckDelayMs - freshCheckDelayMs)
        runCurrent()

        assertEquals(2, repository.checkCount, "Chu kỳ cũ (A) vẫn chạm tới repository do NonCancellable")
        assertEquals(
            freshError,
            (sut.state.value as? RequiredUpdateState.CheckFailed)?.error,
            "Chu kỳ cũ (A) hoàn tất trễ không được ghi đè lên trạng thái mà chu kỳ mới (B) đã chốt"
        )

        // Mỗi chu kỳ check() thật kể cả các lần hỏi lại (từ chỉ số 2 trở đi, dùng lại độ trễ
        // và repository thật do resultsOverride không còn phần tử) đều đi qua freshCheckDelayMs
        // (độ trễ cuối trong danh sách), nên đệm thêm dư dả để chắc chắn đúng hai lượt hỏi lại
        // hoàn tất trong cửa sổ đã tua qua.
        advanceTimeBy(recheckInterval * 2 + 5_000)
        runCurrent()

        assertEquals(
            4,
            repository.checkCount,
            "Chỉ đúng một timer hỏi lại được sống sót sau chu kỳ check() chồng lấn: " +
                "2 lần check ban đầu (lượt bị huỷ vẫn hoàn tất do NonCancellable + lượt mới) " +
                "+ đúng 2 lần hỏi lại trong khoảng thời gian đã trôi qua"
        )
    }

    /**
     * Pin đúng mối nguy thật sự (không chỉ hiện tượng "hai timer"): chu kỳ mới (B) chốt trạng
     * thái ở [RequiredUpdateState.Required] — nghĩa là gate đang CHẶN UI — rồi chu kỳ cũ (A,
     * từ trước khi B chạy, hoàn tất trễ) không được phép ghi đè nó về
     * [RequiredUpdateState.CheckFailed]. Nếu `isActive` guard trong `check()` bị gỡ, A sẽ ghi
     * đè `_state` bằng `CheckFailed`, vô tình MỞ gate cho một máy lẽ ra phải bị chặn cập nhật.
     */
    @Test
    fun stale_check_completing_after_Required_settles_cannot_reopen_gate() = runTest(UnconfinedTestDispatcher()) {
        val staleCheckDelayMs = 1_000L
        val freshCheckDelayMs = 200L
        val staleError = VersionError.NetworkError("stale-cycle-must-not-reopen-gate")
        val requiredUpdate = testUpdate(version = "9.9.9")
        val repository = FakeVersionRepository()
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        val sut = RequiredUpdateController(
            scope = backgroundScope,
            versionChecker = testVersionChecker(
                DelayedCheckVersionRepository(
                    repository,
                    delaysMs = listOf(staleCheckDelayMs, freshCheckDelayMs),
                    resultsOverride = listOf(
                        Result.Error(staleError),
                        Result.Success(listOf(requiredUpdate))
                    )
                ),
                FakeInstaller()
            ),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem,
            recheckIntervalMs = 15 * 60 * 1000L
        )

        // Chu kỳ A (từ init()) còn treo; gọi check() thủ công để bắt đầu chu kỳ B chồng lấn.
        sut.check()

        // B (độ trễ ngắn hơn) hoàn tất trước, chốt gate ở Required.
        advanceTimeBy(freshCheckDelayMs + 1)
        runCurrent()

        val required = sut.state.value
        assertIs<RequiredUpdateState.Required>(required)
        assertEquals("9.9.9", required.update.version)
        assertEquals(UpdateStage.Ready, required.stage)

        // A hoàn tất trễ; isActive guard phải chặn nó ghi đè Required về CheckFailed.
        advanceTimeBy(staleCheckDelayMs - freshCheckDelayMs)
        runCurrent()

        assertEquals(2, repository.checkCount, "Cả hai chu kỳ đều phải chạm tới repository")
        val stateAfterStaleCompletes = sut.state.value
        assertIs<RequiredUpdateState.Required>(
            stateAfterStaleCompletes,
            "Chu kỳ cũ hoàn tất trễ không được mở lại gate: $stateAfterStaleCompletes"
        )
        assertEquals("9.9.9", stateAfterStaleCompletes.update.version)
        assertEquals(UpdateStage.Ready, stateAfterStaleCompletes.stage)
    }

    /** [RequiredUpdateController.close] phải dừng luôn vòng hỏi lại định kỳ, không chỉ chu kỳ hiện tại. */
    @Test
    fun close_stops_recheck_loop() = runTest(UnconfinedTestDispatcher()) {
        val recheckInterval = 15 * 60 * 1000L
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository, recheckIntervalMs = recheckInterval)
        sut.awaitState { it is RequiredUpdateState.CheckFailed }
        assertEquals(1, repository.checkCount)

        sut.close()

        advanceTimeBy(recheckInterval * 4)
        runCurrent()

        assertEquals(1, repository.checkCount, "close() phải huỷ recheckJob đang chờ, không cho nó tiếp tục hỏi lại")
    }

    @Test
    fun recheckIntervalMs_null_performs_exactly_one_check() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(checkError = VersionError.TimeoutError)
        val sut = controller(backgroundScope, repository, recheckIntervalMs = null)
        sut.awaitState { it is RequiredUpdateState.CheckFailed }
        assertEquals(1, repository.checkCount)

        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()

        assertEquals(1, repository.checkCount)
        assertIs<RequiredUpdateState.CheckFailed>(sut.state.value)
    }

    @Test
    fun no_recheck_fires_while_state_is_Required() = runTest(UnconfinedTestDispatcher()) {
        val recheckInterval = 15 * 60 * 1000L
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val sut = controller(backgroundScope, repository, recheckIntervalMs = recheckInterval)
        sut.awaitState { it is RequiredUpdateState.Required }
        assertEquals(1, repository.checkCount)

        advanceTimeBy(recheckInterval * 4)
        runCurrent()

        assertEquals(1, repository.checkCount)
        assertIs<RequiredUpdateState.Required>(sut.state.value)
    }

    @Test
    fun download_reaches_Downloaded_and_auto_installs() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller()
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val installing = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }
        assertIs<RequiredUpdateState.Required>(installing)
        awaitCondition { installer.installed.isNotEmpty() }
        assertEquals(1, installer.installed.size)
        assertEquals("update_file-1", installer.installed.first().name)
    }

    @Test
    fun download_stops_at_Downloaded_when_autoInstall_disabled() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller()
        val sut = controller(backgroundScope, repository, installer, autoInstall = false)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val state = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Downloaded
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertTrue(installer.installed.isEmpty())

        sut.install()
        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }
        awaitCondition { installer.installed.isNotEmpty() }
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun download_error_yields_Failed_stage() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(
            updates = listOf(testUpdate()),
            downloadError = VersionError.NetworkError("offline")
        )
        val sut = controller(backgroundScope, repository)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val state = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertTrue((state.stage as UpdateStage.Failed).message.isNotBlank())
    }

    @Test
    fun corrupt_download_yields_Failed_stage() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(
            updates = listOf(testUpdate()),
            writeCorruptFile = true
        )
        val installer = FakeInstaller()
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun retry_after_download_failure_downloads_again() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(
            updates = listOf(testUpdate()),
            downloadError = VersionError.NetworkError("offline")
        )
        val sut = controller(backgroundScope, repository)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }

        repository.downloadError = null
        sut.retry()

        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }
        assertEquals(2, repository.downloadCount)
    }

    @Test
    fun second_download_call_while_downloading_is_ignored() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val sut = controller(backgroundScope, repository)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()
        sut.download()

        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing
        }
        assertEquals(1, repository.downloadCount)
    }

    @Test
    fun second_install_call_while_installing_is_ignored() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller()
        val sut = controller(backgroundScope, repository, installer, autoInstall = false)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Downloaded
        }

        sut.install()
        sut.install()

        val installedTwice = awaitConditionOrTimeout { installer.installed.size > 1 }
        assertTrue(!installedTwice, "Lệnh install() thứ hai trong lúc đang cài không được thực thi")
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun check_mid_download_abandons_orphaned_download() = runTest(UnconfinedTestDispatcher()) {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(cacheDir)
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller()
        val sut = RequiredUpdateController(
            scope = backgroundScope,
            versionChecker = testVersionChecker(DelayedDownloadVersionRepository(repository), installer),
            currentVersion = "1.0.0",
            cacheDir = cacheDir,
            softwareCode = "ipos",
            fileSystem = fileSystem
        )
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()
        sut.awaitState {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Downloading
        }

        sut.check()

        assertIs<RequiredUpdateState.Required>(
            sut.awaitState { it is RequiredUpdateState.Required && it.stage == UpdateStage.Ready }
        )

        val becameInstalling = awaitConditionOrTimeout(timeoutMs = 500) {
            val state = sut.state.value
            state is RequiredUpdateState.Required && state.stage is UpdateStage.Installing
        }
        assertTrue(!becameInstalling, "check() giữa chừng tải không được để lượt tải mồ côi tự động cài đặt")
        assertTrue(installer.installed.isEmpty(), "Installer không được gọi từ lượt tải đã bị check() huỷ bỏ")
        assertEquals(0, repository.downloadCount, "Lượt tải mồ côi phải bị huỷ trước khi chạm tới repository")
    }

    @Test
    fun install_permission_error_yields_InstallPermissionRequired() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallPermissionRequired))
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage == UpdateStage.InstallPermissionRequired
        }
        assertEquals(0, installer.permissionRequests)

        assertTrue(sut.requestInstallPermission())
        assertEquals(1, installer.permissionRequests)
    }

    @Test
    fun retry_after_permission_required_installs_again() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallPermissionRequired))
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage == UpdateStage.InstallPermissionRequired
        }

        installer.result = Result.Success(Unit)
        sut.retry()

        sut.awaitStateAfterDownloadOrInstall { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }
        awaitCondition { installer.installed.size == 2 }
        assertEquals(2, installer.installed.size)
    }

    @Test
    fun install_error_yields_Failed_stage() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallError("signature mismatch")))
        val sut = controller(backgroundScope, repository, installer)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()

        val state = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertTrue((state.stage as UpdateStage.Failed).message.contains("signature mismatch"))
    }

    @Test
    fun platform_install_failure_yields_Failed_stage() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val outcomes = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 1)
        val sut = controller(backgroundScope, repository, installOutcomes = outcomes)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitStateAfterDownloadOrInstall { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }

        outcomes.emit(InstallOutcome.Failure("Người dùng đã huỷ"))

        val state = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals("Người dùng đã huỷ", (state.stage as UpdateStage.Failed).message)
    }

    @Test
    fun platform_install_failure_without_message_uses_default_text() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val outcomes = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 1)
        val sut = controller(backgroundScope, repository, installOutcomes = outcomes)
        sut.awaitState { it is RequiredUpdateState.Required }
        sut.download()
        sut.awaitStateAfterDownloadOrInstall { it is RequiredUpdateState.Required && it.stage is UpdateStage.Installing }

        outcomes.emit(InstallOutcome.Failure(null))

        val state = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(state)
        assertEquals("Cài đặt thất bại", (state.stage as UpdateStage.Failed).message)
    }

    /**
     * `InstallOutcome` không mang token liên kết với một lần gọi `install()` cụ thể, nên một
     * outcome trễ từ lượt cài trước có thể đến sau khi controller đã chuyển sang chu kỳ khác.
     * Test này đưa controller tới [UpdateStage.Failed] (không phải qua platform outcome, mà
     * qua lỗi cài đặt cục bộ) rồi bắn một `InstallOutcome.Failure` trễ; guard trong
     * `observeInstallOutcomes()` phải bỏ qua nó vì stage hiện tại không phải [UpdateStage.Installing].
     */
    @Test
    fun stale_install_failure_outside_installing_stage_is_ignored() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeVersionRepository(updates = listOf(testUpdate()))
        val installer = FakeInstaller(result = Result.Error(VersionError.InstallError("signature mismatch")))
        val outcomes = MutableSharedFlow<InstallOutcome>(extraBufferCapacity = 1)
        val sut = controller(backgroundScope, repository, installer, installOutcomes = outcomes)
        sut.awaitState { it is RequiredUpdateState.Required }

        sut.download()
        val failedState = sut.awaitStateAfterDownloadOrInstall {
            it is RequiredUpdateState.Required && it.stage is UpdateStage.Failed
        }
        assertIs<RequiredUpdateState.Required>(failedState)
        val originalMessage = (failedState.stage as UpdateStage.Failed).message

        outcomes.emit(InstallOutcome.Failure("Outcome trễ từ lượt cài trước"))

        // Chờ thật một khoảng thời gian để outcome trễ có cơ hội tới collector trước khi assert.
        val staleOverwrote = awaitConditionOrTimeout {
            val state = sut.state.value
            state is RequiredUpdateState.Required &&
                (state.stage as? UpdateStage.Failed)?.message == "Outcome trễ từ lượt cài trước"
        }

        assertTrue(!staleOverwrote, "Outcome trễ ngoài Installing không được ghi đè stage")
        val current = sut.state.value
        assertIs<RequiredUpdateState.Required>(current)
        assertEquals(UpdateStage.Failed(originalMessage), current.stage)
    }
}
