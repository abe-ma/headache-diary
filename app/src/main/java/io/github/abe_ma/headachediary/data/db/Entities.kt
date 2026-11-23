package io.github.abe_ma.headachediary.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "attack", indices = [Index("startedAt")])
data class AttackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long, // UTC epoch ms
    val severity: String, // Severity.name
    val pressureHpa: Double?,
)

@Entity(
    tableName = "medication",
    indices = [Index("name", unique = true)],
)
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val ingredient: String?,
    val isPreset: Boolean,
    @ColumnInfo(defaultValue = "0") val isHidden: Boolean = false,
)

@Entity(
    tableName = "intake",
    foreignKeys = [
        ForeignKey(AttackEntity::class, parentColumns = ["id"], childColumns = ["attackId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MedicationEntity::class, parentColumns = ["id"], childColumns = ["medicationId"]),
    ],
    indices = [Index("attackId"), Index("medicationId")],
)
data class IntakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attackId: Long,
    val medicationId: Long,
    val tablets: Int,
    val effect: String, // Effect.name
)

@Entity(tableName = "pressure_hour", primaryKeys = ["prefectureCode", "time"])
data class PressureHourEntity(
    val prefectureCode: String,
    val time: Long, // UTC epoch ms (1時間値)
    val hpa: Double,
)

data class IntakeWithMedication(
    @Embedded val intake: IntakeEntity,
    @Relation(parentColumn = "medicationId", entityColumn = "id") val medication: MedicationEntity,
)

data class AttackWithIntakes(
    @Embedded val attack: AttackEntity,
    @Relation(entity = IntakeEntity::class, parentColumn = "id", entityColumn = "attackId")
    val intakes: List<IntakeWithMedication>,
)
