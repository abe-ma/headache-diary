# CLAUDE.md

頭痛ダイアリー (Android アプリ) のリポジトリ。仕様駆動開発で進める。

## 暫定スタック (未確定。manager がユーザーに確認して確定させる)
- 言語/UI: Kotlin + Jetpack Compose (Material 3)
- アーキテクチャ: MVVM + Repository、単方向データフロー (StateFlow)
- 永続化: Room (端末内のみ。クラウド同期なし)
- DI: Hilt
- 非同期: Kotlin Coroutines / Flow
- リマインダー: WorkManager (正確な時刻が必要なら AlarmManager)
- ビルド: Gradle (Kotlin DSL, version catalog)、minSdk/targetSdk は要決定
- テスト: JUnit + Turbine (ユニット)、Compose UI テスト / Robolectric

## パッケージ境界
- `ui/` ← android-ui、`data/` `domain/` `di/` ← android-data。UI は domain の型と Repository インターフェースだけに依存する。

## 仕様が正
- 仕様は `spec/` にあり、実装より優先する (`01-requirements.md`, `02-design.md`)。
- 食い違い・不足は推測で実装せず、未決事項に追記して manager 経由でユーザーに確認する。
- テスト名に要件ID (FR-xxx) を入れる。仕様を変えたら同じ変更に仕様更新を含める。

## 想定する記録項目 (ヒアリングで確定する)
発作日時(開始/終了)、痛みの強さ、部位、前兆、症状(吐き気・光/音過敏)、誘因、服薬(薬名/量/効果)、メモ、月経周期、天気/気圧 など。

## 未決事項
- 対象ユーザーと目的 (自己管理 / 受診時の提示資料)
- 記録項目の範囲、気圧などの外部データ取得の要否 (外部通信するか)
- 集計・グラフの種類、エクスポート形式 (CSV/PDF)
- minSdk、アプリ名/パッケージ名、公開有無
- バックアップ・端末間移行の方針
