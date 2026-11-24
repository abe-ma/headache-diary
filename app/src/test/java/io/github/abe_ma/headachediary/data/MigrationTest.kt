package io.github.abe_ma.headachediary.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.db.PresetSeedCallback
import io.github.abe_ma.headachediary.domain.MedicationUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** DB 移行テスト。コミット済みの schemas/1.json から v1 の DB を再現し、v2 へ移行してもデータが残ることを確認する。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private fun schema(version: Int): JSONObject {
        val f = File("schemas/io.github.abe_ma.headachediary.data.db.AppDatabase/$version.json")
        return JSONObject(f.readText()).getJSONObject("database")
    }

    private fun createDb(ctx: Context, name: String, version: Int): SQLiteDatabase {
        val schema = schema(version)
        val path = ctx.getDatabasePath(name).also { it.parentFile!!.mkdirs(); it.delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            val idx = e.optJSONArray("indices")
            if (idx != null) for (j in 0 until idx.length()) {
                db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return db
    }

    @Test
    fun FR_209_migration_1_to_2_keeps_existing_records_and_sets_units() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        createDb(ctx, "migration-test.db", 1).use { db ->
            db.execSQL("INSERT INTO medication (id,name,ingredient,isPreset,isHidden) VALUES (6,'ロキソニン','x',1,1),(16,'五苓散','漢方',1,0),(1000,'自由薬',NULL,0,0)")
            db.execSQL("INSERT INTO attack (id,startedAt,severity,pressureHpa) VALUES (1,1000,'SEVERE',1008.5)")
            db.execSQL("INSERT INTO intake (id,attackId,medicationId,tablets,effect) VALUES (1,1,6,2,'EFFECTIVE'),(2,1,16,1,'UNRATED'),(3,1,1000,3,'NONE')")
            db.execSQL("INSERT INTO pressure_hour (prefectureCode,time,hpa) VALUES ('13',3600000,1010.0)")
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-test.db")
            .addMigrations(*AppDatabase.MIGRATIONS).addCallback(PresetSeedCallback).allowMainThreadQueries().build()
        try {
            val a = db.attackDao().observeAttack(1).first()!!
            assertEquals(1008.5, a.attack.pressureHpa!!, 0.0)
            assertEquals(listOf(2, 1, 3), a.intakes.sortedBy { it.intake.id }.map { it.intake.tablets })
            val meds = db.medicationDao().observeAll().first().associateBy { it.id }
            assertEquals(true, meds.getValue(6).isHidden) // 非表示設定は保持
            assertEquals("PACKET", meds.getValue(16).unit)
            assertEquals("TABLET", meds.getValue(6).unit)
            assertEquals("TABLET", meds.getValue(1000).unit)
            assertEquals(22, meds.values.count { it.isPreset }) // 不足分は seed で補完
            assertEquals(1, db.pressureDao().getRange("13", 0, 7_200_000).size)
            assertTrue(MedicationUnit.valueOf(meds.getValue(16).unit) == MedicationUnit.PACKET)
            assertEquals(null, a.attack.pressureDeltaHpa) // v3 の新列は既存行で NULL
        } finally {
            db.close()
        }
    }

    @Test
    fun FR_404_migration_2_to_3_adds_pressure_delta_column_and_keeps_existing_data() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        createDb(ctx, "migration-test-v2.db", 2).use { db ->
            db.execSQL("INSERT INTO medication (id,name,ingredient,isPreset,isHidden,unit) VALUES (6,'ロキソニン','x',1,0,'TABLET')")
            db.execSQL("INSERT INTO attack (id,startedAt,severity,pressureHpa) VALUES (1,1000,'SEVERE',1008.5),(2,2000,'MILD',NULL)")
            db.execSQL("INSERT INTO intake (id,attackId,medicationId,tablets,effect) VALUES (1,1,6,2,'EFFECTIVE')")
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-test-v2.db")
            .addMigrations(*AppDatabase.MIGRATIONS).addCallback(PresetSeedCallback).allowMainThreadQueries().build()
        try {
            val a1 = db.attackDao().observeAttack(1).first()!!
            assertEquals(1008.5, a1.attack.pressureHpa!!, 0.0)
            assertEquals(null, a1.attack.pressureDeltaHpa)
            assertEquals(2, a1.intakes.single().intake.tablets)
            assertEquals(2, db.attackDao().observeAttacks(null, null).first().size)
            db.attackDao().fillPressure(1, null, -3.5) // 新列に書ける
            assertEquals(-3.5, db.attackDao().observeAttack(1).first()!!.attack.pressureDeltaHpa!!, 0.0)
            assertEquals(1008.5, db.attackDao().observeAttack(1).first()!!.attack.pressureHpa!!, 0.0) // COALESCE で既存値は維持
        } finally {
            db.close()
        }
    }
}
