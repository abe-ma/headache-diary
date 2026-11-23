package io.github.abe_ma.headachediary.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.abe_ma.headachediary.data.catalog.PRESET_MEDICATIONS

@Database(
    entities = [AttackEntity::class, IntakeEntity::class, MedicationEntity::class, PressureHourEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun attackDao(): AttackDao
    abstract fun medicationDao(): MedicationDao
    abstract fun pressureDao(): PressureDao

    companion object {
        /** DB ファイル名。バックアップ設定 (docs/data-layer.md) と対応するため変更しないこと。 */
        const val NAME = "headache_diary.db"

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /** 実機用。 */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addCallback(PresetSeedCallback)
                .addMigrations(*MIGRATIONS)
                .build()

        /** テスト用インメモリ DB (プリセット投入つき)。 */
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addCallback(PresetSeedCallback)
                .allowMainThreadQueries()
                .build()
    }
}

/**
 * v1 → v2 (FR-209): medication.unit を追加。既存の記録・薬は全て保持する。
 * 漢方プリセット (id 16〜22) は包、それ以外は錠 (既定)。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE medication ADD COLUMN unit TEXT NOT NULL DEFAULT 'TABLET'")
        db.execSQL("UPDATE medication SET unit = 'PACKET' WHERE isPreset = 1 AND id BETWEEN 16 AND 22")
    }
}

/**
 * プリセット薬の投入・同期。open のたびに、(1) 未登録の id を INSERT OR IGNORE、(2) 登録済みプリセット行
 * (isPreset=1) の name/ingredient を定義に合わせて UPDATE する。これでアプリ更新による表記変更と
 * 末尾追加が既存 DB にも反映される。id・isHidden は変えない (記録の medicationId と非表示設定を保つ)。
 * 自由入力 (isPreset=0、id>=1000) は更新対象外。後から追加したプリセットと同名の自由入力が既にある場合、
 * プリセット側の投入は一意制約で無視され自由入力が残る (記録を壊さないため。実害は成分表示が無い程度)。
 * ※ SQLite の UPSERT 構文は API 26 の SQLite (3.18) で使えないため 2 文に分けている。
 */
object PresetSeedCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        PRESET_MEDICATIONS.forEachIndexed { i, p ->
            val id = i + 1L
            db.execSQL(
                "INSERT OR IGNORE INTO medication (id, name, ingredient, isPreset, isHidden, unit) VALUES (?, ?, ?, 1, 0, ?)",
                arrayOf<Any?>(id, p.name, p.ingredient, p.unit.name),
            )
            // 名前の一意制約に自由入力が衝突する場合は更新しない (OR IGNORE)。
            db.execSQL(
                "UPDATE OR IGNORE medication SET name = ?, ingredient = ?, unit = ? WHERE id = ? AND isPreset = 1 " +
                    "AND (name != ? OR IFNULL(ingredient, '') != ? OR unit != ?)", // 変更時のみ書く (毎起動の書き込みを避ける)
                arrayOf<Any?>(p.name, p.ingredient, p.unit.name, id, p.name, p.ingredient, p.unit.name),
            )
        }
    }
}

/** 自由入力薬の最小 id。プリセットの末尾追加と衝突しないよう id 空間を分ける。 */
const val CUSTOM_MEDICATION_ID_START = 1000L
