package io.github.abe_ma.headachediary.ui.export

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.AttackRepository
import io.github.abe_ma.headachediary.domain.ExportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 書き出す期間の選び方 (FR-400/401)。 */
enum class ExportPreset { THIS_MONTH, LAST_3_MONTHS, LAST_YEAR, ALL, CUSTOM }

/** 出力形式。 */
enum class ExportKind { CSV, PDF }

sealed interface ExportStatus {
    data object Idle : ExportStatus
    data object Generating : ExportStatus
    data object Failed : ExportStatus
    /** 生成済み。共有を促す。 */
    data class Done(val uri: Uri, val kind: ExportKind) : ExportStatus
}

/** 共有シートを開く要求 (一度だけ消費する)。 */
data class ShareRequest(val uri: Uri, val kind: ExportKind)

data class ExportUiState(
    val preset: ExportPreset = ExportPreset.THIS_MONTH,
    val customStart: LocalDate,
    val customEnd: LocalDate,
    val kind: ExportKind = ExportKind.PDF,
    /** 実際に書き出す期間 (両端を含む日付)。 */
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
    /** 期間内の発作件数。 */
    val attackCount: Int = 0,
    /** 最初の発作の日。期間指定の下限に使う (発作が無ければ null)。 */
    val earliestDate: LocalDate? = null,
    val status: ExportStatus = ExportStatus.Idle,
    val share: ShareRequest? = null,
) {
    /** 期間指定で開始日が終了日より後なら不正。 */
    val isRangeValid: Boolean get() = !rangeStart.isAfter(rangeEnd)
    val canExport: Boolean get() = isRangeValid && status != ExportStatus.Generating
}

@HiltViewModel
class ExportViewModel internal constructor(
    private val attackRepository: AttackRepository,
    private val exportRepository: ExportRepository,
    private val clock: Clock,
    private val zone: ZoneId,
) : ViewModel() {
    @Inject constructor(attackRepository: AttackRepository, exportRepository: ExportRepository) :
        this(attackRepository, exportRepository, Clock.systemDefaultZone(), ZoneId.systemDefault())

    private val today: LocalDate get() = LocalDate.now(clock.withZone(zone))

    /**
     * 今日の日付の変化。画面を開いたまま日付が変わったとき「今月」「過去3か月」などの端を取り直すため、
     * 購読されている間だけ定期的に確認する (ViewModel の寿命で回し続けない)。
     */
    private val todayFlow = flow {
        while (true) {
            emit(today)
            delay(DAY_CHECK_INTERVAL_MILLIS)
        }
    }.distinctUntilChanged()

    private data class Selection(val preset: ExportPreset, val customStart: LocalDate, val customEnd: LocalDate, val kind: ExportKind)
    private data class Progress(val status: ExportStatus = ExportStatus.Idle, val share: ShareRequest? = null)

    private val selection = MutableStateFlow(
        Selection(ExportPreset.THIS_MONTH, today.withDayOfMonth(1), today, ExportKind.PDF),
    )
    private val progress = MutableStateFlow(Progress())

    val uiState: StateFlow<ExportUiState> = combine(
        selection, progress, attackRepository.observeAttacks(), todayFlow,
    ) { sel, prog, all, t ->
        val (start, end) = resolveRange(sel, all, t)
        val inRange = all.count { it.startedAt in start.toInstantRange(end) }
        ExportUiState(
            preset = sel.preset, customStart = sel.customStart, customEnd = sel.customEnd, kind = sel.kind,
            rangeStart = start, rangeEnd = end, attackCount = inRange,
            earliestDate = all.minOfOrNull { it.startedAt }?.atZone(zone)?.toLocalDate(),
            status = prog.status, share = prog.share,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        ExportUiState(customStart = today.withDayOfMonth(1), customEnd = today, rangeStart = today.withDayOfMonth(1), rangeEnd = today),
    )

    private fun resolveRange(sel: Selection, all: List<Attack>, t: LocalDate): Pair<LocalDate, LocalDate> {
        return when (sel.preset) {
            ExportPreset.THIS_MONTH -> t.withDayOfMonth(1) to t
            ExportPreset.LAST_3_MONTHS -> t.minusMonths(3).plusDays(1) to t
            ExportPreset.LAST_YEAR -> t.minusYears(1).plusDays(1) to t
            ExportPreset.ALL -> (all.minOfOrNull { it.startedAt }?.atZone(zone)?.toLocalDate() ?: t) to t
            ExportPreset.CUSTOM -> sel.customStart to sel.customEnd
        }
    }

    /** 両端を含む日付範囲 → Instant 範囲 (終了日の 23:59:59.999 まで)。 */
    private fun LocalDate.toInstantRange(end: LocalDate): ClosedRange<Instant> =
        atStartOfDay(zone).toInstant()..end.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)

    // 期間や形式を変えたら前回の結果 (「ファイルを作成しました」「もう一度共有」) は無効にする。
    // 前の条件のファイルを共有してしまわないため。生成中に変えた場合は、完了時に結果を採用しない (export() 参照)。
    fun setPreset(p: ExportPreset) = changeSelection { it.copy(preset = p) }
    fun setKind(k: ExportKind) = changeSelection { it.copy(kind = k) }
    fun setCustomStart(d: LocalDate) = changeSelection { it.copy(preset = ExportPreset.CUSTOM, customStart = d) }
    fun setCustomEnd(d: LocalDate) = changeSelection { it.copy(preset = ExportPreset.CUSTOM, customEnd = minOf(d, today)) }

    private fun changeSelection(transform: (Selection) -> Selection) {
        selection.update(transform)
        if (progress.value.status != ExportStatus.Generating) clearResult()
    }

    /** FR-400/401/402: 生成して共有を促す。失敗しても記録機能は妨げない。 */
    fun export() {
        val state = uiState.value
        if (!state.canExport) return
        val range = state.rangeStart.toInstantRange(state.rangeEnd)
        val kind = state.kind
        val selectionAtStart = selection.value
        progress.update { Progress(ExportStatus.Generating) }
        viewModelScope.launch {
            try {
                val uri = when (kind) {
                    ExportKind.CSV -> exportRepository.exportCsv(range)
                    ExportKind.PDF -> exportRepository.exportPdf(range)
                }
                // 生成中に期間や形式が変えられていたら、このファイルは今の条件のものではないので共有しない。
                progress.update {
                    if (selection.value == selectionAtStart) Progress(ExportStatus.Done(uri, kind), ShareRequest(uri, kind))
                    else Progress()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.update { if (selection.value == selectionAtStart) Progress(ExportStatus.Failed) else Progress() }
            }
        }
    }

    /** 共有シートを開いたあとに呼ぶ (同じ要求を再度開かない)。 */
    fun onShareLaunched() = progress.update { it.copy(share = null) }

    /** 生成済みのファイルをもう一度共有する。 */
    fun shareAgain() {
        val done = progress.value.status as? ExportStatus.Done ?: return
        progress.update { it.copy(share = ShareRequest(done.uri, done.kind)) }
    }

    /** 前回の結果 (完了/失敗) を消す。 */
    private fun clearResult() = progress.update { Progress() }
}

/** 日付変更を確かめる間隔。 */
private const val DAY_CHECK_INTERVAL_MILLIS = 60_000L
