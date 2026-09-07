# Testing the step counter storage

The plugin stores steps in SQLite (`databases/stepcounter.db`, see README "Data storage").
This document covers the on-device test mode that proves the store and the one-time migration
from the old SharedPreferences file behave, plus how to inspect the database with adb.

## Install the plugin from a local checkout

From the app folder (`w2/wellnessentially`):

```bash
cordova plugin remove cordova-plugin-mc-stepcounter
cordova plugin add ../../cordova-plugin-stepcounter --nosave   # --nosave keeps the git URL in package.json
npm run mobile:android
```

`npm run sm` / `npm run setup:install:android` rebuild the platform from `package.json` and pull the
git version, so use them only after the plugin tag is pushed.

## Test mode (`stepcounter.storageTest`)

Every test op is isolated from real data:

- the legacy file used by tests is `UserData_test`, never the real `UserData`;
- every synthetic row has a key in the years 2000-2001, which pruning skips and the app's
  competition-window query never sends to the server;
- `reset` deletes those rows and the test prefs file.

| op | params | what it does | pass when |
|----|--------|--------------|-----------|
| `seed_legacy` | `days` (7), `stepsPerHour` (250) | writes a legacy-shaped `pedometerDayData` / `pedometerHistoryData` / total into `UserData_test` and clears the migration markers | `ok`, `hourRows == days*24` |
| `migrate` | | runs the migration against `UserData_test` (insert-or-ignore, DB wins) | `report.status == "migrated"` |
| `verify` | | compares legacy rows vs DB rows, DB hour rows vs `getHistory()` output, `PRAGMA integrity_check`, journal mode, migration markers | `checks.legacySubsetOfDb`, `checks.historyMatchesDb`, `checks.integrityOk`, `checks.walEnabled`, `checks.migrationMarked`, `checks.noCrashRows` |
| `corrupt_legacy` | `mode`: `garbage` \| `truncate` \| `flag_only` | evicts the prefs cache, writes a broken legacy file, re-runs the migration | `dbUnaffected`; `garbage`/`truncate` leave the migration `deferred` (retry later, `unreadable` after 3 attempts), `flag_only` records `empty` |
| `crash_mid_write` | `mode`: `rollback` \| `kill_service` | `rollback`: inserts 48 rows in a transaction and throws before commit. `kill_service`: the service process opens a transaction, inserts rows and kills itself; START_STICKY restarts it | `rollback`: `crashRowsAfterRollback == 0`, integrity ok. `kill_service`: call `verify` after ~15 s: `checks.noCrashRows`, `checks.integrityOk`, and `isServiceRunning().running` |
| `checkpoint` | | `PRAGMA wal_checkpoint(TRUNCATE)` so an `adb pull` of the single db file is consistent | `ok` |
| `reset` | `wipeDb` + `confirm: "WIPE"` to also wipe real rows | deletes sentinel rows, `UserData_test`, migration markers | `ok` |

On release builds the action needs `params.adminToken = "stepathon-admin-diagnostics"`; the app's
admin panel sends it. Debug builds accept the action without it.

### From the app

Profile → (admin only, Android) **Pedometer storage (admin)** → **Run full sequence**. The panel
runs seed → verify → migrate → verify → corrupt (garbage) → verify → corrupt (flag only) → crash
(rollback) → verify → crash (kill service) → wait 15 s → verify → reset, and shows a PASS/FAIL
badge per step with the individual checks. **Load logs** shows the plugin's persistent log.

### From Chrome DevTools (chrome://inspect)

```js
const st = (op, p = {}) => new Promise((r, j) => stepcounter.storageTest(op, { ...p, adminToken: 'stepathon-admin-diagnostics' }, r, j));
const seq = [
  ['seed_legacy', { days: 7 }], ['verify'], ['migrate'], ['verify'],
  ['corrupt_legacy', { mode: 'garbage' }], ['verify'], ['corrupt_legacy', { mode: 'flag_only' }],
  ['crash_mid_write', { mode: 'rollback' }], ['verify'],
  ['crash_mid_write', { mode: 'kill_service' }], ['__wait', 15000], ['verify'], ['reset'],
];
(async () => {
  for (const [op, p] of seq) {
    if (op === '__wait') { await new Promise((r) => setTimeout(r, p)); continue; }
    const r = await st(op, p);
    console.log(op, r.ok ? 'PASS' : 'FAIL', r.checks || r);
  }
})();
```

Expected: every line `PASS`. After the `migrate` step `verify` must show `legacySubsetOfDb: true`;
after `corrupt_legacy` the DB row counts must not change.

### Real-data upgrade check

On a device still running plugin 0.1.0 with real history:

1. `stepcounter.getHistory(h => console.log(Object.keys(h).length))` and note the count.
2. Install the 0.2.0 build over it and open the app once.
3. `stepcounter.getStorageInfo(console.log)`: `meta.legacy_status == "migrated"` and
   `meta.legacy_hour_rows` equals the count from step 1; `hourRows` is at least that.
4. `stepcounter.getHistory(...)` returns the same keys as before.

The old `UserData` file is kept read-only for 30 days, then deleted together with `DebugLogs`.

### Reboot and force-stop

```bash
adb reboot                                             # then open the app
adb shell am force-stop com.mctechnologies.getvealth   # then open the app
```

After both, `verify` must show the same row counts and today's steps continue from the value
frozen by the shutdown receiver.

## Inspecting the database

```bash
adb logcat -s StepStore StepStoreMigration StepCounterHelper StepCounterService CordovaStepCounter StepStoreTestHooks
adb shell run-as com.mctechnologies.getvealth ls -la databases shared_prefs
adb shell run-as com.mctechnologies.getvealth sqlite3 databases/stepcounter.db \
  "PRAGMA integrity_check; PRAGMA journal_mode; SELECT kind, count(*), min(key), max(key) FROM period GROUP BY kind; SELECT * FROM meta;"
# devices without sqlite3: run storageTest('checkpoint') first, then
adb shell run-as com.mctechnologies.getvealth cat databases/stepcounter.db > stepcounter.db && sqlite3 stepcounter.db "SELECT * FROM meta"
adb shell dumpsys activity services com.mctechnologies.getvealth | grep -A3 StepCounterService
adb shell dumpsys deviceidle whitelist | grep getvealth
```
