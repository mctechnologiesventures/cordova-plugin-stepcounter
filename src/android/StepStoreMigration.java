package com.mctechnologies.cordovapluginstepcounter;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One-time, idempotent copy of the legacy SharedPreferences store ("UserData") into StepStore.
 *
 * Rules that make this safe no matter which process runs it first, or how often:
 *  - Legacy rows only FILL GAPS (insert-or-ignore). Existing DB rows are never overwritten
 *    or deleted, so an empty or garbled legacy read can never wipe anything.
 *  - A legacy file that exists but reads back as an empty map (the exact failure mode that
 *    wiped devices under the old store) is NOT treated as "nothing to migrate": we retry on
 *    the next open, up to MAX_ATTEMPTS, then give up and record `unreadable`.
 *  - The legacy file is kept read-only for RETENTION_MS after a successful migration as a
 *    recovery fallback, then deleted together with the old "DebugLogs" prefs file.
 */
class StepStoreMigration {

    private static final String TAG = "StepStoreMigration";

    static final String LEGACY_PREFS = "UserData";
    static final String LEGACY_LOGS_PREFS = "DebugLogs";
    static final String LEGACY_KEY_DAY = "pedometerDayData";
    static final String LEGACY_KEY_HOUR = "pedometerHistoryData";
    static final String LEGACY_KEY_TOTAL = "PEDOMETER_TOTAL_COUNT_PREF";

    static final String META_MIGRATED_AT = "legacy_migrated_at";
    static final String META_STATUS = "legacy_status";
    static final String META_DAY_ROWS = "legacy_day_rows";
    static final String META_HOUR_ROWS = "legacy_hour_rows";
    static final String META_CHECKSUM = "legacy_checksum";
    static final String META_CONFLICTS = "legacy_conflicts";
    static final String META_ATTEMPTS = "migration_attempts";
    static final String META_DELETED_AT = "legacy_deleted_at";

    static final String STATUS_MIGRATED = "migrated";
    static final String STATUS_EMPTY = "empty";
    static final String STATUS_UNREADABLE = "unreadable";

    static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    static final int MAX_ATTEMPTS = 3;
    /** A prefs XML with none of our keys is ~100 bytes; anything larger that reads as empty is suspect. */
    static final int EMPTY_FILE_THRESHOLD_BYTES = 200;

    static final class LegacyRow {
        final String kind;
        final String key;
        final int steps;
        final int offset;
        final int buffer;

        LegacyRow(String kind, String key, int steps, int offset, int buffer) {
            this.kind = kind;
            this.key = key;
            this.steps = steps;
            this.offset = offset;
            this.buffer = buffer;
        }

        String line() {
            return kind + "|" + key + "|" + steps + "|" + offset + "|" + buffer;
        }
    }

    /** Snapshot of what the legacy prefs file currently holds. */
    static final class LegacySnapshot {
        final List<LegacyRow> rows = new ArrayList<>();
        int dayRows;
        int hourRows;
        Integer total;
        boolean fileExists;
        long fileSize;
        boolean mapEmpty;
        String parseError;
        String checksum;
    }

    static JSONObject runIfNeeded(@NonNull Context context, @NonNull StepStore store) {
        return run(context, store, false, LEGACY_PREFS);
    }

    static JSONObject run(@NonNull Context context, @NonNull StepStore store, boolean force, @NonNull String prefsName) {
        JSONObject report = new JSONObject();
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            String migratedAt = store.getMeta(db, META_MIGRATED_AT);

            if (!force && migratedAt != null) {
                maybeDeleteLegacy(context, store, db, migratedAt, now);
                report.put("status", "already_migrated");
                report.put("legacyStatus", store.getMeta(db, META_STATUS));
                db.setTransactionSuccessful();
                return report;
            }

            LegacySnapshot legacy = readLegacy(context, prefsName);
            report.put("prefsName", prefsName);
            report.put("legacyFileExists", legacy.fileExists);
            report.put("legacyFileSize", legacy.fileSize);
            report.put("legacyDayRows", legacy.dayRows);
            report.put("legacyHourRows", legacy.hourRows);
            report.put("legacyChecksum", legacy.checksum);
            if (legacy.parseError != null) report.put("parseError", legacy.parseError);

            if (!legacy.rows.isEmpty()) {
                int inserted = 0;
                int ignored = 0;
                int conflicts = 0;
                for (LegacyRow row : legacy.rows) {
                    StepStore.PeriodRow existing = store.getPeriod(db, row.kind, row.key);
                    if (existing == null) {
                        store.putPeriod(db, row.kind, row.key, row.steps, row.offset, row.buffer, now);
                        inserted++;
                    } else {
                        ignored++;
                        if (existing.steps != row.steps || existing.offset != row.offset || existing.buffer != row.buffer) {
                            conflicts++;
                        }
                    }
                }
                if (legacy.total != null && store.getMeta(db, StepStore.META_TOTAL_COUNT) == null) {
                    store.setMeta(db, StepStore.META_TOTAL_COUNT, String.valueOf(legacy.total));
                }
                markMigrated(store, db, now, STATUS_MIGRATED, legacy, conflicts);
                report.put("status", STATUS_MIGRATED);
                report.put("inserted", inserted);
                report.put("ignored", ignored);
                report.put("conflicts", conflicts);
                store.log("INFO", TAG, "Legacy migrated from " + prefsName + ": inserted=" + inserted + " ignored=" + ignored +
                        " conflicts=" + conflicts + " day=" + legacy.dayRows + " hour=" + legacy.hourRows);
            } else if (legacy.mapEmpty && legacy.fileExists && legacy.fileSize > EMPTY_FILE_THRESHOLD_BYTES) {
                // The file has content but the platform handed us an empty map: exactly the
                // mid-write / corrupt-XML case. Never conclude "nothing to migrate" from it.
                int attempts = parseInt(store.getMeta(db, META_ATTEMPTS)) + 1;
                store.setMeta(db, META_ATTEMPTS, String.valueOf(attempts));
                report.put("attempts", attempts);
                if (attempts >= MAX_ATTEMPTS) {
                    markMigrated(store, db, now, STATUS_UNREADABLE, legacy, 0);
                    report.put("status", STATUS_UNREADABLE);
                    store.log("ERROR", TAG, "Legacy prefs " + prefsName + " unreadable after " + attempts + " attempts (size=" +
                            legacy.fileSize + ")");
                } else {
                    report.put("status", "deferred");
                    store.log("WARN", TAG, "Legacy prefs " + prefsName + " read as empty (size=" + legacy.fileSize +
                            "), attempt " + attempts + ", will retry");
                }
            } else {
                markMigrated(store, db, now, STATUS_EMPTY, legacy, 0);
                report.put("status", STATUS_EMPTY);
                store.log("INFO", TAG, "No legacy step data in " + prefsName + " (exists=" + legacy.fileExists +
                        " size=" + legacy.fileSize + ")");
            }

            db.setTransactionSuccessful();
        } catch (Exception ex) {
            Log.e(TAG, "Migration failed: " + ex.getMessage(), ex);
            try {
                report.put("status", "error");
                report.put("error", String.valueOf(ex.getMessage()));
            } catch (Exception ignored) {
                // report is best effort
            }
        } finally {
            db.endTransaction();
        }
        return report;
    }

    private static void markMigrated(StepStore store, SQLiteDatabase db, long now, String status, LegacySnapshot legacy, int conflicts) {
        store.setMeta(db, META_MIGRATED_AT, String.valueOf(now));
        store.setMeta(db, META_STATUS, status);
        store.setMeta(db, META_DAY_ROWS, String.valueOf(legacy.dayRows));
        store.setMeta(db, META_HOUR_ROWS, String.valueOf(legacy.hourRows));
        store.setMeta(db, META_CHECKSUM, legacy.checksum);
        store.setMeta(db, META_CONFLICTS, String.valueOf(conflicts));
        store.setMeta(db, META_ATTEMPTS, null);
    }

    /** Clears every migration marker so the next open (or a forced run) migrates again. */
    static void clearMarkers(StepStore store, SQLiteDatabase db) {
        store.setMeta(db, META_MIGRATED_AT, null);
        store.setMeta(db, META_STATUS, null);
        store.setMeta(db, META_DAY_ROWS, null);
        store.setMeta(db, META_HOUR_ROWS, null);
        store.setMeta(db, META_CHECKSUM, null);
        store.setMeta(db, META_CONFLICTS, null);
        store.setMeta(db, META_ATTEMPTS, null);
        store.setMeta(db, META_DELETED_AT, null);
    }

    private static void maybeDeleteLegacy(Context context, StepStore store, SQLiteDatabase db, String migratedAt, long now) {
        if (store.getMeta(db, META_DELETED_AT) != null) return;
        long migratedAtMs;
        try {
            migratedAtMs = Long.parseLong(migratedAt);
        } catch (NumberFormatException ex) {
            return;
        }
        if (now - migratedAtMs < RETENTION_MS) return;
        deleteLegacyFiles(context, LEGACY_PREFS);
        deleteLegacyFiles(context, LEGACY_LOGS_PREFS);
        store.setMeta(db, META_DELETED_AT, String.valueOf(now));
        store.log("INFO", TAG, "Legacy prefs files deleted after retention period");
    }

    static void deleteLegacyFiles(Context context, String prefsName) {
        try {
            context.deleteSharedPreferences(prefsName);
        } catch (Exception ex) {
            Log.w(TAG, "deleteSharedPreferences(" + prefsName + ") failed: " + ex.getMessage());
        }
    }

    static File legacyFile(Context context, String prefsName) {
        return new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"), prefsName + ".xml");
    }

    @SuppressWarnings("deprecation")
    static LegacySnapshot readLegacy(Context context, String prefsName) {
        LegacySnapshot snapshot = new LegacySnapshot();
        File file = legacyFile(context, prefsName);
        snapshot.fileExists = file.exists();
        snapshot.fileSize = snapshot.fileExists ? file.length() : 0;

        SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
        Map<String, ?> all = prefs.getAll();
        snapshot.mapEmpty = all == null || all.isEmpty();

        snapshot.dayRows = parseRows(prefs, LEGACY_KEY_DAY, StepStore.KIND_DAY, snapshot);
        snapshot.hourRows = parseRows(prefs, LEGACY_KEY_HOUR, StepStore.KIND_HOUR, snapshot);
        if (prefs.contains(LEGACY_KEY_TOTAL)) {
            try {
                snapshot.total = prefs.getInt(LEGACY_KEY_TOTAL, 0);
            } catch (ClassCastException ex) {
                snapshot.total = null;
            }
        }
        snapshot.checksum = checksum(snapshot.rows);
        return snapshot;
    }

    private static int parseRows(SharedPreferences prefs, String prefKey, String kind, LegacySnapshot snapshot) {
        if (!prefs.contains(prefKey)) return 0;
        int count = 0;
        try {
            JSONObject data = new JSONObject(prefs.getString(prefKey, "{}"));
            Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject entry = data.optJSONObject(key);
                if (entry == null) continue;
                snapshot.rows.add(new LegacyRow(kind, key, entry.optInt("steps", 0), entry.optInt("offset", 0), entry.optInt("buffer", 0)));
                count++;
            }
        } catch (Exception ex) {
            snapshot.parseError = prefKey + ": " + ex.getMessage();
            Log.w(TAG, "Legacy " + prefKey + " unparseable: " + ex.getMessage());
        }
        return count;
    }

    static String checksum(List<LegacyRow> rows) {
        List<String> lines = new ArrayList<>(rows.size());
        for (LegacyRow row : rows) lines.add(row.line());
        return checksumLines(lines);
    }

    static String checksumLines(List<String> lines) {
        try {
            Collections.sort(lines);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes("UTF-8"));
                digest.update((byte) '\n');
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            return "error";
        }
    }

    static int parseInt(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
