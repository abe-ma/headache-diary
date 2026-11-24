package io.github.abe_ma.headachediary.data.repo

import io.github.abe_ma.headachediary.domain.Settings
import io.github.abe_ma.headachediary.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 設定の変更に応じて副作用 (通知ジョブの再設定、地域変更直後の気圧取得) を起こす
 * (設計 3 章: `update` は変更に応じて通知ジョブを再設定する)。
 */
class NotifyAwareSettingsRepository(
    private val delegate: SettingsRepository,
    private val onChanged: suspend (old: Settings, new: Settings) -> Unit,
) : SettingsRepository {
    override fun observe(): Flow<Settings> = delegate.observe()

    override suspend fun update(transform: (Settings) -> Settings) {
        val old = delegate.observe().first()
        delegate.update(transform)
        val new = delegate.observe().first()
        if (old != new) {
            // 設定は保存済み。副作用の失敗で update を失敗させない (キャンセルは再送出)。
            try {
                onChanged(old, new)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("NotifyAwareSettings", "onChanged failed", e)
            }
        }
    }
}
