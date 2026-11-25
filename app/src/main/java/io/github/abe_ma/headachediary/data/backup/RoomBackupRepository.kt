package io.github.abe_ma.headachediary.data.backup

import androidx.room.withTransaction
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.db.AttackEntity
import io.github.abe_ma.headachediary.data.db.CUSTOM_MEDICATION_ID_START
import io.github.abe_ma.headachediary.data.db.IntakeEntity
import io.github.abe_ma.headachediary.data.db.MedicationEntity
import io.github.abe_ma.headachediary.domain.BackupException
import io.github.abe_ma.headachediary.domain.BackupInspection
import io.github.abe_ma.headachediary.domain.BackupRepository
import io.github.abe_ma.headachediary.domain.Effect
import io.github.abe_ma.headachediary.domain.InvalidReason
import io.github.abe_ma.headachediary.domain.MedicationUnit
import io.github.abe_ma.headachediary.domain.PrefectureRepository
import io.github.abe_ma.headachediary.domain.RestoreResult
import io.github.abe_ma.headachediary.domain.Severity
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalTime

/**
 * バックアップの作成・検証・統合 (FR-600〜607)。
 * - 書き出しは発作 1 件ずつ Stream へ書く。読み込みは上限 20MB を超えたら中止 (メモリを使い切らない)。
 * - 統合は 1 つの Room トランザクション。失敗時は何も変更しない。設定は検証済みの値のみ SettingsRepository 経由で反映。
 */
@OptIn(ExperimentalSerializationApi::class)
class RoomBackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val prefectures: PrefectureRepository,
    private val appVersion: String,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackupRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---------------- 書き出し ----------------

    override suspend fun write(out: OutputStream) = withContext(io) {
        val attacks = db.attackDao().getAllWithIntakes().sortedBy { it.attack.startedAt }
        val meds = db.medicationDao().getAll()
        val medById = meds.associateBy { it.id }
        fun ref(m: MedicationEntity) =
            if (m.isPreset) BackupMedicationRef(presetId = m.id, name = m.name) else BackupMedicationRef(customName = m.name)

        val s = settings.observe().first()
        val w = out.buffered()
        fun raw(text: String) = w.write(text.toByteArray(Charsets.UTF_8))
        raw("{\"format\":${json.encodeToString(String.serializer(), BackupFormat.NAME)},")
        raw("\"formatVersion\":${BackupFormat.VERSION},\"exportedAt\":${now().toEpochMilli()},")
        raw("\"appVersion\":${json.encodeToString(String.serializer(), appVersion)},\"attacks\":[")
        attacks.forEachIndexed { i, a ->
            if (i > 0) raw(",")
            val item = BackupAttack(
                startedAt = a.attack.startedAt, severity = a.attack.severity,
                pressureHpa = a.attack.pressureHpa, pressureDeltaHpa = a.attack.pressureDeltaHpa,
                intakes = a.intakes.sortedBy { it.intake.id }.map {
                    BackupIntake(ref(medById[it.intake.medicationId] ?: it.medication), it.intake.tablets, it.intake.effect)
                },
            )
            json.encodeToStream(BackupAttack.serializer(), item, w) // 1 件ずつ書く (全体を 1 つの文字列にしない)
        }
        raw("],\"customMedications\":")
        json.encodeToStream(
            ListSerializer(BackupCustomMedication.serializer()),
            meds.filter { !it.isPreset }.map { BackupCustomMedication(it.name, it.unit) }, w,
        )
        raw(",\"medicationVisibility\":")
        json.encodeToStream(
            ListSerializer(BackupVisibility.serializer()),
            meds.map { m ->
                if (m.isPreset) BackupVisibility(presetId = m.id, hidden = m.isHidden) else BackupVisibility(customName = m.name, hidden = m.isHidden)
            },
            w,
        )
        raw(",\"settings\":")
        json.encodeToStream(
            BackupSettings.serializer(),
            BackupSettings(
                prefectureCode = s.prefectureCode, effectReminderEnabled = s.effectReminderEnabled,
                effectReminderDelayMinutes = s.effectReminderDelayMinutes, dropNotifyEnabled = s.dropNotifyEnabled,
                dropThresholdHpa = s.dropThresholdHpa, dropNotifyTime = "%02d:%02d".format(s.dropNotifyTime.hour, s.dropNotifyTime.minute),
            ),
            w,
        )
        raw("}")
        w.flush()
    }

    // ---------------- 読み込みの検証 ----------------

    /** 上限つきで全体を読む。超えたら TOO_LARGE。 */
    private fun readLimited(input: InputStream): ByteArray {
        val buf = java.io.ByteArrayOutputStream(minOf(BackupFormat.MAX_BYTES, 1 shl 20))
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > BackupFormat.MAX_BYTES) throw BackupException(InvalidReason.TOO_LARGE)
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /** ファイルの先頭が JSON オブジェクトらしいか (BOM と空白を飛ばす)。 */
    private fun looksLikeJsonObject(bytes: ByteArray): Boolean {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && (bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() || bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
        return i < bytes.size && bytes[i] == '{'.code.toByte()
    }

    /** 検証して中身を返す。不正なら BackupException。DB は触らない。 */
    private fun parse(input: InputStream): BackupFile {
        val bytes = readLimited(input)
        val header = try {
            json.decodeFromStream(BackupHeader.serializer(), ByteArrayInputStream(bytes))
        } catch (e: SerializationException) {
            // JSON オブジェクトで始まらないものは「バックアップではない」、始まるのに壊れていれば「壊れている」。
            throw BackupException(if (looksLikeJsonObject(bytes)) InvalidReason.CORRUPT else InvalidReason.NOT_BACKUP)
        } catch (e: IllegalArgumentException) {
            throw BackupException(if (looksLikeJsonObject(bytes)) InvalidReason.CORRUPT else InvalidReason.NOT_BACKUP)
        } catch (e: java.io.IOException) {
            throw BackupException(InvalidReason.CORRUPT)
        }
        if (header.format != BackupFormat.NAME) throw BackupException(InvalidReason.NOT_BACKUP)
        val version = header.formatVersion ?: throw BackupException(InvalidReason.CORRUPT)
        if (version > BackupFormat.VERSION) throw BackupException(InvalidReason.UNSUPPORTED_VERSION)
        if (version < 1) throw BackupException(InvalidReason.CORRUPT)
        val file = try {
            json.decodeFromStream(BackupFile.serializer(), ByteArrayInputStream(bytes))
        } catch (e: SerializationException) {
            throw BackupException(InvalidReason.CORRUPT)
        } catch (e: IllegalArgumentException) {
            throw BackupException(InvalidReason.CORRUPT)
        } catch (e: java.io.IOException) {
            throw BackupException(InvalidReason.CORRUPT)
        }
        validate(file)
        return file
    }

    /** 値の整合性 (未知の強さ/効果、錠数 1 未満は壊れたファイル扱い)。 */
    private fun validate(f: BackupFile) {
        val severities = Severity.entries.map { it.name }.toSet()
        val effects = Effect.entries.map { it.name }.toSet()
        for (a in f.attacks) {
            if (a.severity !in severities) throw BackupException(InvalidReason.CORRUPT)
            for (i in a.intakes) if (i.effect !in effects || i.tablets < 1) throw BackupException(InvalidReason.CORRUPT)
        }
    }

    // ---------------- 薬の参照解決と重複判定 ----------------

    private class Meds(val byId: Map<Long, MedicationEntity>, val byName: MutableMap<String, Long>)

    private suspend fun loadMeds(): Meds {
        val all = db.medicationDao().getAll()
        return Meds(all.associateBy { it.id }, all.associate { it.name to it.id }.toMutableMap())
    }

    /** 参照の解決結果: 既存の id ("id:N") か、これから自由入力として追加する薬名 ("new:名前")。 */
    private fun resolve(ref: BackupMedicationRef, meds: Meds): String {
        ref.presetId?.let { id -> meds.byId[id]?.takeIf { it.isPreset }?.let { return "id:${it.id}" } }
        val name = (ref.customName ?: ref.name)?.trim().takeUnless { it.isNullOrEmpty() } ?: UNKNOWN_MEDICATION
        return meds.byName[name]?.let { "id:$it" } ?: "new:$name"
    }

    /** 重複判定のキー: 開始日時 (ms)・痛みの強さ・服薬の (薬, 数量) の集合。 */
    private fun key(startedAt: Long, severity: String, intakes: List<Pair<String, Int>>): String =
        "$startedAt|$severity|" + intakes.map { "${it.first}x${it.second}" }.sorted().joinToString(",")

    private suspend fun existingKeys(): MutableSet<String> =
        db.attackDao().getAllWithIntakes().mapTo(HashSet()) { a ->
            key(a.attack.startedAt, a.attack.severity, a.intakes.map { "id:${it.intake.medicationId}" to it.intake.tablets })
        }

    /** 追加される自由入力の薬名 (customMedications と参照から、端末に無いもの)。 */
    private fun newMedicationNames(f: BackupFile, meds: Meds): LinkedHashSet<String> {
        val names = LinkedHashSet<String>()
        for (m in f.customMedications) {
            val n = m.name.trim()
            if (n.isNotEmpty() && n !in meds.byName) names += n
        }
        for (a in f.attacks) for (i in a.intakes) {
            val r = resolve(i.medication, meds)
            if (r.startsWith("new:")) names += r.removePrefix("new:")
        }
        return names
    }

    // ---------------- inspect / restore ----------------

    override suspend fun inspect(input: InputStream): BackupInspection = withContext(io) {
        val file = try {
            parse(input)
        } catch (e: BackupException) {
            return@withContext BackupInspection.Invalid(e.reason)
        }
        val meds = loadMeds()
        val keys = existingKeys()
        var dup = 0
        for (a in file.attacks) {
            val k = key(a.startedAt, a.severity, a.intakes.map { resolve(it.medication, meds) to it.tablets })
            if (!keys.add(k)) dup++ // ファイル内の重複も重複として数える (restore と同じ規則)
        }
        BackupInspection.Valid(
            attackCount = file.attacks.size, newAttackCount = file.attacks.size - dup, duplicateCount = dup,
            periodStart = file.attacks.minOfOrNull { it.startedAt }?.let(Instant::ofEpochMilli),
            periodEnd = file.attacks.maxOfOrNull { it.startedAt }?.let(Instant::ofEpochMilli),
            customMedicationCount = newMedicationNames(file, meds).size,
            includesSettings = file.settings != null,
            exportedAt = Instant.ofEpochMilli(file.exportedAt), appVersion = file.appVersion,
        )
    }

    override suspend fun restore(input: InputStream): RestoreResult = withContext(io) {
        val file = parse(input)
        // 設定は先に検証・正規化 (DB のトランザクション後に失敗要因を残さない)。
        val restoredSettings = file.settings?.let { sanitize(it) }

        var added = 0
        var skipped = 0
        var addedMeds = 0
        db.withTransaction {
            val medDao = db.medicationDao()
            val meds = loadMeds()
            // 1) 自由入力の薬を追加 (参照を解決できない薬を含む)。
            var nextId = maxOf((medDao.maxId() ?: 0L) + 1, CUSTOM_MEDICATION_ID_START)
            val unitByName = file.customMedications.associate { it.name.trim() to parseUnit(it.unit) }
            for (name in newMedicationNames(file, meds)) {
                medDao.insert(MedicationEntity(nextId, name, null, isPreset = false, isHidden = false, unit = (unitByName[name] ?: MedicationUnit.TABLET).name))
                meds.byName[name] = nextId
                nextId++
                addedMeds++
            }
            // 2) 発作を統合 (重複はスキップ。ファイル内の重複も同様)。
            val keys = existingKeys()
            val newAttacks = ArrayList<BackupAttack>()
            val newIntakeIds = ArrayList<List<Pair<Long, BackupIntake>>>()
            for (a in file.attacks) {
                val resolved = a.intakes.map { resolve(it.medication, meds).removePrefix("id:").toLong() to it }
                val k = key(a.startedAt, a.severity, resolved.map { "id:${it.first}" to it.second.tablets })
                if (keys.add(k)) { newAttacks += a; newIntakeIds += resolved } else skipped++
            }
            val attackDao = db.attackDao()
            val ids = attackDao.insertAttacks(
                newAttacks.map { AttackEntity(0, it.startedAt, it.severity, it.pressureHpa, it.pressureDeltaHpa) }, // 気圧は再計算しない (FR-605)
            )
            val intakes = ArrayList<IntakeEntity>()
            newAttacks.indices.forEach { i ->
                newIntakeIds[i].forEach { (medId, bi) -> intakes += IntakeEntity(0, ids[i], medId, bi.tablets, bi.effect) }
            }
            attackDao.insertIntakes(intakes)
            added = newAttacks.size
            // 3) 薬の表示/非表示 (ファイルの状態で上書き。解決できない薬は無視)。
            for (v in file.medicationVisibility) {
                val id = v.presetId?.let { p -> meds.byId[p]?.takeIf { it.isPreset }?.id }
                    ?: v.customName?.trim()?.let { meds.byName[it] }
                if (id != null) medDao.setHidden(id, v.hidden)
            }
        }
        // 4) 設定 (通知のオン/オフは常にオフ。地域が変われば SettingsRepository のフックが気圧を即時取得する)。
        var applied = false
        if (restoredSettings != null) {
            try {
                settings.update {
                    it.copy(
                        prefectureCode = restoredSettings.prefectureCode,
                        effectReminderEnabled = false, effectReminderDelayMinutes = restoredSettings.effectReminderDelayMinutes,
                        dropNotifyEnabled = false, dropThresholdHpa = restoredSettings.dropThresholdHpa,
                        dropNotifyTime = restoredSettings.dropNotifyTime,
                    )
                }
                applied = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                applied = false // 記録は統合済み。設定だけ反映できなかったことを結果で伝える
            }
        }
        RestoreResult(added, skipped, addedMeds, settingsApplied = applied, notificationsTurnedOff = applied)
    }

    private class Sanitized(val prefectureCode: String?, val effectReminderDelayMinutes: Int, val dropThresholdHpa: Double, val dropNotifyTime: LocalTime)

    /** ファイルの設定値を検証して正規化する (範囲外は既定値)。 */
    private fun sanitize(s: BackupSettings): Sanitized {
        val code = s.prefectureCode?.takeIf { c -> prefectures.all().any { it.code == c } }
        val delay = s.effectReminderDelayMinutes.takeIf { it in 1..24 * 60 } ?: 120
        val threshold = s.dropThresholdHpa.takeIf { it.isFinite() && it > 0.0 && it <= 100.0 } ?: 6.0
        val time = runCatching { LocalTime.parse(s.dropNotifyTime) }.getOrNull() ?: LocalTime.of(20, 0)
        return Sanitized(code, delay, threshold, time)
    }

    private fun parseUnit(u: String?): MedicationUnit = runCatching { MedicationUnit.valueOf(u ?: "") }.getOrDefault(MedicationUnit.TABLET)

    private companion object { const val UNKNOWN_MEDICATION = "不明な薬" }
}
