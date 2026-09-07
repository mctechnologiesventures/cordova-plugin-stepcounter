package com.mctechnologies.cordovapluginstepcounter;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Storage test mode, reachable only through the `storage_test` plugin action.
 *
 * Isolation rules, so a test can never touch real step data:
 *  - the legacy file used by tests is "UserData_test", never the real "UserData";
 *  - every synthetic row uses a key in the years 2000-2001 (below StepStore.SENTINEL_KEY_LIMIT),
 *    which pruning skips and the app's competition-window query never sends to the server.
 */
class StepStoreTestHooks {

    private static final String TAG = "StepStoreTestHooks";

    static final String TEST_PREFS = "UserData_test";
    static final String EXTRA_CRASH_MID_WRITE = "test_crash_mid_write";
    private static final String SENTINEL_DAY_PREFIX = "2001-01-";
    private static final String CRASH_DAY_PREFIX = "2000-06-";

    static JSONObject run(@NonNull Context context, @NonNull String op, @NonNull JSONObject params) {
        JSONObject result = new JSONObject();
        StepStore store = StepStore.getInstance(context);
        try {
            result.put("op", op);
            switch (op) {
                case "seed_legacy":
                    seedLegacy(context, store, params, result);
                    break;
                case "migrate":
                    result.put("report", StepStoreMigration.run(context, store, true, TEST_PREFS));
                    result.put("ok", true);
                    break;
                case "verify":
                    verify(context, store, result);
                    break;
                case "corrupt_legacy":
                    corruptLegacy(context, store, params, result);
                    break;
                case "crash_mid_write":
                    crashMidWrite(context, store, params, result);
                    break;
                case "checkpoint":
                    store.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close();
                    result.put("ok", true);
                    break;
                case "reset":
                    reset(context, store, params, result);
                    break;
                default:
                    result.put("ok", false);
                    result.put("error", "Unknown storage_test op: " + op);
            }
        } catch (Exception ex) {
            Log.e(TAG, "storage_test " + op + " failed: " + ex.getMessage(), ex);
            try {
                result.put("ok", false);
                result.put("error", String.valueOf(ex.getMessage()));
            } catch (Exception ignored) {
                // best effort
            }
        }
        return result;
    }

    //region seed_legacy

    private static void seedLegacy(Context context, StepStore store, JSONObject params, JSONObject result) throws Exception {
        int days = Math.max(1, Math.min(31, params.optInt("days", 7)));
        int stepsPerHour = Math.max(1, params.optInt("stepsPerHour", 250));

        JSONObject dayData = new JSONObject();
        JSONObject hourData = new JSONObject();
        int offset = 1000;
        int total = 0;
        for (int d = 0; d < days; d++) {
            String dayKey = SENTINEL_DAY_PREFIX + two(d + 1);
            int daySteps = 0;
            for (int h = 0; h < 24; h++) {
                String hourKey = dayKey + " " + two(h);
                int steps = stepsPerHour + (h * 7) % 50;
                hourData.put(hourKey, entry(steps, offset + daySteps, 0));
                daySteps += steps;
            }
            dayData.put(dayKey, entry(daySteps, offset, 0));
            offset += daySteps;
            total += daySteps;
        }

        SharedPreferences prefs = legacyPrefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.putString(StepStoreMigration.LEGACY_KEY_DAY, dayData.toString());
        editor.putString(StepStoreMigration.LEGACY_KEY_HOUR, hourData.toString());
        editor.putInt(StepStoreMigration.LEGACY_KEY_TOTAL, total);
        boolean committed = editor.commit();

        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            StepStoreMigration.clearMarkers(store, db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        StepStoreMigration.LegacySnapshot legacy = StepStoreMigration.readLegacy(context, TEST_PREFS);
        result.put("ok", committed && legacy.rows.size() == days * 25);
        result.put("committed", committed);
        result.put("days", days);
        result.put("dayRows", legacy.dayRows);
        result.put("hourRows", legacy.hourRows);
        result.put("total", total);
        result.put("checksum", legacy.checksum);
        result.put("fileSize", legacy.fileSize);
    }

    private static String two(int value) {
        return String.format(Locale.US, "%02d", value);
    }

    private static JSONObject entry(int steps, int offset, int buffer) throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("steps", steps);
        entry.put("offset", offset);
        entry.put("buffer", buffer);
        return entry;
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences legacyPrefs(Context context) {
        return context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    //endregion

    //region verify

    private static void verify(Context context, StepStore store, JSONObject result) throws Exception {
        SQLiteDatabase db = store.getReadableDatabase();
        StepStoreMigration.LegacySnapshot legacy = StepStoreMigration.readLegacy(context, TEST_PREFS);

        JSONObject legacyInfo = new JSONObject();
        legacyInfo.put("present", legacy.fileExists);
        legacyInfo.put("fileSize", legacy.fileSize);
        legacyInfo.put("dayRows", legacy.dayRows);
        legacyInfo.put("hourRows", legacy.hourRows);
        legacyInfo.put("checksum", legacy.checksum);
        legacyInfo.put("mapEmpty", legacy.mapEmpty);
        legacyInfo.put("parseError", legacy.parseError == null ? JSONObject.NULL : legacy.parseError);
        result.put("legacy", legacyInfo);

        // Every legacy row must be in the DB with the same values.
        JSONArray missing = new JSONArray();
        JSONArray conflicts = new JSONArray();
        for (StepStoreMigration.LegacyRow row : legacy.rows) {
            StepStore.PeriodRow existing = store.getPeriod(db, row.kind, row.key);
            if (existing == null) {
                if (missing.length() < 20) missing.put(row.kind + ":" + row.key);
            } else if (existing.steps != row.steps || existing.offset != row.offset || existing.buffer != row.buffer) {
                if (conflicts.length() < 20) conflicts.put(row.kind + ":" + row.key + " legacy=" + row.steps + " db=" + existing.steps);
            }
        }
        boolean legacySubsetOfDb = !legacy.rows.isEmpty() && missing.length() == 0 && conflicts.length() == 0;

        // DB side.
        List<String> hourLines = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT key, steps, step_offset, buffer FROM period WHERE kind=?", new String[]{StepStore.KIND_HOUR})) {
            while (c.moveToNext()) {
                hourLines.add(StepStore.KIND_HOUR + "|" + c.getString(0) + "|" + c.getInt(1) + "|" + c.getInt(2) + "|" + c.getInt(3));
            }
        }
        String dbHourChecksum = StepStoreMigration.checksumLines(new ArrayList<>(hourLines));

        JSONObject dbInfo = new JSONObject();
        dbInfo.put("dayRows", store.countPeriods(StepStore.KIND_DAY, false));
        dbInfo.put("hourRows", hourLines.size());
        dbInfo.put("sentinelRows", store.countPeriods(StepStore.KIND_DAY, true) + store.countPeriods(StepStore.KIND_HOUR, true));
        dbInfo.put("crashRows", countCrashRows(db));
        dbInfo.put("hourChecksum", dbHourChecksum);
        dbInfo.put("totalCount", store.getTotalCount());
        result.put("db", dbInfo);

        // The JS-visible shape must be exactly the DB content.
        JSONObject history = store.getHistoryJson();
        List<String> historyLines = new ArrayList<>();
        Iterator<String> keys = history.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = history.getJSONObject(key);
            historyLines.add(StepStore.KIND_HOUR + "|" + key + "|" + entry.getInt("steps") + "|" + entry.getInt("offset") + "|" + entry.getInt("buffer"));
        }
        String historyChecksum = StepStoreMigration.checksumLines(historyLines);
        JSONObject historyInfo = new JSONObject();
        historyInfo.put("rows", history.length());
        historyInfo.put("checksum", historyChecksum);
        result.put("history", historyInfo);

        String integrity = store.pragmaString(db, "integrity_check");
        String journalMode = store.pragmaString(db, "journal_mode");
        result.put("integrity", integrity);
        result.put("journalMode", journalMode);

        JSONObject meta = new JSONObject();
        try (Cursor c = db.rawQuery("SELECT key, value FROM meta ORDER BY key", null)) {
            while (c.moveToNext()) meta.put(c.getString(0), c.getString(1));
        }
        result.put("meta", meta);

        JSONObject checks = new JSONObject();
        checks.put("legacySubsetOfDb", legacySubsetOfDb);
        checks.put("historyMatchesDb", history.length() == hourLines.size() && historyChecksum.equals(dbHourChecksum));
        checks.put("integrityOk", "ok".equalsIgnoreCase(integrity));
        checks.put("walEnabled", "wal".equalsIgnoreCase(journalMode));
        checks.put("migrationMarked", meta.has(StepStoreMigration.META_MIGRATED_AT));
        checks.put("noCrashRows", countCrashRows(db) == 0);
        result.put("checks", checks);
        result.put("missingInDb", missing);
        result.put("conflicts", conflicts);
        result.put("ok", checks.getBoolean("integrityOk") && checks.getBoolean("historyMatchesDb"));
    }

    //endregion

    //region corrupt_legacy

    private static void corruptLegacy(Context context, StepStore store, JSONObject params, JSONObject result) throws Exception {
        String mode = params.optString("mode", "garbage");
        int dayBefore = store.countPeriods(StepStore.KIND_DAY, false);
        int hourBefore = store.countPeriods(StepStore.KIND_HOUR, false);

        // Evict the in-process cache first so the next getSharedPreferences() really re-reads
        // the file we are about to write: that is what makes this a faithful reproduction.
        StepStoreMigration.deleteLegacyFiles(context, TEST_PREFS);

        String q = "&quot;";
        String validXml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                + "    <string name=\"" + StepStoreMigration.LEGACY_KEY_DAY + "\">"
                + "{" + q + "2001-01-01" + q + ":{" + q + "steps" + q + ":6000," + q + "offset" + q + ":1000," + q + "buffer" + q + ":0},"
                + q + "2001-01-02" + q + ":{" + q + "steps" + q + ":6500," + q + "offset" + q + ":7000," + q + "buffer" + q + ":0},"
                + q + "2001-01-03" + q + ":{" + q + "steps" + q + ":7000," + q + "offset" + q + ":13500," + q + "buffer" + q + ":0}}</string>\n"
                + "    <int name=\"" + StepStoreMigration.LEGACY_KEY_TOTAL + "\" value=\"19500\" />\n"
                + "</map>\n";
        String content;
        switch (mode) {
            case "truncate":
                // A commit interrupted half way: the XML is cut before the closing tags.
                content = validXml.substring(0, validXml.length() / 2);
                break;
            case "flag_only":
                // The exact file the old bug left behind: only the log-migration flag survived.
                content = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                        + "    <boolean name=\"logs_migration_completed\" value=\"true\" />\n</map>\n";
                break;
            case "garbage":
            default:
                StringBuilder garbage = new StringBuilder("<<not xml at all>>");
                while (garbage.length() < 400) garbage.append("  garbage");
                content = garbage.toString();
        }

        File file = StepStoreMigration.legacyFile(context, TEST_PREFS);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
        }

        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            store.setMeta(db, StepStoreMigration.META_MIGRATED_AT, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        JSONObject report = StepStoreMigration.run(context, store, true, TEST_PREFS);

        int dayAfter = store.countPeriods(StepStore.KIND_DAY, false);
        int hourAfter = store.countPeriods(StepStore.KIND_HOUR, false);
        boolean unaffected = dayAfter == dayBefore && hourAfter == hourBefore;
        String status = report.optString("status", "");
        boolean expected = "flag_only".equals(mode)
                ? StepStoreMigration.STATUS_EMPTY.equals(status)
                : ("deferred".equals(status) || StepStoreMigration.STATUS_UNREADABLE.equals(status));

        result.put("mode", mode);
        result.put("fileSize", file.length());
        result.put("dbBefore", dayBefore + "/" + hourBefore);
        result.put("dbAfter", dayAfter + "/" + hourAfter);
        result.put("dbUnaffected", unaffected);
        result.put("legacyStatusNow", status);
        result.put("migrationAttempts", report.optInt("attempts", 0));
        result.put("report", report);
        result.put("ok", unaffected && expected);
    }

    //endregion

    //region crash_mid_write

    private static void crashMidWrite(Context context, StepStore store, JSONObject params, JSONObject result) throws Exception {
        String mode = params.optString("mode", "rollback");
        if ("kill_service".equals(mode)) {
            Intent intent = new Intent(context, StepCounterService.class);
            intent.putExtra(EXTRA_CRASH_MID_WRITE, true);
            ContextCompat.startForegroundService(context, intent);
            result.put("mode", mode);
            result.put("requested", true);
            result.put("ok", true);
            result.put("hint", "Service inserts crash rows in an open transaction and kills its process. "
                    + "Call verify after ~15s: expect checks.noCrashRows, integrityOk, and the service running again.");
            return;
        }

        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            insertCrashRows(store, db);
            throw new IllegalStateException("simulated crash before commit");
        } catch (IllegalStateException expected) {
            // fall through to endTransaction() without setTransactionSuccessful()
        } finally {
            db.endTransaction();
        }

        int crashRows = countCrashRows(db);
        String integrity = store.pragmaString(db, "integrity_check");
        result.put("mode", mode);
        result.put("crashRowsAfterRollback", crashRows);
        result.put("integrity", integrity);
        result.put("ok", crashRows == 0 && "ok".equalsIgnoreCase(integrity));
    }

    /** Inserts the rows the crash tests use; caller owns the transaction. */
    static void insertCrashRows(StepStore store, SQLiteDatabase db) {
        long now = System.currentTimeMillis();
        for (int d = 1; d <= 2; d++) {
            for (int h = 0; h < 24; h++) {
                String key = CRASH_DAY_PREFIX + two(d) + " " + two(h);
                store.putPeriod(db, StepStore.KIND_HOUR, key, 1, 0, 0, now);
            }
        }
    }

    static int countCrashRows(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM period WHERE key LIKE ?", new String[]{CRASH_DAY_PREFIX + "%"})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Called from the service process when it receives EXTRA_CRASH_MID_WRITE. Never returns. */
    static void crashServiceMidWrite(Context context) {
        StepStore store = StepStore.getInstance(context);
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransactionNonExclusive();
        insertCrashRows(store, db);
        store.log("WARN", TAG, "crash_mid_write: killing service process with an open transaction");
        Log.w(TAG, "crash_mid_write: killing process " + android.os.Process.myPid());
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    //endregion

    //region reset

    private static void reset(Context context, StepStore store, JSONObject params, JSONObject result) throws Exception {
        boolean wipeDb = params.optBoolean("wipeDb", false) && "WIPE".equals(params.optString("confirm", ""));
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            if (wipeDb) {
                db.execSQL("DELETE FROM period");
                db.execSQL("DELETE FROM meta");
            } else {
                db.execSQL("DELETE FROM period WHERE key < ?", new Object[]{StepStore.SENTINEL_KEY_LIMIT});
                StepStoreMigration.clearMarkers(store, db);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        StepStoreMigration.deleteLegacyFiles(context, TEST_PREFS);
        result.put("wipedDb", wipeDb);
        result.put("sentinelRows", store.countPeriods(StepStore.KIND_DAY, true) + store.countPeriods(StepStore.KIND_HOUR, true));
        result.put("ok", true);
    }

    //endregion
}
