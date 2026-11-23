# リリース署名メモ (Google Play 公開時)

**鍵 (keystore) はこのリポジトリに作成・コミットしない。** `.gitignore` で `*.jks` / `*.keystore` / `keystore.properties` を除外済み。

## 現状
- `assembleRelease` / `bundleRelease` の成果物は **署名なし** (`app-release-unsigned.apk` / `app-release.aab`)。`signingConfig` は未設定。
- 署名なし APK は端末にインストールできない。端末での動作確認は debug APK (自動で debug 鍵署名) を使う。

## 公開手順 (Play App Signing 前提)
1. Play Console でアプリを作成し、**Play App Signing** を有効にする (新規アプリは AAB 提出が必須)。
2. **アップロード鍵**を開発者の手元で作る (値は各自が決める。紛失に備え安全な場所に複数バックアップ):
   `keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`
3. 署名情報は Git 管理外に置く: `app/keystore.properties` (storeFile / storePassword / keyAlias / keyPassword)。
   `app/build.gradle.kts` に、ファイルが存在する時だけ `signingConfigs.create("release")` を読み込み `release` ビルドタイプへ適用する設定を追加する (build 担当が公開直前に実施。ファイル不在でもビルドが通ること)。
   CI を使う場合は鍵とパスワードを CI のシークレットで渡す。
4. `./gradlew bundleRelease` で署名済み AAB を生成し、Play Console の内部テストトラックへアップロード。Google が配信用の App Signing 鍵で再署名する。
5. 公開前チェック: applicationId (`io.github.abe_ma.headachediary`) は公開後に変更不可。versionCode は提出ごとに増やす。データセーフティ (健康情報は端末内保存、外部送信は丸めた座標のみ) とプライバシーポリシー URL を用意する。

## 注意
- アップロード鍵の紛失時は Play Console から鍵リセット申請が必要 (App Signing 有効時)。
- App Signing を使わず自前で署名して配布する場合 (サイドロード等) は、`apksigner` で zipalign 後に署名する。その鍵は紛失すると更新インストールができなくなる。
