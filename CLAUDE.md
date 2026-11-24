# CLAUDE.md

頭痛ダイアリー (Android アプリ) のリポジトリ。仕様駆動で開発する。仕様は `spec/`、設計メモ・検証記録は `docs/`。

## 技術スタック
- 言語/UI: Kotlin + Jetpack Compose (Material 3)
- アーキテクチャ: MVVM + Repository、単方向データフロー (StateFlow)
- 永続化: Room (端末内のみ。独自のクラウド同期なし。Android の自動バックアップは対象)
- 設定: DataStore / DI: Hilt / 非同期: Kotlin Coroutines・Flow / 定期処理・通知: WorkManager
- 通信: Retrofit + kotlinx.serialization (Open-Meteo のみ、API キー不要)
- ビルド: Gradle (Kotlin DSL, version catalog)。minSdk 26 / targetSdk 36 / applicationId `io.github.abe_ma.headachediary`
- テスト: JUnit + Turbine (ユニット)、Robolectric、Compose UI テスト

## パッケージ境界
- `ui/` (画面・ViewModel・テーマ)、`domain/` (モデル・Repository インターフェース)、`data/` (Room・DataStore・通信・通知・書き出しの実装)、`di/` (Hilt の束ね)。
- UI は `domain` の型と Repository インターフェースだけに依存する。UI 専用の Hilt モジュールは `ui/` に置いてよい。

## 仕様が正
- 仕様は `spec/` (`01-requirements.md`, `02-design.md`) にあり、実装より優先する。
- 食い違い・不足は推測で実装せず、仕様を先に更新してから実装する。
- テスト名に要件ID (FR-xxx / NFR-xxx) を入れる。仕様を変えたら同じ変更で仕様も更新する。

## 方針
- 健康データ (発作・服薬・気圧との紐づけ) は端末内に保存し、外部へ送信しない。外部へ送るのは気圧取得のための丸めた座標 (小数1桁) と期間だけ (NFR-100)。
- アプリは医療的助言・診断をしない。記録・集計・可視化まで (NFR-104)。文言は `strings*.xml` に集約する。
- ログに例外メッセージやスタックトレースを出さない (SQL や健康データが混ざりうるため)。クラス名のみ。

## ビルドと検証
- `./gradlew testDebugUnitTest` / `./gradlew lintDebug` / `./gradlew assembleDebug`。メモリが少ない環境では `--no-parallel --max-workers=2` を付け、終了後に `./gradlew --stop`。
- 詳細は `docs/build.md`。実機でしか確認できない項目は `docs/device-checklist.md`。
- 署名鍵 (`*.jks` / `keystore.properties`) と `local.properties` はコミットしない。公開手順は `docs/release-signing.md`。
