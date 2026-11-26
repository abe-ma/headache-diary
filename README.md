# 頭痛ダイアリー

片頭痛・頭痛の発作と服薬を記録し、気圧との関係を見るための Android アプリ。記録は端末内に保存されます。

## 主な機能
- 発作の記録 (日時、痛みの強さ 3 段階、服薬の薬名・数量・効果の体感)
- 気圧の自動取得 (登録した都道府県の海面更正気圧、[Open-Meteo](https://open-meteo.com/))。発作時の気圧と、24 時間前との差を記録
- 集計: 月別の発作回数、気圧の推移 (予報つき) と発作の重ね合わせ
- 書き出し: CSV / PDF (受診時の提示用)
- バックアップと復元: 記録・自由入力の薬・薬の表示/非表示・設定を JSON ファイルに保存し、機種変更や再インストールのあとに読み込める (読み込みは既存の記録に統合)
- 通知 (既定オフ): 服薬後の効果入力のお知らせ、気圧が下がる予報のお知らせ
- 薬の候補の表示/非表示、ダークモード対応

アプリは診断や治療の助言を行いません。記録・集計・可視化のためのものです。

## 動作環境
Android 8.0 (API 26) 以上。

## インストール (APK)
署名付きの APK を [Releases](https://github.com/abe-ma/headache-diary/releases) で配布しています。Google Play では配布していません。

1. 最新の Release から `headache-diary-<バージョン>.apk` をダウンロードする (Android 8.0 以上)。
2. 端末でファイルを開く。「この提供元のアプリのインストールを許可」を求められたら、使っているブラウザやファイルアプリに対して許可する。Play プロテクトの警告が出ることがありますが、「このまま インストール」を選べます。
3. (任意) ダウンロードの確認: Release に載せた SHA-256 と、`sha256sum <APK>` の結果を比べる。署名は `apksigner verify --print-certs <APK>` で確認でき、証明書の SHA-256 は次のとおり: `aae0d2c8a825ab76e71734dcf8e117dff809de7fab6ad97afa73195b07bc7f8e`
4. 更新: 同じ鍵で署名されているので、新しい APK をそのまま上書きインストールできます (記録は残ります)。署名が異なる APK (自分でビルドした debug 版など) からは上書きできません。

このアプリは診断や治療を目的としたものではなく、無保証です ([LICENSE](LICENSE))。

## ビルド
JDK 17 と Android SDK (platform 36 以降) が必要です。`local.properties` に `sdk.dir=<SDK のパス>` を書くか、`ANDROID_HOME` を設定してください。

```
./gradlew testDebugUnitTest   # ユニットテスト
./gradlew assembleDebug       # debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
```

## ドキュメント
- 仕様: [spec/01-requirements.md](spec/01-requirements.md)、設計: [spec/02-design.md](spec/02-design.md)
- ビルド・検証記録: [docs/build.md](docs/build.md)、データ層メモ: [docs/data-layer.md](docs/data-layer.md)
- 実機確認チェックリスト: [docs/device-checklist.md](docs/device-checklist.md)
- プライバシーポリシー: [docs/privacy-policy.md](docs/privacy-policy.md)
- 公開手順 (署名): [docs/release-signing.md](docs/release-signing.md)
- 薬名の照合記録: [docs/medication-verification.md](docs/medication-verification.md)

## データと通信
外部に送信するのは、気圧の取得のための登録地域の代表座標 (小数 1 桁に丸めた値) と取得期間だけです。発作・服薬などの記録は送信しません。詳細は [プライバシーポリシー](docs/privacy-policy.md)。

## 気圧データの出典
Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0)。無料枠は非商用利用に限られます。商用利用 (広告・課金) をする場合は有料プランが必要です。

## ライセンス
[MIT License](LICENSE) (Copyright (c) 2026 abe-ma)。ただし気圧データは Open-Meteo のライセンス (CC BY 4.0、無料枠は非商用限定) に従います。
