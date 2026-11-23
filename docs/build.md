# ビルド構成と依存バージョン (T-01 / build)

環境: JDK 17、Gradle 9.9.2 (wrapper)、Android SDK は `ANDROID_HOME` または `local.properties` の `sdk.dir`。
`./gradlew testDebugUnitTest assembleDebug` で検証。

## 識別子
- applicationId / namespace: `io.github.abe_ma.headachediary` (`app/build.gradle.kts` の `appId` 1か所)。
- minSdk 26 / targetSdk 36 (Play の API 36 要件) / compileSdk 37.2 (AndroidX 最新が 37.1 以上を要求するため。SDK Platform 37.2 が必要)。

## バージョンと理由 (`gradle/libs.versions.toml`)
| 項目 | 版 | 理由 |
| --- | --- | --- |
| AGP | 9.5.2 | 2026-11 時点の最新安定版。Kotlin 組み込み (kotlin-android プラグイン不要) |
| Kotlin | 2.4.21 | 最新安定版。Compose / serialization プラグインを同版に揃える |
| KSP | 2.3.14 | Room / Hilt の annotation processing (kapt は使わない) |
| Compose BOM | 2026.11.01 | Compose 系を BOM で一括管理。Material 3 |
| Navigation Compose / Hilt Navigation | 2.10.2 / 1.4.0 | 画面遷移と ViewModel 注入 |
| Hilt | 2.60.1 | DI。WorkManager 用に androidx.hilt:hilt-work も導入 |
| Room | 2.8.5 | KSP。スキーマは `app/schemas/` に出力しコミット (exportSchema=true) |
| DataStore (Preferences) | 1.3.0 | 設定保存 |
| WorkManager | 2.12.0 | リマインダー/日次の気圧取得 |
| Coroutines | 1.11.0 | Flow / テスト |
| テスト | JUnit 4.13.2, Turbine 1.2.1, Robolectric 4.18.1, Compose UI test (BOM), MockWebServer | UI テストは Robolectric 上で実行 |

## HTTP クライアント: Retrofit 3 + kotlinx.serialization (OkHttp 5)
Open-Meteo の GET 1 本のみで十分軽量。Retrofit はインターフェース宣言で Hilt 注入・MockWebServer でのテストが容易。
Ktor は Android 向けにエンジン/プラグイン構成が増えるため見送り。Json は既に Kotlin プラグインがあり追加依存が最小。

## マニフェスト
- 権限: `INTERNET`, `POST_NOTIFICATIONS`。位置情報権限なし。
- `allowBackup=true` + `data_extraction_rules.xml` (API31+) / `backup_rules.xml` (〜API30): DB と `datastore/` を含める (NFR-200)。
- FileProvider: `${applicationId}.fileprovider`、`cache/exports/` のみ。

## R8 (release) 保持ルールの確認 (T-05 配線時)
結論: **追加の proguard ルールは不要** (`proguard-rules.pro` は空のまま)。ライブラリ同梱の consumer rules で足りることを
`app/build/outputs/mapping/release/{seeds,mapping,configuration}.txt` で確認した (assembleRelease、2026-11-23)。

| 対象 | 確認結果 |
| --- | --- |
| `PressureRefreshWorker` (WorkManager がクラス名から生成) | 名前・`(Context, WorkerParameters)` コンストラクタとも seeds に残り、リネームなし (work-runtime の `-keepnames/-keepclassmembers ... ListenableWorker`) |
| `OpenMeteoApi` (Retrofit) | `forecast(...)` が seeds に残る (retrofit の `@retrofit2.http.*` 保持ルール)。クラス名のみ縮小リネーム |
| `ForecastResponse` / `Hourly` (kotlinx.serialization) | `$serializer` と `Companion.serializer()` が seeds に残る (serialization の consumer rules) |
| Room | `AppDatabase` / `AppDatabase_Impl` が seeds に残る (`-keep class * extends RoomDatabase`)。Entity のリネームは生成コードに焼き込み済みで問題なし |

未実施: release APK を実機/エミュレータで動かした実行時確認 (JSON パース・Worker 起動)。実機で初回確認する際は release でも気圧取得を一度試すこと。
