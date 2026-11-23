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
    private fun v1Schema(): JSONObject {
        val f = File("schemas/io.github.abe_ma.headachediary.data.db.AppDatabase/1.json")
        return JSONObject(f.readText()).getJSONObject("database")
    }

    @Test
    fun FR_209_migration_1_to_2_keeps_existing_records_and_sets_units() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val schema = v1Schema()
        val path = ctx.getDatabasePath("migration-test.db").also { it.parentFile!!.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
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
            db.version = 1
            // v1 のユーザーデータ: 漢方プリセット (id16) と通常プリセット (id6)、自由入力 (id1000)、それらを使った記録
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
        } finally {
            db.close()
        }
    }
}
