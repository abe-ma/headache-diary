# データ層メモ (T-03)
- Room DB 名: `headache_diary.db` (`AppDatabase.NAME`)。version=1、スキーマは `app/schemas/` にコミット。
- DataStore: `settings` → `files/datastore/settings.preferences_pb`。
- WorkManager の DB は `androidx.work.workdb` (同じ database ドメイン)。バックアップ対象から外す:
  `backup_rules.xml` / `data_extraction_rules.xml` の `<include domain="database" path="." />` の後ろに
  `<exclude domain="database" path="androidx.work.workdb" />` (+ `-wal` / `-shm`) を追加。
  Room 側 WAL の取りこぼしを避けるため、DB 側は include 限定でなく workdb の exclude で対応する。(build 経由で反映)
- プリセット薬 id は定義順 1 始まりで固定、自由入力は 1000 以上。プリセット追加は必ず末尾 (open 毎に INSERT OR IGNORE)。
