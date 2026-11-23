package io.github.abe_ma.headachediary.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.abe_ma.headachediary.data.catalog.StaticPrefectureRepository
import io.github.abe_ma.headachediary.data.db.AppDatabase
import io.github.abe_ma.headachediary.data.export.FileExportRepository
import io.github.abe_ma.headachediary.data.remote.OpenMeteoApi
import io.github.abe_ma.headachediary.data.repo.RoomPressureRepository
import io.github.abe_ma.headachediary.data.repo.DataStoreSettingsRepository
import io.github.abe_ma.headachediary.data.repo.NoOpReminderScheduler
import io.github.abe_ma.headachediary.data.repo.RoomAttackRepository
import io.github.abe_ma.headachediary.data.repo.RoomMedicationRepository
import io.github.abe_ma.headachediary.domain.AttackRepository
import io.github.abe_ma.headachediary.domain.ExportRepository
import io.github.abe_ma.headachediary.domain.MedicationRepository
import io.github.abe_ma.headachediary.domain.PrefectureRepository
import io.github.abe_ma.headachediary.domain.PressureRepository
import io.github.abe_ma.headachediary.domain.ReminderScheduler
import io.github.abe_ma.headachediary.domain.SettingsRepository
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.pressureMetaDataStore: DataStore<Preferences> by preferencesDataStore(name = "pressure_meta")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun database(@ApplicationContext c: Context): AppDatabase = AppDatabase.create(c)

    @Provides @Singleton
    fun settings(@ApplicationContext c: Context): SettingsRepository = DataStoreSettingsRepository(c.settingsDataStore)

    @Provides @Singleton
    fun medications(db: AppDatabase): MedicationRepository = RoomMedicationRepository(db)

    @Provides @Singleton
    fun prefectures(): PrefectureRepository = StaticPrefectureRepository()

    // T-08 で WorkManager 実装に差し替える。
    @Provides @Singleton
    fun reminders(): ReminderScheduler = NoOpReminderScheduler()

    @Provides @Singleton
    fun openMeteo(): OpenMeteoApi = OpenMeteoApi.create()

    @Provides @Singleton
    fun pressure(
        @ApplicationContext c: Context,
        db: AppDatabase,
        api: OpenMeteoApi,
        settings: SettingsRepository,
        prefectures: PrefectureRepository,
    ): PressureRepository = RoomPressureRepository(db.pressureDao(), api, settings, prefectures, c.pressureMetaDataStore)

    @Provides @Singleton
    fun attacks(
        db: AppDatabase,
        pressure: PressureRepository,
        settings: SettingsRepository,
        reminders: ReminderScheduler,
    ): AttackRepository = RoomAttackRepository(db, pressure, settings, reminders)

    @Provides @Singleton
    fun export(
        @ApplicationContext c: Context,
        attacks: AttackRepository,
        pressure: PressureRepository,
    ): ExportRepository = FileExportRepository(c, attacks, pressure)
}
