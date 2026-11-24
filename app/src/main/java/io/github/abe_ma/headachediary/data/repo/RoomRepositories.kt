package io.github.abe_ma.headachediary.data.repo

import androidx.room.withTransaction
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.db.AttackEntity
import io.github.abe_ma.headachediary.data.db.AttackWithIntakes
import io.github.abe_ma.headachediary.data.db.CUSTOM_MEDICATION_ID_START
import io.github.abe_ma.headachediary.data.db.IntakeEntity
import io.github.abe_ma.headachediary.data.db.MedicationEntity
import io.github.abe_ma.headachediary.domain.Attack
import io.github.abe_ma.headachediary.domain.AttackRepository
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.Intake
import io.github.abe_ma.headachediary.domain.Medication
import io.github.abe_ma.headachediary.domain.MedicationRepository
import io.github.abe_ma.headachediary.domain.MedicationUnit
import io.github.abe_ma.headachediary.domain.MonthCount
import io.github.abe_ma.headachediary.domain.PressureRepository
import io.github.abe_ma.headachediary.domain.ReminderScheduler
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

internal fun MedicationEntity.toDomain() = Medication(
    id, name, ingredient, isPreset, isHidden,
    unit = runCatching { MedicationUnit.valueOf(unit) }.getOrDefault(MedicationUnit.TABLET),
)

internal fun AttackWithIntakes.toDomain() = Attack(
    id = attack.id,
    startedAt = Instant.ofEpochMilli(attack.startedAt),
    severity = Severity.valueOf(attack.severity),
    pressureHpa = attack.pressureHpa,
    intakes = intakes.sortedBy { it.intake.id }.map {
        Intake(it.intake.id, it.medication.toDomain(), it.intake.tablets, Effect.valueOf(it.intake.effect))
    },
    pressureDeltaHpa = attack.pressureDeltaHpa,
)

class RoomMedicationRepository(private val db: AppDatabase) : MedicationRepository {
    private val dao = db.medicationDao()

    override fun observeCandidates(): Flow<List<Medication>> = dao.observeCandidates().map { l -> l.map { it.toDomain() } }
    override fun observeAll(): Flow<List<Medication>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override suspend fun setHidden(id: Long, hidden: Boolean) = dao.setHidden(id, hidden)

    override suspend fun addCustom(name: String): Medication = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "薬名が空です" }
        dao.findByName(trimmed)?.let {
            // 非表示の薬を明示的に入力した場合は再表示する (候補に出ないまま記録だけ作られるのを防ぐ)。
            if (it.isHidden) dao.setHidden(it.id, false)
            return@withTransaction it.copy(isHidden = false).toDomain()
        }
        val id = maxOf((dao.maxId() ?: 0L) + 1, CUSTOM_MEDICATION_ID_START)
        val entity = MedicationEntity(id, trimmed, null, isPreset = false, isHidden = false)
        dao.insert(entity)
        entity.toDomain()
    }
}

class RoomAttackRepository(
    private val db: AppDatabase,
    private val pressure: PressureRepository,
    private val settings: SettingsRepository,
    private val reminders: ReminderScheduler,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Instant = Instant::now,
) : AttackRepository {
    private val dao = db.attackDao()

    override fun observeAttacks(range: ClosedRange<Instant>?): Flow<List<Attack>> =
        dao.observeAttacks(range?.start?.toEpochMilli(), range?.endInclusive?.toEpochMilli())
            .map { l -> l.map { it.toDomain() } }

    override fun observeAttack(id: Long): Flow<Attack?> = dao.observeAttack(id).map { it?.toDomain() }

    override suspend fun save(attack: Attack): Long {
        require(attack.intakes.all { it.tablets >= 1 }) { "錠数は1以上" }
        val existing = if (attack.id != 0L) dao.getAttack(attack.id) else null
        val startedMs = attack.startedAt.toEpochMilli()
        // 日時が変わった編集では保存済みの気圧・気圧差は無効 (FR-104, 404)。
        val timeChanged = existing != null && existing.startedAt != startedMs
        val current = pressure.pressureAt(attack.startedAt) // 現在の登録地域の p(t)
        val hpa = (if (timeChanged) null else attack.pressureHpa) ?: current
        // FR-404: 保存値があれば維持。無ければ解決するが、保存済みの気圧が現在地域の p(t) と一致するときだけ
        // (地域変更後に古い発作を編集しても、旧地域の p(t) と新地域の p(t−24h) の差を保存しない。日次補完と共通のガード)。
        // 日時を変えた保存は hpa が現在地域の値なので、両方が現在地域で再解決される。
        val delta = (if (timeChanged) null else attack.pressureDeltaHpa)
            ?: PressureDelta.resolveIfSameRegion(pressure, attack.startedAt, hpa, current)

        val id = db.withTransaction {
            val entity = AttackEntity(attack.id, startedMs, attack.severity.name, hpa, delta)
            val id = if (existing == null) dao.insertAttack(entity) else { dao.updateAttack(entity); attack.id }
            val keptIds = attack.intakes.map { it.id }.filter { it != 0L }.toSet()
            val stale = dao.intakeIds(id).filter { it !in keptIds }
            if (stale.isNotEmpty()) dao.deleteIntakes(stale)
            attack.intakes.forEach {
                val row = IntakeEntity(it.id, id, it.medication.id, it.tablets, it.effect.name)
                if (it.id == 0L) dao.insertIntake(row) else dao.updateIntake(row)
            }
            id
        }
        // FR-500: 未評価があり設定が有効ならリマインダーを予約/更新、なければ取消。
        val s = settings.observe().first()
        if (s.effectReminderEnabled && attack.intakes.any { it.effect == Effect.UNRATED }) {
            reminders.scheduleEffectReminder(id, s.effectReminderDelayMinutes)
        } else {
            reminders.cancelEffectReminder(id)
        }
        return id
    }

    override suspend fun delete(id: Long) {
        dao.deleteAttack(id) // intake は CASCADE
        reminders.cancelEffectReminder(id)
    }

    override fun observeMonthlyCounts(months: Int): Flow<List<MonthCount>> {
        val z = zone()
        val current = YearMonth.from(now().atZone(z))
        val first = current.minusMonths((months - 1).toLong())
        val from = first.atDay(1).atStartOfDay(z).toInstant().toEpochMilli()
        return dao.observeStartedAtSince(from).map { times ->
            val counts = times.groupingBy { YearMonth.from(Instant.ofEpochMilli(it).atZone(z)) }.eachCount()
            (0 until months).map { first.plusMonths(it.toLong()) }.map { MonthCount(it, counts[it] ?: 0) }
        }
    }
}
