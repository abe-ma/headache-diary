# リリース署名メモ

**鍵 (keystore) はこのリポジトリに作成・コミットしない。** `.gitignore` で `*.jks` / `*.keystore` / `keystore.properties` を除外済み。

## 現状
- `app/keystore.properties` が**無い**環境 (CI・他人のクローン) の `assembleRelease` / `bundleRelease` は従来どおり **署名なし** (`app-release-unsigned.apk` / `app-release.aab`) でビルドが通る。
- `app/keystore.properties` が**ある**環境では、release が自動で署名される (下記「GitHub Releases / サイドロード配布」)。キーが欠けていると、署名なしのまま作らないようビルドが失敗する (欠けたキー名のみ表示。値は出さない)。
- 署名なし APK は端末にインストールできない。端末での動作確認は debug APK (自動で debug 鍵署名) か、署名付き release を使う。

## GitHub Releases / サイドロード配布 (署名付き APK)
Play ストアを使わず、GitHub Releases で APK を配布する場合の手順。**鍵・パスワードはリポジトリに置かない・コミットしない・ログに貼らない。**

### 1. 鍵の作成 (開発者本人が一度だけ、リポジトリ外で)
```
mkdir -p ~/.android-keys && chmod 700 ~/.android-keys
keytool -genkeypair -v -keystore ~/.android-keys/headache-diary-release.jks \
  -alias headache-diary -keyalg RSA -keysize 4096 -validity 36500
```
- パスワードは対話入力する (コマンドラインに書かない)。**鍵ファイルとパスワードは安全な場所に複数バックアップ**。
  紛失すると、既存インストールへ上書き更新できる APK を二度と作れない (同じ署名でないと更新不可)。

### 2. 署名設定
- `app/keystore.properties.example` を `app/keystore.properties` にコピーし、storeFile / storePassword / keyAlias / keyPassword を実際の値にする。
  `storeFile` は絶対パスか `~/` 始まり。`keystore.properties` と `*.jks` は `.gitignore` 済み。
  パスワードは平文で書かれるため、ファイルの権限を自分だけに絞る (`chmod 600 app/keystore.properties`)。値をシェルの履歴に残さず作るには、`read -s -p "password: " P` で入力して `printf` でファイルに書き出す。`storeFile` の `~/` 展開は Linux/macOS のみ (Windows では絶対パスを使う)。
- v2 / v3 署名は AGP が付与する (minSdk 26 で v2 以上が有効。v1 は無効)。zipalign も AGP が自動で行う。

### 3. ビルド
```
./gradlew --no-parallel --max-workers=2 assembleRelease
```
別ディレクトリ (`git archive` の展開物など) でビルドする場合は、`keystore.properties` をコピーせず場所だけ渡せる: `./gradlew -PkeystoreProperties=/絶対パス/keystore.properties assembleRelease`。
出力: `app/build/outputs/apk/release/headache-diary-<versionName>-release.apk` (署名情報が無い場合は `...-release-unsigned.apk`) (例 `headache-diary-0.1.0-release.apk`)。
配布時は GitHub Releases のアセット名を `headache-diary-<versionName>.apk` にしてもよい。versionName / versionCode は `app/build.gradle.kts` で更新する (更新インストールには versionCode の増加が必須)。

### 4. 署名の検証と証明書指紋の控え
`apksigner` は Android SDK の build-tools にある (`$ANDROID_HOME/build-tools/<版>/apksigner`)。
```
APKSIGNER=$ANDROID_HOME/build-tools/36.0.0/apksigner
$APKSIGNER verify --verbose --print-certs app/build/outputs/apk/release/headache-diary-0.1.0-release.apk
```
- `Verifies` と `Verified using v2 scheme: true` / `v3 scheme: true` を確認する。
- 出力の `Signer #1 certificate SHA-256 digest` が**配布元の証明書指紋**。初回リリース時に控え (README やリリースノートに記載)、以後のリリースで同じ値であることを毎回確認する。鍵ファイルからも確認できる: `keytool -list -v -keystore ~/.android-keys/headache-diary-release.jks -alias headache-diary` (パスワードは対話入力)。
- zipalign の確認: `$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 <apk>` (16KB ページ境界のアラインを含む。`Verification successful` を確認)。
- 配布物の sha256 も控える: `sha256sum headache-diary-0.1.0-release.apk` (リリースノートに記載すると利用者が検証できる)。

### 5. 利用者側の注意
- 署名鍵が debug 版と異なるため、debug 版を入れた端末では一度アンインストールしないと release 版に更新できない (データは消える)。
- 提供元不明のアプリのインストール許可が必要。インストール前に指紋/sha256 を照合できる。

## Google Play で公開する場合 (Play App Signing 前提)
1. Play Console でアプリを作成し、**Play App Signing** を有効にする (新規アプリは AAB 提出が必須)。
2. **アップロード鍵**を開発者の手元で作る (値は各自が決める。紛失に備え安全な場所に複数バックアップ):
   `keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`
3. 署名情報は Git 管理外の `app/keystore.properties` に置く (storeFile / storePassword / keyAlias / keyPassword。`app/build.gradle.kts` が存在時のみ読み込む。雛形は `keystore.properties.example`)。
   CI を使う場合は鍵とパスワードを CI のシークレットで渡し、実行時に `keystore.properties` を生成する。
4. `./gradlew bundleRelease` で署名済み AAB を生成し、Play Console の内部テストトラックへアップロード。Google が配信用の App Signing 鍵で再署名する。
5. 公開前チェック: applicationId (`io.github.abe_ma.headachediary`) は公開後に変更不可。versionCode は提出ごとに増やす。データセーフティ (健康情報は端末内保存、外部送信は丸めた座標のみ) とプライバシーポリシー URL を用意する。

## 注意
- アップロード鍵の紛失時は Play Console から鍵リセット申請が必要 (App Signing 有効時)。
- App Signing を使わず自前で署名して配布する場合 (サイドロード等) は、`apksigner` で zipalign 後に署名する。その鍵は紛失すると更新インストールができなくなる。
