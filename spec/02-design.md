# 設計 (頭痛ダイアリー)
状態: 承認済み (2026-11-23)。要件は `01-requirements.md`。契約 (Repository インターフェース・ドメインモデル) を変えるときは、先にこの文書を更新する。

## 1. 構成
- Kotlin + Jetpack Compose (Material 3)、MVVM + Repository、StateFlow、Room、Hilt、Coroutines/Flow、WorkManager、DataStore (設定)。
- minSdk 26。パッケージ: `ui/`、`data/`、`domain/`、`di/`。UI は `domain` の型と Repository インターフェースにのみ依存する。
- ネットワークは Open-Meteo のみ (Retrofit + kotlinx.serialization。API キー不要)。

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
    val pressureDeltaHpa: Double? = null, // FR-404: 発作時 − 24時間前の気圧差 (hPa、マイナス=低下)。欠損可。末尾配置 (位置引数の既存呼び出しを壊さないため)。UI は null を渡せば Repository が解決する
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
    val unit: MedicationUnit,        // FR-209: TABLET=錠 / PACKET=包 (漢方=包、他=錠)
)
data class PressurePoint(val time: Instant, val hpa: Double)    // 1時間値
data class MonthCount(val yearMonth: YearMonth, val count: Int) // FR-300

data class Settings(
    val prefectureCode: String?,                 // JIS X 0401 の 2桁 "01".."47"。未設定可
    val effectReminderEnabled: Boolean = false,  // FR-505: 既定オフ
    val effectReminderDelayMinutes: Int = 120,   // FR-500
    val dropNotifyEnabled: Boolean = false,      // FR-501 (権限要求のため既定オフ)
    val dropThresholdHpa: Double = 6.0,
    val dropNotifyTime: LocalTime = LocalTime.of(20, 0),
)
```

### Room テーブル
- `attack(id PK, startedAt, severity, pressureHpa NULL, pressureDeltaHpa NULL)` (Room v3 / MIGRATION_2_3 で列追加。既存行は NULL)
- `intake(id PK, attackId FK→attack ON DELETE CASCADE, medicationId FK, tablets, effect)`
- `medication(id PK, name, ingredient NULL, isPreset, isHidden DEFAULT 0, unit DEFAULT 'TABLET')` (Room v2 / MIGRATION_1_2。プリセットは起動時に id で name/ingredient/unit を同期) — プリセットは初回起動時 (または DB 初期化時) に投入。プリセットの定義は `data` 内の静的リソース (01-requirements の一覧)。
- `pressure_hour(prefectureCode, time, hpa, PK(prefectureCode, time))` — 地域変更後も過去地域のデータは残す (削除しない)。
- 使用回数順 (FR-206) は `intake` の集計で算出する (専用カラムを持たない)。
- 設定は DataStore。DB 移行は `exportSchema=true` で管理。

## 3. Repository 契約 (domain に定義、data が実装、ui が利用)
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

## 4. 画面・遷移
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
- 文言は医療的助言・診断をしない (NFR-104)。文言は `strings*.xml` に集約する。

## 5. 権限・マニフェスト
- `INTERNET`、`POST_NOTIFICATIONS` (API 33+、通知有効化時に要求)。位置情報権限は使わない。
- `android:allowBackup="true"` + `dataExtractionRules` / `fullBackupContent` で Room DB と DataStore を含める (NFR-200)。
- CSV/PDF の共有は `FileProvider` (キャッシュ配下に出力、一定期間後に削除)。

## 6. 実装との差分メモ (統合レビュー反映)
- 画面: 上記に加え「薬の管理」(設定から、FR-207)、「書き出し」(設定から、FR-400〜403)、「このアプリについて」(設定から、帰属・データの扱い・注意書き) が実装された。通知の権限フロー (FR-505) は設定スイッチと、未評価服薬の初回保存後の一度きりの説明ダイアログ (UI 内 SharedPreferences、バックアップ対象外)。
- 追加の契約: `RefreshResult` (Success / Failure(NO_PREFECTURE|NETWORK|API_ERROR))、`ReminderScheduler` (WorkManager 実装)、通知設定の変更フック (`NotifyAwareSettingsRepository`: 地域変更で即時取得、通知設定変更で再予約)。最終取得時刻は地域別 (FR-504)。
- 通知: 効果リマインド・気圧低下とも既定オフ。ロック画面は公開版 (FR-506)。
- 書き出し: CSV 末尾に Open-Meteo 帰属行 (FR-403)、PDF/CSV は薬名×数量+単位 (FR-209)。
- 例外: UI 専用の Hilt モジュールは `ui/` に置く。

## 7. バックアップと復元 (FR-600〜607)
`domain` に追加する契約 (java.io の Stream のみに依存。Uri や ContentResolver は ui が扱う):
```kotlin
interface BackupRepository {
    suspend fun write(out: OutputStream)                       // JSON を書き出す (close は呼び出し側)
    suspend fun inspect(input: InputStream): BackupInspection  // 検証 + 統合のプレビュー。DB は変更しない
    suspend fun restore(input: InputStream): RestoreResult     // 検証し、1 トランザクションで統合。失敗時は変更なし
}
sealed interface BackupInspection {
    data class Valid(val attackCount: Int, val newAttackCount: Int, val duplicateCount: Int,
        val periodStart: Instant?, val periodEnd: Instant?, val customMedicationCount: Int,
        val includesSettings: Boolean, val exportedAt: Instant, val appVersion: String) : BackupInspection
    data class Invalid(val reason: InvalidReason) : BackupInspection  // NOT_BACKUP / CORRUPT / TOO_LARGE / UNSUPPORTED_VERSION
}
data class RestoreResult(val addedAttacks: Int, val skippedDuplicates: Int, val addedMedications: Int,
    val settingsApplied: Boolean, val notificationsTurnedOff: Boolean)
```
- ファイル: UTF-8 JSON。ルートに `format="headache-diary-backup"`、`formatVersion=1`、`exportedAt`、`appVersion`、`attacks[]`、`customMedications[]`、`medicationVisibility[]`、`settings`。服薬の薬は `{presetId}` か `{customName}` で参照 (端末ごとの自由入力の id は持ち出さない)。時刻は epoch ミリ秒。`kotlinx.serialization` (ignoreUnknownKeys=true)。
- 重複判定: 開始日時 (ms)・痛みの強さ・服薬の (薬, 数量) の集合が同じ発作。既存を残す。
- 上限: 読み込みファイルは 20MB まで。書き出しと読み込みはストリームで処理し、1 トランザクションで反映。
- 設定の復元は `SettingsRepository.update` を通す (地域変更の即時取得フックが働く)。通知のオン/オフは常に false で復元。
- 画面: 設定 > 「バックアップと復元」(作成 / 読み込み、注意書き)。SAF (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`) は ui が担当。
