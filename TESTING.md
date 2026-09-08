# Testing the step counter storage

The plugin stores steps in SQLite (`databases/stepcounter.db`, see README "Data storage"). This
document covers how to prove the store and the one-time migration from the old SharedPreferences
file on a real device, and how to inspect the database with adb. All commands assume a debuggable
build (`run-as` needs it) and the package `com.mhventures.dev.stepathon`; adjust for other builds.

## Install the plugin from a local checkout

From the app folder (`w2/wellnessentially`):

```bash
cordova plugin remove cordova-plugin-mc-stepcounter
cordova plugin add ../../cordova-plugin-stepcounter --nosave   # --nosave keeps the git URL in package.json
npm run wa                                                     # parcel watch + deploy to the device
```

`npm run sm` / `npm run setup:install:android` rebuild the platform from `package.json` and pull the
git version, so use them only after the plugin tag is pushed.

## 1. Upgrade path: a device with the old store opens the new version

This is the state every existing user is in after a store update: a populated `UserData.xml`, no
database yet. The migration must copy everything and today's counter must continue, not restart.

1. Generate a legacy-shaped file. Keys are local-time `yyyy-MM-dd` (day) and `yyyy-MM-dd HH`
   (hour); each entry is `{"steps","offset","buffer"}`. Make today's day entry consistent with the
   live sensor value `S` (read it from `adb logcat -s StepCounterService:I | grep STEP_UPDATE`):
   `{"steps": X + S, "offset": 0, "buffer": X}` so the counter continues at `X + S`.
   Store the JSON strings XML-escaped in a SharedPreferences map:

   ```xml
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map>
       <string name="pedometerDayData">{&quot;2026-09-06&quot;:{&quot;steps&quot;:5885,&quot;offset&quot;:1000,&quot;buffer&quot;:0}, ...}</string>
       <string name="pedometerHistoryData">{&quot;2026-09-06 08&quot;:{&quot;steps&quot;:250,&quot;offset&quot;:1000,&quot;buffer&quot;:0}, ...}</string>
       <int name="PEDOMETER_TOTAL_COUNT_PREF" value="16213" />
   </map>
   ```

2. Put it in place and wipe the database, exactly like a first open after the update:

   ```bash
   P=com.mhventures.dev.stepathon
   adb push UserData.xml /data/local/tmp/UserData.xml
   adb shell am force-stop $P
   adb shell "run-as $P sh -c 'cp /data/local/tmp/UserData.xml shared_prefs/UserData.xml && chmod 660 shared_prefs/UserData.xml && rm -f databases/stepcounter.db databases/stepcounter.db-wal databases/stepcounter.db-shm'"
   adb shell monkey -p $P -c android.intent.category.LAUNCHER 1
   ```

3. Open the app (it syncs, which starts the service) and read the result (section 3). Expected:
   - `meta.legacy_status = migrated`, `legacy_day_rows` / `legacy_hour_rows` equal to the file,
     `total_count` taken from the file;
   - `period` contains every legacy row, `getHistory()` returns the same keys;
   - today's day row continues from the legacy value (`X + S`), no reset to 0;
   - the log table has `Legacy migrated from UserData: inserted=N ignored=0 conflicts=0`;
   - `shared_prefs/UserData.xml` is still there (read-only fallback, deleted after 30 days).

   Verified on a moto g13 (Android 13) on 2026-09-07: 3 day rows, 59 hour rows, `inserted=62`,
   today continued at 4,443 with the sensor at 68.

4. Corrupt-file variant. Replace `UserData.xml` with garbage (400 bytes of non-XML) or a valid map
   holding only `<boolean name="logs_migration_completed" value="true" />`, wipe the database again
   and relaunch. Expected: garbage leaves the migration `deferred` (`migration_attempts` grows,
   `unreadable` after 3 opens) and never creates rows; the flag-only file records `empty`.

## 2. Service survival

```bash
adb shell am force-stop $P                       # then open the app: rows unchanged, service restarted by the next sync
adb shell run-as $P kill $(adb shell "run-as $P pidof $P:cordovapluginstepcounter")   # START_STICKY brings it back
adb reboot                                       # then open the app: today continues from the value frozen at shutdown
adb shell dumpsys activity services $P | grep -A3 StepCounterService   # isForeground=true, foregroundId=777
```

## 3. Inspecting the database

The device usually has no `sqlite3`; pull the three files and open them on the Mac (the WAL is
replayed on open):

```bash
for f in stepcounter.db stepcounter.db-wal stepcounter.db-shm; do adb shell "run-as $P cat databases/$f" > $f; done
sqlite3 stepcounter.db "PRAGMA integrity_check; SELECT * FROM meta; SELECT kind,count(*),min(key),max(key) FROM period GROUP BY kind; SELECT datetime(at/1000,'unixepoch'),level,tag,msg FROM log ORDER BY id DESC LIMIT 20;"
adb logcat -s StepStore StepStoreMigration StepCounterHelper StepCounterService CordovaStepCounter
```

From the app's JavaScript (chrome://inspect): `stepcounter.getStorageInfo(console.log)`,
`stepcounter.isServiceRunning(console.log)`, `stepcounter.getLogs(console.log)`.
