package io.github.abe_ma.headachediary.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.AttackRepository
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Intake
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.domain.SettingsRepository
import io.github.abe_ma.headachediary.ui.settings.NotificationPromptStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject

/** 編集中の服薬。key は画面内の安定識別子 (LazyColumn 用)。id は保存済みなら非 0。 */
data class IntakeDraft(
    val key: Long,
    val id: Long,
    val medication: Medication,
    val tablets: Int,
    val effect: Effect,
)

data class AttackEditUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    val startedAt: Instant = Instant.now(),
    val severity: Severity = Severity.MODERATE,
    val intakes: List<IntakeDraft> = emptyList(),
    val isBusy: Boolean = false,
    val saveFailed: Boolean = false,
    /** 編集対象が存在しない (削除済みなど)。 */
    val notFound: Boolean = false,
    /** 保存/削除が完了し画面を閉じてよい。 */
    val finished: Boolean = false,
    /** FR-505(b): 保存後に出す通知の説明ダイアログの段階。出している間は finished にならない。 */
    val postSavePrompt: PostSavePrompt = PostSavePrompt.NONE,
    /** 開いた時点から内容が変わっている (未保存で戻る時の確認に使う)。 */
    val isDirty: Boolean = false,
)

enum class PostSavePrompt {
    NONE,
    /** 「効果入力のお知らせを受け取りますか?」 */
    ASK,
    /** 通知権限が拒否された旨の案内 (FR-505)。 */
    DENIED,
}

/** プロセス死から復元するための編集中の下書き (SavedStateHandle に JSON 文字列で保持)。 */
@Serializable
private data class DraftSnapshot(
    val startedAtMillis: Long,
    val severity: Severity,
    val intakes: List<DraftIntake>,
)

@Serializable
private data class DraftIntake(
    val key: Long,
    val id: Long,
    val medicationId: Long,
    val medicationName: String,
    val ingredient: String?,
    val isPreset: Boolean,
    val isHidden: Boolean,
    val tablets: Int,
    val effect: Effect,
)

private fun IntakeDraft.toSnapshot() = DraftIntake(
    key, id, medication.id, medication.name, medication.ingredient, medication.isPreset, medication.isHidden, tablets, effect,
)

private fun DraftIntake.toDraft() = IntakeDraft(
    key, id, Medication(medicationId, medicationName, ingredient, isPreset, isHidden), tablets, effect,
)

const val MIN_TABLETS = 1
const val MAX_TABLETS = 99

@HiltViewModel
class AttackEditViewModel @Inject constructor(
    private val attackRepository: AttackRepository,
    private val settingsRepository: SettingsRepository,
    private val promptStore: NotificationPromptStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    /** ナビゲーション引数 id。0 = 新規。 */
    private val attackId: Long = savedStateHandle.get<Long>(ARG_ID) ?: 0L

    private val _uiState = MutableStateFlow(AttackEditUiState(isNew = attackId == 0L))
    val uiState: StateFlow<AttackEditUiState> = _uiState.asStateFlow()

    private var original: Attack? = null
    private var nextKey = 1L

    /** 「変更なし」の基準。isDirty の判定に使う。 */
    private var baseline: DraftSnapshot = snapshotOf(_uiState.value)

    init {
        if (attackId == 0L) {
            // 新規: 基準は開いた時点の状態。復元があれば下書きを反映する。
            restoreDraft()?.let { applyDraft(it) }
            _uiState.update { it.copy(isLoading = false) }
            refreshDirty()
        } else {
            viewModelScope.launch {
                val loaded = attackRepository.observeAttack(attackId).first()
                if (loaded == null) {
                    _uiState.update { it.copy(isLoading = false, notFound = true) }
                } else {
                    original = loaded
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isNew = false,
                            startedAt = loaded.startedAt,
                            severity = loaded.severity,
                            intakes = loaded.intakes.map { i ->
                                IntakeDraft(nextKey++, i.id, i.medication, i.tablets, i.effect)
                            },
                        )
                    }
                    baseline = snapshotOf(_uiState.value)
                    restoreDraft()?.let { applyDraft(it) }
                    refreshDirty()
                }
            }
        }
    }

    private fun snapshotOf(s: AttackEditUiState) =
        DraftSnapshot(s.startedAt.toEpochMilli(), s.severity, s.intakes.map { it.toSnapshot().copy(key = 0) })

    private fun restoreDraft(): DraftSnapshot? =
        savedStateHandle.get<String>(KEY_DRAFT)?.let { runCatching { Json.decodeFromString<DraftSnapshot>(it) }.getOrNull() }

    private fun applyDraft(d: DraftSnapshot) {
        nextKey = maxOf(nextKey, (d.intakes.maxOfOrNull { it.key } ?: 0L) + 1)
        _uiState.update {
            it.copy(
                startedAt = Instant.ofEpochMilli(d.startedAtMillis),
                severity = d.severity,
                intakes = d.intakes.map { i -> i.toDraft() },
            )
        }
    }

    /** 状態を変える操作は必ずここを通す: 下書きを保存し、isDirty を更新する。 */
    private fun mutate(transform: (AttackEditUiState) -> AttackEditUiState) {
        _uiState.update(transform)
        refreshDirty()
        val s = _uiState.value
        if (!s.isLoading && !s.notFound) {
            val draft = DraftSnapshot(s.startedAt.toEpochMilli(), s.severity, s.intakes.map { it.toSnapshot() })
            savedStateHandle[KEY_DRAFT] = Json.encodeToString(DraftSnapshot.serializer(), draft)
        }
    }

    private fun refreshDirty() = _uiState.update { it.copy(isDirty = snapshotOf(it) != baseline) }

    /** FR-208: 未来の日時は指定できない (現在時刻に丸める)。 */
    fun setStartedAt(at: Instant) = mutate { it.copy(startedAt = minOf(at, Instant.now())) }
    fun setSeverity(severity: Severity) = mutate { it.copy(severity = severity) }

    /** FR-203: 薬を服薬に追加。錠数 1、効果は未評価から始める。 */
    fun addMedication(medication: Medication) = mutate {
        it.copy(intakes = it.intakes + IntakeDraft(nextKey++, 0L, medication, MIN_TABLETS, Effect.UNRATED))
    }

    fun removeIntake(key: Long) = mutate { s -> s.copy(intakes = s.intakes.filterNot { it.key == key }) }

    /** FR-205: 錠数は MIN_TABLETS..MAX_TABLETS に収める。 */
    fun changeTablets(key: Long, delta: Int) = updateIntake(key) {
        it.copy(tablets = (it.tablets + delta).coerceIn(MIN_TABLETS, MAX_TABLETS))
    }

    fun setEffect(key: Long, effect: Effect) = updateIntake(key) { it.copy(effect = effect) }

    private fun updateIntake(key: Long, transform: (IntakeDraft) -> IntakeDraft) = mutate { s ->
        s.copy(intakes = s.intakes.map { if (it.key == key) transform(it) else it })
    }

    fun save() {
        val s = _uiState.value
        if (s.isBusy || s.isLoading || s.notFound) return
        _uiState.update { it.copy(isBusy = true, saveFailed = false) }
        viewModelScope.launch {
            val attack = Attack(
                id = attackId,
                startedAt = s.startedAt,
                severity = s.severity,
                // 日時を変えたら気圧を引き直すため null を渡す (保存時に Repository が解決する)。
                pressureHpa = original?.takeIf { it.startedAt == s.startedAt }?.pressureHpa,
                intakes = s.intakes.map { Intake(it.id, it.medication, it.tablets, it.effect) },
            )
            runCatching { attackRepository.save(attack) }
                .onSuccess {
                    savedStateHandle.remove<String>(KEY_DRAFT)
                    val ask = attack.intakes.any { it.effect == Effect.UNRATED } && shouldAskNotification()
                    if (ask) promptStore.markShown() // 一度だけ。「受け取らない」でも再表示しない。
                    _uiState.update {
                        if (ask) it.copy(isBusy = false, postSavePrompt = PostSavePrompt.ASK)
                        else it.copy(isBusy = false, finished = true)
                    }
                }
                .onFailure { _uiState.update { it.copy(isBusy = false, saveFailed = true) } }
        }
    }

    /** 未評価の服薬を保存した初回で、効果入力のお知らせがまだオフのときだけ尋ねる。 */
    private suspend fun shouldAskNotification(): Boolean =
        !promptStore.hasBeenShown() && !settingsRepository.observe().first().effectReminderEnabled

    /** 「受け取る」: 権限が得られたら設定をオンにして閉じる。拒否ならオフのまま理由を案内する。 */
    fun onPromptAccepted(permissionGranted: Boolean) {
        if (!permissionGranted) {
            _uiState.update { it.copy(postSavePrompt = PostSavePrompt.DENIED) }
            return
        }
        viewModelScope.launch {
            settingsRepository.update { it.copy(effectReminderEnabled = true) }
            _uiState.update { it.copy(postSavePrompt = PostSavePrompt.NONE, finished = true) }
        }
    }

    /** 「受け取らない」または案内を閉じた: 設定は変えずに閉じる。 */
    fun onPromptDismissed() = _uiState.update { it.copy(postSavePrompt = PostSavePrompt.NONE, finished = true) }

    fun delete() {
        val s = _uiState.value
        if (s.isBusy || attackId == 0L) return
        _uiState.update { it.copy(isBusy = true, saveFailed = false) }
        viewModelScope.launch {
            runCatching { attackRepository.delete(attackId) }
                .onSuccess { savedStateHandle.remove<String>(KEY_DRAFT); _uiState.update { it.copy(isBusy = false, finished = true) } }
                .onFailure { _uiState.update { it.copy(isBusy = false, saveFailed = true) } }
        }
    }

    companion object {
        const val ARG_ID = "id"
        private const val KEY_DRAFT = "draft"
    }
}
