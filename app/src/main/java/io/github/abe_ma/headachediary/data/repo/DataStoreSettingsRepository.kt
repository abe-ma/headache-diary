package io.github.abe_ma.headachediary.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.abe_ma.headachediary.domain.Settings
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

/** 設定 (DataStore Preferences)。ファイルは files/datastore/settings.preferences_pb。 */
class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    private object K {
        val prefecture = stringPreferencesKey("prefectureCode")
        val effectEnabled = booleanPreferencesKey("effectReminderEnabled")
        val effectDelay = intPreferencesKey("effectReminderDelayMinutes")
        val dropEnabled = booleanPreferencesKey("dropNotifyEnabled")
        val dropThreshold = doublePreferencesKey("dropThresholdHpa")
        val dropTimeMinutes = intPreferencesKey("dropNotifyTimeMinutes") // 0:00 からの分
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings(prefectureCode = null)
        return Settings(
            prefectureCode = this[K.prefecture],
            effectReminderEnabled = this[K.effectEnabled] ?: d.effectReminderEnabled,
            effectReminderDelayMinutes = this[K.effectDelay] ?: d.effectReminderDelayMinutes,
            dropNotifyEnabled = this[K.dropEnabled] ?: d.dropNotifyEnabled,
            dropThresholdHpa = this[K.dropThreshold] ?: d.dropThresholdHpa,
            dropNotifyTime = this[K.dropTimeMinutes]?.let { LocalTime.of(it / 60, it % 60) } ?: d.dropNotifyTime,
        )
    }

    override fun observe(): Flow<Settings> = store.data.map { it.toSettings() }

    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit { p ->
            val s = transform(p.toSettings())
            if (s.prefectureCode == null) p.remove(K.prefecture) else p[K.prefecture] = s.prefectureCode
            p[K.effectEnabled] = s.effectReminderEnabled
            p[K.effectDelay] = s.effectReminderDelayMinutes
            p[K.dropEnabled] = s.dropNotifyEnabled
            p[K.dropThreshold] = s.dropThresholdHpa
            p[K.dropTimeMinutes] = s.dropNotifyTime.hour * 60 + s.dropNotifyTime.minute
        }
    }
}

/** T-08 までの no-op。 */
class NoOpReminderScheduler : io.github.abe_ma.headachediary.domain.ReminderScheduler {
    override fun scheduleEffectReminder(attackId: Long, delayMinutes: Int) = Unit
    override fun cancelEffectReminder(attackId: Long) = Unit
}
