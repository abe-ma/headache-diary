package io.github.abe_ma.headachediary.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AttackDao {
    @Transaction
    @Query("SELECT * FROM attack WHERE (:from IS NULL OR startedAt >= :from) AND (:to IS NULL OR startedAt <= :to) ORDER BY startedAt DESC, id DESC")
    fun observeAttacks(from: Long?, to: Long?): Flow<List<AttackWithIntakes>>

    @Transaction
    @Query("SELECT * FROM attack WHERE id = :id")
    fun observeAttack(id: Long): Flow<AttackWithIntakes?>

    @Query("SELECT * FROM attack WHERE id = :id")
    suspend fun getAttack(id: Long): AttackEntity?

    @Query("SELECT startedAt FROM attack WHERE startedAt >= :from")
    fun observeStartedAtSince(from: Long): Flow<List<Long>>

    /** T-05 の補完用: 気圧が欠損している、指定時刻以降の発作。 */
    @Query("SELECT * FROM attack WHERE pressureHpa IS NULL AND startedAt >= :since")
    suspend fun getMissingPressureSince(since: Long): List<AttackEntity>

    /** 効果が未評価の服薬を持つ、指定時刻以降の発作 (起動時のリマインダー復元用)。 */
    @Query("SELECT DISTINCT a.* FROM attack a JOIN intake i ON i.attackId = a.id WHERE i.effect = 'UNRATED' AND a.startedAt >= :since")
    suspend fun getUnratedSince(since: Long): List<AttackEntity>

    @Query("UPDATE attack SET pressureHpa = :hpa WHERE id = :id AND pressureHpa IS NULL")
    suspend fun fillPressure(id: Long, hpa: Double)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAttack(a: AttackEntity): Long
    @Update suspend fun updateAttack(a: AttackEntity)
    @Query("DELETE FROM attack WHERE id = :id") suspend fun deleteAttack(id: Long)

    @Insert suspend fun insertIntake(i: IntakeEntity): Long
    @Update suspend fun updateIntake(i: IntakeEntity)
    @Query("SELECT id FROM intake WHERE attackId = :attackId") suspend fun intakeIds(attackId: Long): List<Long>
    @Query("DELETE FROM intake WHERE id IN (:ids)") suspend fun deleteIntakes(ids: List<Long>)
}

@Dao
interface MedicationDao {
    /** 非表示を除く。使用回数の多い順 → id 順 (プリセットは定義順に id 採番)。FR-206, 207 */
    @Query(
        "SELECT * FROM medication WHERE isHidden = 0 " +
            "ORDER BY (SELECT COUNT(*) FROM intake WHERE intake.medicationId = medication.id) DESC, id ASC",
    )
    fun observeCandidates(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medication ORDER BY id ASC")
    fun observeAll(): Flow<List<MedicationEntity>>

    @Query("UPDATE medication SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("SELECT * FROM medication WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): MedicationEntity?

    @Query("SELECT MAX(id) FROM medication")
    suspend fun maxId(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: MedicationEntity): Long
}

@Dao
interface PressureDao {
    @Query("SELECT * FROM pressure_hour WHERE prefectureCode = :code AND time BETWEEN :from AND :to ORDER BY time")
    fun observeSeries(code: String, from: Long, to: Long): Flow<List<PressureHourEntity>>

    @Query("SELECT * FROM pressure_hour WHERE prefectureCode = :code AND time BETWEEN :from AND :to ORDER BY time")
    suspend fun getRange(code: String, from: Long, to: Long): List<PressureHourEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PressureHourEntity>)
}
