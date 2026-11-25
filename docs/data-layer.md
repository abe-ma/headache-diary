# データ層メモ
- Room DB 名: `headache_diary.db` (`AppDatabase.NAME`)。version=1、スキーマは `app/schemas/` にコミット。
- DataStore: `settings` → `files/datastore/settings.preferences_pb`。
- WorkManager の DB は `androidx.work.workdb` (同じ database ドメイン)。バックアップ対象から外す:
  `backup_rules.xml` / `data_extraction_rules.xml` の `<include domain="database" path="." />` の後ろに
  `<exclude domain="database" path="androidx.work.workdb" />` (+ `-wal` / `-shm`) を追加。
  Room 側 WAL の取りこぼしを避けるため、DB 側は include 限定でなく workdb の exclude で対応する。(build 経由で反映)
- プリセット薬 id は定義順 1 始まりで固定、自由入力は 1000 以上。プリセット追加は必ず末尾 (open 毎に INSERT OR IGNORE)。

## 通知まわりの仕様メモ
- 効果リマインドをオフ→オンにしたとき、直近 12 時間の未評価の発作で「有効な予約が無い」ものに再度予約する。
  そのため、すでに通知済みの発作でも 12 時間以内なら、オンに切り替え直すと再通知されうる (許容)。
- 起動時の再予約 (NotifyBootstrap) の基準は「起動した時点から 12 時間以内に開始した未評価の発作」。
  バックアップ復元後は WorkManager の記録が無いため、復元した時点の「今」が基準になる (許容)。
  発作の保存時刻は持たないので、予約の遅延は設定値 (既定 120 分) を復元時点から数え直す。
- 設定変更の副作用 (`SettingsChangeHandler`) は各処理を独立に try/catch し、失敗しても設定の保存は成立し、
  アプリは落ちない。取りこぼしは次回起動の NotifyBootstrap で補正される (キャンセルは再送出)。
- 気圧取得 Worker の失敗再試行は 4 回まで。超えたら成功扱いで終え、翌日の定期実行に任せる。

## 前日との気圧差 (FR-404) の補完ルール (T-19 レビュー対応)
- 日次補完は、保存済みの気圧が **現在の登録地域の p(t) と 0.1 hPa 以内で一致する発作にだけ** 気圧差を埋める
  (地域変更後に「旧地域の p(t) − 新地域の p(t−24h)」という無意味な差を作らないため。v2→v3 更新直後の一斉補完も同じ規則)。
  合わない発作は気圧差が null のまま (保存時に気圧と差を同じ地域で解決する経路は変更なし)。
- 24 時間前のデータが取れない発作は、31 日を過ぎるまで毎日の補完で再計算され続ける (pressureAt を 2 回呼ぶだけなので許容)。
- 気圧差の表記 (符号付き小数 1 桁、絶対値の HALF_UP) は `domain/PressureFormat` に 1 か所だけ実装し、UI・CSV・PDF で共用する。

## バックアップ (FR-600〜607) の読み込み仕様 (T-21a/T-21c)
- 形式: `headache-diary-backup` / formatVersion 1。サイズ上限 20MB (超過は TOO_LARGE)。壊れたもの・上限や範囲外の値は CORRUPT、形式違いは NOT_BACKUP、新しい形式は UNSUPPORTED_VERSION。いずれも DB・設定は変わらない。
- 上限 (`BackupLimits`、超過・範囲外は CORRUPT): 発作 100,000 件以下 / 1 発作あたり服薬 20 件以下 / 自由入力の薬 1,000 件以下 / 薬名 100 文字以下 /
  開始日時 2000-01-01T00:00Z 〜 (現在 + 1 日) / 気圧 300〜1200 hPa / 気圧差 ±150 hPa / 錠数 1〜99。
- 設定の復元 (通知のオン/オフは常にオフ): 値の範囲は UI と同じ定数 (`domain/SettingsLimits`。しきい値 1〜20 hPa、効果リマインドの遅延は選択肢 30/60/120/180/360 分)。範囲外は既定値 (6.0 hPa / 120 分 / 20:00)。
  **地域は、ファイルの値が null または 47 都道府県コード外なら現在の地域を維持**し、有効な値のときだけ上書きする (変わったら気圧を即時取得)。
- 重複判定は (開始日時 ms, 痛みの強さ, 服薬の (薬, 数量) の集合)。気圧・気圧差はファイルの値のまま再計算しない。
- UI 側の定数 (`ui/settings/SettingsViewModel`) は `SettingsLimits` と同じ値で、`BackupRepositoryTest` が一致を検証する。
