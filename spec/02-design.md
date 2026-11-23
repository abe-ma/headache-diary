# 設計 (頭痛ダイアリー)
状態: 承認済み (2026-11-23)。要件は `01-requirements.md`。ここに書いた契約の変更は manager 経由で合意する。

## 1. 構成
- Kotlin + Jetpack Compose (Material 3)、MVVM + Repository、StateFlow、Room、Hilt、Coroutines/Flow、WorkManager、DataStore (設定)。
- minSdk 26。パッケージ: `ui/` (android-ui)、`data/` `domain/` `di/` (android-data)。UI は `domain` の型と Repository インターフェースにのみ依存する。
- ネットワークは Open-Meteo のみ (Retrofit か Ktor は android-data が選定。API キー不要)。

## 2. データモデル (domain)
時刻は UTC epoch ミリ秒で保存し、表示時に端末のタイムゾーンへ変換する。Open-Meteo へは `timezone=Asia/Tokyo&timeformat=unixtime` で要求する。

```kotlin
enum class Severity { MILD, MODERATE, SEVERE }                 // FR-202
enum class Effect { UNRATED, EFFECTIVE, SLIGHT, NONE }          // FR-204

data class Attack(                                              // FR-200,201
    val id: Long,                    // 0 = 新規
    val startedAt: Instant,
    val severity: Severity,
    val pressureHpa: Double?,        // FR-104: 保存時の紐づけ。欠損可
    val intakes: List<Intake>,
)
data class Intake(                                              // FR-203,204,205
    val id: Long,
    val medication: Medication,
    val tablets: Int,                // 1以上
    val effect: Effect,
)
data class Medication(
    val id: Long,
    val name: String,                // 表示名 (商品名)
    val ingredient: String?,         // 参考表示 (成分)。自由入力は null
    val isPreset: Boolean,
    val isHidden: Boolean,           // FR-207
)
data class PressurePoint(val time: Instant, val hpa: Double)    // 1時間値
data class MonthCount(val yearMonth: YearMonth, val count: Int) // FR-300

data class Settings(
    val prefectureCode: String?,                 // JIS X 0401 の 2桁 "01".."47"。未設定可
    val effectReminderEnabled: Boolean = true,
    val effectReminderDelayMinutes: Int = 120,   // FR-500
    val dropNotifyEnabled: Boolean = false,      // FR-501 (権限要求のため既定オフ)
    val dropThresholdHpa: Double = 6.0,
    val dropNotifyTime: LocalTime = LocalTime.of(20, 0),
)
```

### Room テーブル
- `attack(id PK, startedAt, severity, pressureHpa NULL)`
- `intake(id PK, attackId FK→attack ON DELETE CASCADE, medicationId FK, tablets, effect)`
- `medication(id PK, name, ingredient NULL, isPreset, isHidden DEFAULT 0)` — プリセットは初回起動時 (または DB 初期化時) に投入。プリセットの定義は `data` 内の静的リソース (01-requirements の一覧)。
- `pressure_hour(prefectureCode, time, hpa, PK(prefectureCode, time))` — 地域変更後も過去地域のデータは残す (削除しない)。
- 使用回数順 (FR-206) は `intake` の集計で算出する (専用カラムを持たない)。
- 設定は DataStore。DB 移行は `exportSchema=true` で管理。

## 3. Repository 契約 (domain、android-data が実装、android-ui が利用)
```kotlin
interface AttackRepository {
    fun observeAttacks(range: ClosedRange<Instant>? = null): Flow<List<Attack>>   // 新しい順
    fun observeAttack(id: Long): Flow<Attack?>
    suspend fun save(attack: Attack): Long          // 保存時に pressureHpa を PressureRepository で解決。未評価の服薬があれば効果リマインダーを予約/更新 (FR-500)
    suspend fun delete(id: Long)                    // 関連リマインダーも取消
    fun observeMonthlyCounts(months: Int): Flow<List<MonthCount>>                 // FR-300
}
interface MedicationRepository {
    fun observeCandidates(): Flow<List<Medication>>  // 非表示を除く。使用回数の多い順 → プリセット順 (FR-206, 207)
    fun observeAll(): Flow<List<Medication>>         // 薬の管理画面用。非表示も含む
    suspend fun setHidden(id: Long, hidden: Boolean) // FR-207。過去記録の表示には影響しない
    suspend fun addCustom(name: String): Medication  // 同名は既存を返す
}
interface PressureRepository {
    fun observeSeries(range: ClosedRange<Instant>): Flow<List<PressurePoint>>      // 登録地域。FR-103
    suspend fun pressureAt(time: Instant): Double?   // 最寄りの1時間値 (±1時間以内) なければ null
    suspend fun refresh(): RefreshResult             // FR-101。成功/失敗(理由)。失敗でも例外を投げない
    // sealed RefreshResult { Success; Failure(reason: NO_PREFECTURE | NETWORK | API_ERROR) }
    fun observeLastFetchedAt(): Flow<Instant?>       // FR-105
}
interface SettingsRepository {
    fun observe(): Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)  // 変更に応じて通知ジョブを再設定
}
interface ExportRepository {
    suspend fun exportCsv(range: ClosedRange<Instant>): Uri   // FR-400。FileProvider の content Uri
    suspend fun exportPdf(range: ClosedRange<Instant>): Uri   // FR-401。android.graphics.pdf.PdfDocument
}
data class Prefecture(val code: String, val name: String, val lat: Double, val lon: Double) // 静的データ (県庁所在地)
interface PrefectureRepository { fun all(): List<Prefecture> }
```

### 気圧の取得と補完
- 取得: `GET https://api.open-meteo.com/v1/forecast?latitude=<小数1桁>&longitude=<小数1桁>&hourly=pressure_msl&past_days=<n>&forecast_days=3&timezone=Asia/Tokyo&timeformat=unixtime`。座標は小数1桁に丸める (NFR-100)。
- `past_days`: 地域を設定した直後の初回は 31 (暫定)、通常の日次は 7。月間グラフが過去分を持てるのは取得開始以降。
- 日次ジョブ (WorkManager、ネットワーク接続時、失敗は指数バックオフ。FR-105) 完了後に、(a) `pressureHpa` が null の直近発作を補完、(b) FR-501 の判定を行う。
- FR-504: 最終取得から 36 時間以上経っている場合は通知を出さない。

### 気圧低下の判定 (FR-501、暫定)
通知日の翌暦日 (ローカル 0:00〜24:00) に含まれる各時刻 t について、t より前 24 時間以内の最大値と p(t) の差を下降幅とし、その最大値がしきい値以上なら通知対象。1日1回。

### 通知ジョブ
- 効果リマインド: `OneTimeWorkRequest` (遅延 = 設定分)。タグ `effect-<attackId>`。通知タップで `attack/{id}` を開く。
- 気圧低下通知: 日次取得ジョブの後に判定し、設定時刻に通知するため `OneTimeWorkRequest` を設定時刻に再予約。通知チャンネルは2つ (効果入力 / 気圧低下)。
- 再起動・バックアップ復元後はアプリ起動時に `SettingsRepository` から再予約する (WorkManager は再起動を越えて保持される)。

## 4. 画面・遷移 (android-ui)
下部タブ3つ: **記録** / **集計** / **設定**。

| 画面 | 内容 | 要件 |
| --- | --- | --- |
| 記録一覧 (タブ1) | 発作の一覧 (新しい順)。FAB で新規作成 | FR-200 |
| 発作編集 (`attack/{id}`、新規は id=0) | 発作日時、痛みの強さ (3択)、服薬の追加/削除 (薬選択、錠数、効果)。保存/削除 | FR-201〜206 |
| 薬選択 (ボトムシート) | 候補 (非表示を除く・使用回数順)、検索、自由入力で追加 | FR-203, 206 |
| 集計 (タブ2) | 月別発作回数の棒グラフ。気圧推移 + 発作マーカーのグラフ (期間切替)。帰属表示リンク | FR-300, 301, 103, 106 |
| 設定 (タブ3) | 地域 (都道府県)、薬の管理 (一覧と表示/非表示スイッチ)、通知 (効果リマインド/気圧低下: オン/オフ・時間・しきい値)、最終取得時刻、書き出しへの導線、このアプリについて (帰属・プライバシー) | FR-100, 105, 207, 500〜503 |
| 書き出し | 期間選択、CSV/PDF を選んで共有シートへ | FR-400〜402 |

- 初回起動: 地域未設定でも記録は使える。集計/設定から地域設定を促す。気圧が無いグラフは「地域を設定すると気圧が表示されます」と案内する。
- 文言は医療的助言・診断をしない (NFR-104)。文言は manager が確認する。

## 5. 権限・マニフェスト
- `INTERNET`、`POST_NOTIFICATIONS` (API 33+、通知有効化時に要求)。位置情報権限は使わない。
- `android:allowBackup="true"` + `dataExtractionRules` / `fullBackupContent` で Room DB と DataStore を含める (NFR-200)。
- CSV/PDF の共有は `FileProvider` (キャッシュ配下に出力、一定期間後に削除)。

## 6. タスク分解案 (承認後)
| ID | 内容 | 担当 | 依存 |
| --- | --- | --- | --- |
| T-01 | Gradle 雛形 (version catalog, Hilt, Room, Compose, WorkManager)、CI なし、マニフェスト | build (追加) または android-data | 開発環境導入 |
| T-02 | domain モデル + Repository インターフェース + プリセット薬/都道府県データ | android-data | T-01 |
| T-03 | Room/DataStore 実装、AttackRepository/MedicationRepository | android-data | T-02 |
| T-04 | 発作記録 UI (一覧・編集・薬選択) | android-ui | T-02 (契約) |
| T-05 | Open-Meteo 連携、PressureRepository、日次ジョブ | android-data | T-02 |
| T-06 | 集計 UI (月別回数、気圧グラフ、帰属表示) | android-ui | T-02 |
| T-07 | 設定 UI + SettingsRepository + 地域選択 | android-ui / android-data | T-02 |
| T-08 | 通知 (効果リマインド、気圧低下) | android-data (UI 側は通知タップ遷移) | T-03, T-05 |
| T-09 | CSV/PDF エクスポート + 書き出し UI | android-data / android-ui | T-03, T-05 |
| T-10 | プライバシーポリシー・ストア申告資料 (manager が起草) | manager | - |
