package io.github.abe_ma.headachediary.data.fake

import android.net.Uri
import io.github.abe_ma.headachediary.data.catalog.StaticPrefectureRepository
import io.github.abe_ma.headachediary.data.catalog.presetMedications
import io.github.abe_ma.headachediary.data.repo.PressureDelta
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.AttackRepository
import io.github.abe_ma.headachediary.domain.ExportRepository
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.MedicationRepository
import io.github.abe_ma.headachediary.domain.MonthCount
import io.github.abe_ma.headachediary.domain.PrefectureRepository
import io.github.abe_ma.headachediary.domain.PressurePoint
import io.github.abe_ma.headachediary.domain.PressureRepository
import io.github.abe_ma.headachediary.domain.RefreshResult
import io.github.abe_ma.headachediary.domain.Settings
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * UI 開発・UI テスト用のインメモリ実装 (T-02)。永続化・通知・通信はしない。
 * 3 つの Fake は相互に連携する: Attack の保存で pressureAt を解決し、薬の使用回数は Attack から集計する。
 */
class FakeMedicationRepository(
    private val attacks: MutableStateFlow<List<Attack>>,
    initial: List<Medication> = presetMedications(),
) : MedicationRepository {
    private val medications = MutableStateFlow(initial)

    override fun observeAll(): Flow<List<Medication>> = medications

    override fun observeCandidates(): Flow<List<Medication>> =
        combine(medications, attacks) { meds, atks ->
            val usage = atks.flatMap { it.intakes }.groupingBy { it.medication.id }.eachCount()
            // 使用回数の多い順 → 同数は定義順 (id 昇順。プリセットは定義順、自由入力は追加順)
            meds.filter { !it.isHidden }
                .sortedWith(compareByDescending<Medication> { usage[it.id] ?: 0 }.thenBy { it.id })
        }

    override suspend fun setHidden(id: Long, hidden: Boolean) {
        medications.update { list -> list.map { if (it.id == id) it.copy(isHidden = hidden) else it } }
    }

    override suspend fun addCustom(name: String): Medication {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "薬名が空です" }
        var result: Medication? = null
        medications.update { list ->
            val existing = list.firstOrNull { it.name == trimmed }
            if (existing != null) {
                // 実装 (Room) と同じく、非表示の薬を明示入力したら再表示する。
                val shown = existing.copy(isHidden = false)
                result = shown
                list.map { if (it.id == existing.id) shown else it }
            } else {
                val created = Medication(
                    id = (list.maxOfOrNull { it.id } ?: 0L) + 1,
                    name = trimmed, ingredient = null, isPreset = false, isHidden = false,
                )
                result = created
                list + created
            }
        }
        return result!!
    }
}

class FakeAttackRepository(
    private val pressure: PressureRepository? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) : AttackRepository {
    /** FakeMedicationRepository と共有する発作一覧。 */
    val state = MutableStateFlow<List<Attack>>(emptyList())
    private var nextAttackId = 1L
    private var nextIntakeId = 1L

    override fun observeAttacks(range: ClosedRange<Instant>?): Flow<List<Attack>> =
        state.map { list ->
            list.filter { range == null || it.startedAt in range }.sortedByDescending { it.startedAt }
        }

    override fun observeAttack(id: Long): Flow<Attack?> = state.map { l -> l.firstOrNull { it.id == id } }

    override suspend fun save(attack: Attack): Long {
        val id = if (attack.id == 0L) nextAttackId++ else attack.id
        val intakes = attack.intakes.map { if (it.id == 0L) it.copy(id = nextIntakeId++) else it }
        // 実装 (Room) と同じ: 日時を変えた編集では保存済みの気圧を捨てて再解決する。
        val old = state.value.firstOrNull { it.id == id }
        val timeChanged = old != null && old.startedAt != attack.startedAt
        val current = pressure?.pressureAt(attack.startedAt)
        val hpa = (if (timeChanged) null else attack.pressureHpa) ?: current
        // FR-404: Room 実装と同じガード (保存済みの気圧が現在地域の p(t) と 0.1 hPa 以内で一致するときだけ差を解決)。
        val delta = (if (timeChanged) null else attack.pressureDeltaHpa)
            ?: pressure?.let { PressureDelta.resolveIfSameRegion(it, attack.startedAt, hpa, current) }
        val saved = attack.copy(id = id, intakes = intakes, pressureHpa = hpa, pressureDeltaHpa = delta)
        state.update { list -> list.filter { it.id != id } + saved }
        return id
    }

    override suspend fun delete(id: Long) {
        state.update { list -> list.filter { it.id != id } }
    }

    override fun observeMonthlyCounts(months: Int): Flow<List<MonthCount>> = state.map { list ->
        val current = YearMonth.from(now().atZone(zone))
        val counts = list.groupingBy { YearMonth.from(it.startedAt.atZone(zone)) }.eachCount()
        // 古い月 → 新しい月。発作 0 件の月も 0 で含める。
        (months - 1 downTo 0).map { current.minusMonths(it.toLong()) }.map { MonthCount(it, counts[it] ?: 0) }
    }
}

class FakePressureRepository(
    initial: List<PressurePoint> = emptyList(),
    lastFetchedAt: Instant? = null,
) : PressureRepository {
    val points = MutableStateFlow(initial.sortedBy { it.time })
    val lastFetched = MutableStateFlow(lastFetchedAt)

    /** refresh() が返す結果。テストで差し替える。 */
    var nextRefreshResult: RefreshResult = RefreshResult.Success

    override fun observeSeries(range: ClosedRange<Instant>): Flow<List<PressurePoint>> =
        points.map { l -> l.filter { it.time in range } }

    override suspend fun pressureAt(time: Instant): Double? =
        points.value.filter { Duration.between(it.time, time).abs() <= Duration.ofHours(1) }
            .minByOrNull { Duration.between(it.time, time).abs() }?.hpa

    override suspend fun refresh(): RefreshResult = nextRefreshResult

    override fun observeLastFetchedAt(): Flow<Instant?> = lastFetched
}

class FakeSettingsRepository(initial: Settings = Settings(prefectureCode = null)) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override fun observe(): Flow<Settings> = state
    override suspend fun update(transform: (Settings) -> Settings) {
        state.update(transform)
    }
}

/** 実ファイルは作らず固定の Uri を返す。`Uri.parse` を使うため、素の JUnit ではなく Robolectric 配下で使うこと。 */
class FakeExportRepository : ExportRepository {
    override suspend fun exportCsv(range: ClosedRange<Instant>): Uri = Uri.parse("content://fake/export.csv")
    override suspend fun exportPdf(range: ClosedRange<Instant>): Uri = Uri.parse("content://fake/export.pdf")
}

/** 4 つの Fake を連携済みで束ねる。UI のプレビュー/テストで使う。 */
class FakeRepositories(
    val pressure: FakePressureRepository = FakePressureRepository(),
    val attacks: FakeAttackRepository = FakeAttackRepository(pressure),
    val medications: FakeMedicationRepository = FakeMedicationRepository(attacks.state),
    val settings: FakeSettingsRepository = FakeSettingsRepository(),
    val export: FakeExportRepository = FakeExportRepository(),
    val prefectures: PrefectureRepository = StaticPrefectureRepository(),
)
