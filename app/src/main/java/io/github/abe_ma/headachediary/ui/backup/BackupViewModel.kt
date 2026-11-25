package io.github.abe_ma.headachediary.ui.backup

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.abe_ma.headachediary.domain.BackupException
import io.github.abe_ma.headachediary.domain.BackupInspection
import io.github.abe_ma.headachediary.domain.BackupRepository
import io.github.abe_ma.headachediary.domain.InvalidReason
import io.github.abe_ma.headachediary.domain.RestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 実行中の処理 (進捗表示用)。 */
enum class BackupOperation { CREATING, INSPECTING, RESTORING }

/** 失敗の種類。FR-606 の 4 つ + ファイルの読み書きができなかった場合。 */
enum class BackupError {
    NOT_BACKUP, CORRUPT, TOO_LARGE, UNSUPPORTED_VERSION,
    /** 壊れてはいないが、内容 (発作の総件数・自由入力の薬の数) がこのアプリの想定の範囲外。「壊れている」とは区別して伝える。 */
    OUT_OF_RANGE,
    /** 保存先/選んだファイルを開けない、書き込みに失敗した、など。 */
    IO,
}

/** 画面の状態。ダイアログもここから導く (回転で失わない)。 */
sealed interface BackupPhase {
    data object Idle : BackupPhase
    /** 作成前の注意 (FR-601)。了承すると保存先の選択に進む。 */
    data object CreateWarning : BackupPhase
    data class Working(val operation: BackupOperation) : BackupPhase
    data object CreateDone : BackupPhase
    /** 検証済みの内容の確認 (FR-603)。「読み込む」を押すまで DB は変わらない。 */
    data class RestoreConfirm(val inspection: BackupInspection.Valid) : BackupPhase
    /**
     * [includedSettings]: 確認画面の時点で、ファイルに設定が含まれていたか (inspect の includesSettings)。
     * 含まれていたのに result.settingsApplied=false なら「設定を反映できなかった」と、「そもそも設定が無かった」を区別して表示する。
     */
    data class RestoreDone(val result: RestoreResult, val includedSettings: Boolean) : BackupPhase
    /**
     * [duringCreate]: 作成中の失敗か (読み込み系は「記録は変更されていません」を添える)。
     * [leftoverPossible]: 作成の失敗後に保存先の書きかけのファイルを削除できなかった (手で削除してもらう案内を出す)。
     */
    data class Failed(val error: BackupError, val duringCreate: Boolean, val leftoverPossible: Boolean = false) : BackupPhase
}

/** 画面が一度だけ実行する SAF の起動要求。 */
sealed interface BackupLaunch {
    /** ACTION_CREATE_DOCUMENT。既定のファイル名つき。 */
    data class CreateDocument(val suggestedName: String) : BackupLaunch
    /** ACTION_OPEN_DOCUMENT。 */
    data object OpenDocument : BackupLaunch
}

data class BackupUiState(
    val phase: BackupPhase = BackupPhase.Idle,
    val launch: BackupLaunch? = null,
) {
    val isWorking: Boolean get() = phase is BackupPhase.Working
}

@HiltViewModel
class BackupViewModel internal constructor(
    private val backup: BackupRepository,
    private val files: BackupFileAccess,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val zone: ZoneId,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    @Inject constructor(backup: BackupRepository, files: BackupFileAccess, savedStateHandle: SavedStateHandle) :
        this(backup, files, Dispatchers.IO, Clock.systemDefaultZone(), ZoneId.systemDefault(), savedStateHandle)

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 確認ダイアログの対象ファイル。確認後に同じファイルを読み直して反映する。プロセス死でも保持する。 */
    private var pendingRestoreUri: Uri?
        get() = savedStateHandle.get<String>(KEY_PENDING_URI)?.let { Uri.parse(it) }
        set(value) { savedStateHandle[KEY_PENDING_URI] = value?.toString() }

    // ---- 作成 (FR-601) -------------------------------------------------------

    /** 「バックアップを作成」: まず注意を出す。 */
    fun onCreateClicked() {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(phase = BackupPhase.CreateWarning) }
    }

    /** 注意を了承: 保存先の選択 (SAF) を起動する。 */
    fun onCreateWarningAccepted() {
        if (_uiState.value.phase != BackupPhase.CreateWarning) return
        _uiState.update {
            it.copy(phase = BackupPhase.Idle, launch = BackupLaunch.CreateDocument(defaultFileName()))
        }
    }

    /** `headache-diary-backup-yyyyMMdd-HHmm.json` (端末のローカル時刻)。 */
    fun defaultFileName(): String =
        "headache-diary-backup-" + FILE_NAME_TIME.format(clock.instant().atZone(zone)) + ".json"

    /** 保存先が選ばれた (null = キャンセル)。 */
    fun onCreateDocumentResult(uri: Uri?) {
        if (uri == null || _uiState.value.isWorking) return
        _uiState.update { it.copy(phase = BackupPhase.Working(BackupOperation.CREATING)) }
        viewModelScope.launch {
            val failure = try {
                // use {} の close() の失敗 (最後の書き込みが保存先に渡らない) も失敗として扱う。
                withContext(io) { files.openOutput(uri).use { backup.write(it) } }
                null
            } catch (e: CancellationException) {
                // 画面を離れるなどで中断された場合も、中途半端なファイルを残さない。
                deleteIncomplete(uri)
                throw e
            } catch (e: Exception) {
                e
            }
            val leftover = failure != null && !deleteIncomplete(uri)
            _uiState.update {
                it.copy(
                    phase = if (failure == null) BackupPhase.CreateDone
                    else BackupPhase.Failed(BackupError.IO, duringCreate = true, leftoverPossible = leftover),
                )
            }
        }
    }

    /**
     * 作成の失敗時に保存先のファイルを削除する (T-21d)。削除に失敗しても握りつぶして続行する。
     * 戻り値: 削除できたか (false なら書きかけのファイルが残っている可能性がある)。
     */
    private suspend fun deleteIncomplete(uri: Uri): Boolean = withContext(NonCancellable + io) {
        try {
            files.deleteQuietly(uri)
        } catch (_: Exception) {
            false
        }
    }

    // ---- 読み込み (FR-603〜607) ----------------------------------------------

    fun onOpenClicked() {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(launch = BackupLaunch.OpenDocument) }
    }

    /** ファイルが選ばれた (null = キャンセル)。DB を変えずに検証し、内容の確認に進む。 */
    fun onOpenDocumentResult(uri: Uri?) {
        if (uri == null || _uiState.value.isWorking) return
        _uiState.update { it.copy(phase = BackupPhase.Working(BackupOperation.INSPECTING)) }
        viewModelScope.launch {
            val result = runCatchingNonCancellation {
                withContext(io) { files.openInput(uri).use { backup.inspect(it) } }
            }
            val phase = result.fold(
                onSuccess = { inspection ->
                    when (inspection) {
                        is BackupInspection.Valid -> {
                            pendingRestoreUri = uri
                            BackupPhase.RestoreConfirm(inspection)
                        }
                        is BackupInspection.Invalid -> BackupPhase.Failed(inspection.reason.toError(), duringCreate = false)
                    }
                },
                onFailure = { BackupPhase.Failed(it.toError(), duringCreate = false) },
            )
            _uiState.update { it.copy(phase = phase) }
        }
    }

    /** 確認ダイアログで「読み込む」: ここで初めて反映する (restore は再検証し、失敗時は何も変更しない)。 */
    fun onRestoreConfirmed() {
        val uri = pendingRestoreUri
        val confirm = _uiState.value.phase as? BackupPhase.RestoreConfirm
        if (confirm == null || uri == null) return
        val includedSettings = confirm.inspection.includesSettings
        _uiState.update { it.copy(phase = BackupPhase.Working(BackupOperation.RESTORING)) }
        viewModelScope.launch {
            val result = runCatchingNonCancellation {
                withContext(io) { files.openInput(uri).use { backup.restore(it) } }
            }
            pendingRestoreUri = null
            val phase = result.fold(
                onSuccess = { BackupPhase.RestoreDone(it, includedSettings) },
                onFailure = { BackupPhase.Failed(it.toError(), duringCreate = false) },
            )
            _uiState.update { it.copy(phase = phase) }
        }
    }

    // ---- 共通 ---------------------------------------------------------------

    /** ダイアログを閉じる (確認ダイアログの「やめる」も含む。反映はしない)。 */
    fun onDismiss() {
        if (_uiState.value.isWorking) return
        pendingRestoreUri = null
        _uiState.update { it.copy(phase = BackupPhase.Idle) }
    }

    /** SAF を起動したら呼ぶ (同じ要求を再実行しない)。 */
    fun onLaunchConsumed() = _uiState.update { it.copy(launch = null) }

    private fun Throwable.toError(): BackupError = when (this) {
        is BackupException -> reason.toError()
        else -> BackupError.IO
    }

    private fun InvalidReason.toError(): BackupError = when (this) {
        InvalidReason.NOT_BACKUP -> BackupError.NOT_BACKUP
        InvalidReason.CORRUPT -> BackupError.CORRUPT
        InvalidReason.TOO_LARGE -> BackupError.TOO_LARGE
        InvalidReason.UNSUPPORTED_VERSION -> BackupError.UNSUPPORTED_VERSION
        InvalidReason.OUT_OF_RANGE -> BackupError.OUT_OF_RANGE
    }

    /** キャンセルは握りつぶさず再スローし、それ以外の例外は Result にする。 */
    private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private companion object {
        const val KEY_PENDING_URI = "pending_restore_uri"
        val FILE_NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}
