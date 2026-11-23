package io.github.abe_ma.headachediary.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.abe_ma.headachediary.data.catalog.PRESET_MEDICATIONS

@Database(
    entities = [AttackEntity::class, IntakeEntity::class, MedicationEntity::class, PressureHourEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun attackDao(): AttackDao
    abstract fun medicationDao(): MedicationDao
    abstract fun pressureDao(): PressureDao

    companion object {
        /** DB ファイル名。バックアップ設定 (docs/data-layer.md) と対応するため変更しないこと。 */
        const val NAME = "headache_diary.db"

        /** 実機用。 */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addCallback(PresetSeedCallback)
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
 * プリセット薬の投入。open のたびに `INSERT OR IGNORE` するので、アプリ更新で末尾に追加した
 * プリセットも次回起動で入る。id は定義順 (1 始まり) で固定。ユーザーの isHidden は上書きしない。
 */
object PresetSeedCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        PRESET_MEDICATIONS.forEachIndexed { i, p ->
            db.execSQL(
                "INSERT OR IGNORE INTO medication (id, name, ingredient, isPreset, isHidden) VALUES (?, ?, ?, 1, 0)",
                arrayOf<Any?>(i + 1L, p.name, p.ingredient),
            )
        }
    }
}

/** 自由入力薬の最小 id。プリセットの末尾追加と衝突しないよう id 空間を分ける。 */
const val CUSTOM_MEDICATION_ID_START = 1000L
