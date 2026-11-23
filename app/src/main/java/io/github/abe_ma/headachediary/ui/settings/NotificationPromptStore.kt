package io.github.abe_ma.headachediary.ui.settings

import android.content.Context
import androidx.core.content.edit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-505(b): 「効果入力のお知らせを受け取りますか?」を一度だけ出すための記録。
 * UI 内の状態でドメイン契約には含めない。バックアップ対象外 (復元後に一度だけ再表示されうるが、通知が有効なら出さない)。
 */
interface NotificationPromptStore {
    fun hasBeenShown(): Boolean
    fun markShown()
}

@Singleton
class SharedPrefsNotificationPromptStore @Inject constructor(
    @ApplicationContext context: Context,
) : NotificationPromptStore {
    private val prefs = context.getSharedPreferences("ui_state", Context.MODE_PRIVATE)
    override fun hasBeenShown() = prefs.getBoolean(KEY, false)
    override fun markShown() = prefs.edit { putBoolean(KEY, true) }

    private companion object {
        const val KEY = "effect_notify_prompt_shown"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UiBindingsModule {
    @Binds abstract fun promptStore(impl: SharedPrefsNotificationPromptStore): NotificationPromptStore
}
