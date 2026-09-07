package com.mctechnologies.cordovapluginstepcounter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Single SQLite store for the pedometer.
 *
 * Replaces the JSON-in-SharedPreferences store ("UserData"), which was rewritten in full
 * from two processes and could be wiped by a single empty read (see StepStoreMigration).
 *
 * Both the UI process and the ":cordovapluginstepcounter" service process open the same
 * database file. SQLite file locking plus Android's built-in busy timeout serialise the
 * writers; WAL lets one process keep reading while the other commits.
 *
 * Schema (version 1):
 *   period(kind, key, steps, step_offset, buffer, updated_at)  -- kind = 'day' | 'hour'
 *   meta(key, value)
 *   log(id, at, level, tag, msg)
 */
public class StepStore extends SQLiteOpenHelper {

    private static final String TAG = "StepStore";

    static final String DB_NAME = "stepcounter.db";
    static final int DB_VERSION = 1;

    static final String KIND_DAY = "day";
    static final String KIND_HOUR = "hour";
    static final String DAY_PATTERN = "yyyy-MM-dd";
    static final String HOUR_PATTERN = "yyyy-MM-dd HH";

    /** Rows with a key below this are synthetic test rows (see StepStoreTestHooks). */
    static final String SENTINEL_KEY_LIMIT = "2002";

    static final String META_TOTAL_COUNT = "total_count";
    static final String META_HEARTBEAT_AT = "service_heartbeat_at";
    static final String META_LAST_SENSOR_AT = "last_sensor_at";
    static final String META_LAST_PRUNE_AT = "last_prune_at";

    private static final long HOUR_RETENTION_DAYS = 400;
    private static final long DAY_RETENTION_DAYS = 800;
    private static final int LOG_RETENTION_ROWS = 500;
    private static final long PRUNE_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static StepStore instance;

    private final Context appContext;

    static synchronized StepStore getInstance(@NonNull Context context) {
        if (instance == null) {
            StepStore store = new StepStore(context.getApplicationContext());
            store.setWriteAheadLoggingEnabled(true);
            instance = store;
            try {
                StepStoreMigration.runIfNeeded(store.appContext, store);
            } catch (Exception ex) {
                Log.e(TAG, "Legacy migration failed: " + ex.getMessage(), ex);
            }
        }
        return instance;
    }

    private StepStore(Context appContext) {
        super(appContext, DB_NAME, null, DB_VERSION);
        this.appContext = appContext;
    }

    //region Schema

    @Override
    public void onCreate(SQLiteDatabase db) {
        createSchema(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Guards the two-process first-open race: whichever process wins onCreate, the other
        // one still sees a complete schema.
        createSchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first SQLite schema. Future versions add ALTER TABLE steps here,
        // one `if (oldVersion < N)` block per version, never dropping data.
        createSchema(db);
    }

    private static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS period (" +
                "kind TEXT NOT NULL, " +
                "key TEXT NOT NULL, " +
                "steps INTEGER NOT NULL, " +
                "step_offset INTEGER NOT NULL, " +
                "buffer INTEGER NOT NULL DEFAULT 0, " +
                "updated_at INTEGER NOT NULL, " +
                "PRIMARY KEY (kind, key)) WITHOUT ROWID");
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "at INTEGER NOT NULL, " +
                "level TEXT, tag TEXT, msg TEXT)");
    }

    //endregion

    //region Period rows

    static final class PeriodRow {
        final int steps;
        final int offset;
        final int buffer;

        PeriodRow(int steps, int offset, int buffer) {
            this.steps = steps;
            this.offset = offset;
            this.buffer = buffer;
        }
    }

    static final class ApplyResult {
        final int oldSteps;
        final int newSteps;
        final boolean saved;
        final boolean newPeriod;

        ApplyResult(int oldSteps, int newSteps, boolean saved, boolean newPeriod) {
            this.oldSteps = oldSteps;
            this.newSteps = newSteps;
            this.saved = saved;
            this.newPeriod = newPeriod;
        }
    }

    @Nullable
    PeriodRow getPeriod(SQLiteDatabase db, String kind, String key) {
        try (Cursor c = db.rawQuery("SELECT steps, step_offset, buffer FROM period WHERE kind=? AND key=?",
                new String[]{kind, key})) {
            if (c.moveToFirst()) {
                return new PeriodRow(c.getInt(0), c.getInt(1), c.getInt(2));
            }
        }
        return null;
    }

    void putPeriod(SQLiteDatabase db, String kind, String key, int steps, int offset, int buffer, long now) {
        ContentValues values = new ContentValues();
        values.put("kind", kind);
        values.put("key", key);
        values.put("steps", steps);
        values.put("step_offset", offset);
        values.put("buffer", buffer);
        values.put("updated_at", now);
        db.insertWithOnConflict("period", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    static String formatKey(String pattern, Date date) {
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(date);
    }

    static Date previousPeriod(String kind, Date now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        if (KIND_HOUR.equals(kind)) {
            calendar.add(Calendar.HOUR_OF_DAY, -1);
        } else {
            calendar.add(Calendar.DATE, -1);
        }
        return calendar.getTime();
    }

    /**
     * Literal port of the legacy StepCounterHelper.cacheSteps() offset/buffer algorithm,
     * applied to one period kind inside the caller's transaction.
     */
    private ApplyResult applyPeriod(SQLiteDatabase db, String kind, String pattern, int steps, Date now) {
        String currentKey = formatKey(pattern, now);
        PeriodRow row = getPeriod(db, kind, currentKey);

        int oldSteps = 0;
        int offset;
        int buffer = 0;
        boolean newPeriod = false;

        if (row != null) {
            offset = row.offset;
            oldSteps = row.steps;
            buffer = row.buffer;

            int delta = (steps - offset + buffer) - oldSteps;
            if (delta < 0) {
                // The sensor went backwards without a buffer save (reboot we did not see).
                Log.w(TAG, "STEP_ANOMALY: negative delta. kind=" + kind + " sensor=" + steps + " offset=" + offset +
                        " buffer=" + buffer + " oldSteps=" + oldSteps + " delta=" + delta + " key=" + currentKey);
                buffer += (Math.abs(delta) + 1);
            }
        } else {
            String previousKey = formatKey(pattern, previousPeriod(kind, now));
            PeriodRow previous = getPeriod(db, kind, previousKey);
            newPeriod = true;
            if (previous != null) {
                offset = previous.offset + previous.steps;
                buffer = previous.buffer;
                Log.d(TAG, "NEW_PERIOD: kind=" + kind + " sensor=" + steps + " inheritedOffset=" + offset +
                        " inheritedBuffer=" + buffer + " prev=" + previousKey + " current=" + currentKey);
            } else {
                offset = steps - oldSteps;
                Log.d(TAG, "FIRST_RUN: kind=" + kind + " sensor=" + steps + " calculatedOffset=" + offset);
            }
        }

        int newSteps = steps - offset + buffer;
        if (newSteps < 0) {
            Log.e(TAG, "STEP_NEGATIVE: kind=" + kind + " sensor=" + steps + " offset=" + offset + " buffer=" + buffer +
                    " result=" + newSteps + " returning=" + oldSteps + " key=" + currentKey);
            return new ApplyResult(oldSteps, oldSteps, false, newPeriod);
        }

        putPeriod(db, kind, currentKey, newSteps, offset, buffer, now.getTime());
        return new ApplyResult(oldSteps, newSteps, true, newPeriod);
    }

    /**
     * Records one TYPE_STEP_COUNTER value: updates the day row, the hour row, the running
     * total, the heartbeat, and prunes old rows, all in ONE transaction.
     *
     * @return today's step count (the old value if nothing could be saved), like the legacy code.
     */
    synchronized int recordSensorValue(int sensorSteps, Date now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            ApplyResult day = applyPeriod(db, KIND_DAY, DAY_PATTERN, sensorSteps, now);
            ApplyResult hour = applyPeriod(db, KIND_HOUR, HOUR_PATTERN, sensorSteps, now);

            if (day.saved) {
                // Day delta only. The legacy code added the delta once per period kind, which
                // roughly doubled get_step_count.
                setMeta(db, META_TOTAL_COUNT, String.valueOf(getTotalCount(db) + (day.newSteps - day.oldSteps)));
            }
            setMeta(db, META_LAST_SENSOR_AT, String.valueOf(now.getTime()));
            setMeta(db, META_HEARTBEAT_AT, String.valueOf(now.getTime()));

            if (hour.newPeriod || pruneIsDue(db, now)) {
                prune(db, now);
            }

            db.setTransactionSuccessful();
            Log.d(TAG, "STEP_SAVED: sensor=" + sensorSteps + " daily=" + day.newSteps + " saved=" + day.saved);
            return day.saved ? day.newSteps : day.oldSteps;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Shutdown handler (legacy saveDailyBuffer): the sensor restarts from 0 after a reboot, so
     * the current day/hour keep their steps as buffer with a zero offset.
     */
    synchronized void freezeCurrentPeriods(Date now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            freezePeriod(db, KIND_DAY, formatKey(DAY_PATTERN, now), now);
            freezePeriod(db, KIND_HOUR, formatKey(HOUR_PATTERN, now), now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void freezePeriod(SQLiteDatabase db, String kind, String key, Date now) {
        PeriodRow row = getPeriod(db, kind, key);
        if (row != null && row.steps >= 0) {
            putPeriod(db, kind, key, row.steps, 0, row.steps, now.getTime());
            Log.i(TAG, "BUFFER_SAVED: kind=" + kind + " steps=" + row.steps + " key=" + key);
        }
    }

    /** @return today's steps or -1 when there is no row for today (plugin action contract). */
    synchronized int getTodaySteps(Date now) {
        PeriodRow row = getPeriod(getReadableDatabase(), KIND_DAY, formatKey(DAY_PATTERN, now));
        return row == null ? -1 : row.steps;
    }

    synchronized int getTotalCount() {
        return getTotalCount(getReadableDatabase());
    }

    private int getTotalCount(SQLiteDatabase db) {
        String value = getMeta(db, META_TOTAL_COUNT);
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Hour rows as the legacy JSON shape: {"yyyy-MM-dd HH": {"steps": N, "offset": N, "buffer": N}}.
     * The JS wrapper and the app parse exactly this.
     */
    synchronized JSONObject getHistoryJson() {
        JSONObject result = new JSONObject();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT key, steps, step_offset, buffer FROM period WHERE kind=? ORDER BY key", new String[]{KIND_HOUR})) {
            while (c.moveToNext()) {
                JSONObject entry = new JSONObject();
                entry.put("steps", c.getInt(1));
                entry.put("offset", c.getInt(2));
                entry.put("buffer", c.getInt(3));
                result.put(c.getString(0), entry);
            }
        } catch (Exception ex) {
            Log.e(TAG, "getHistoryJson failed: " + ex.getMessage(), ex);
        }
        return result;
    }

    //endregion

    //region Pruning

    private boolean pruneIsDue(SQLiteDatabase db, Date now) {
        String last = getMeta(db, META_LAST_PRUNE_AT);
        if (last == null) return true;
        try {
            return now.getTime() - Long.parseLong(last) > PRUNE_INTERVAL_MS;
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    /** Must be called inside a transaction. Sentinel test rows are left alone. */
    void prune(SQLiteDatabase db, Date now) {
        String hourCutoff = formatKey(HOUR_PATTERN, daysAgo(now, HOUR_RETENTION_DAYS));
        String dayCutoff = formatKey(DAY_PATTERN, daysAgo(now, DAY_RETENTION_DAYS));
        db.execSQL("DELETE FROM period WHERE kind=? AND key<? AND key>=?", new Object[]{KIND_HOUR, hourCutoff, SENTINEL_KEY_LIMIT});
        db.execSQL("DELETE FROM period WHERE kind=? AND key<? AND key>=?", new Object[]{KIND_DAY, dayCutoff, SENTINEL_KEY_LIMIT});
        db.execSQL("DELETE FROM log WHERE id <= (SELECT COALESCE(MAX(id), 0) FROM log) - " + LOG_RETENTION_ROWS);
        setMeta(db, META_LAST_PRUNE_AT, String.valueOf(now.getTime()));
    }

    private static Date daysAgo(Date now, long days) {
        return new Date(now.getTime() - days * 24L * 60 * 60 * 1000);
    }

    //endregion

    //region Meta

    @Nullable
    String getMeta(SQLiteDatabase db, String key) {
        try (Cursor c = db.rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) {
            if (c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    @Nullable
    synchronized String getMeta(String key) {
        return getMeta(getReadableDatabase(), key);
    }

    void setMeta(SQLiteDatabase db, String key, @Nullable String value) {
        if (value == null) {
            db.delete("meta", "key=?", new String[]{key});
            return;
        }
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value);
        db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized void setMeta(String key, @Nullable String value) {
        setMeta(getWritableDatabase(), key, value);
    }

    synchronized void heartbeat() {
        setMeta(getWritableDatabase(), META_HEARTBEAT_AT, String.valueOf(System.currentTimeMillis()));
    }

    //endregion

    //region Logs

    synchronized void log(String level, String tag, String message) {
        try {
            ContentValues values = new ContentValues();
            values.put("at", System.currentTimeMillis());
            values.put("level", level);
            values.put("tag", tag);
            values.put("msg", message);
            getWritableDatabase().insert("log", null, values);
        } catch (Exception ex) {
            Log.e(TAG, "log insert failed: " + ex.getMessage());
        }
    }

    /** Same JSON shape as the legacy DebugLogs prefs: [{timestamp, level, tag, message}]. */
    synchronized String getLogsJson() {
        JSONArray logs = new JSONArray();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        try (Cursor c = getReadableDatabase().rawQuery("SELECT at, level, tag, msg FROM log ORDER BY id", null)) {
            while (c.moveToNext()) {
                JSONObject entry = new JSONObject();
                entry.put("timestamp", formatter.format(new Date(c.getLong(0))));
                entry.put("level", c.getString(1));
                entry.put("tag", c.getString(2));
                entry.put("message", c.getString(3));
                logs.put(entry);
            }
        } catch (Exception ex) {
            Log.e(TAG, "getLogsJson failed: " + ex.getMessage(), ex);
        }
        return logs.toString();
    }

    synchronized void clearLogs() {
        getWritableDatabase().delete("log", null, null);
    }

    //endregion

    //region Diagnostics

    synchronized int countPeriods(String kind, boolean sentinelOnly) {
        SQLiteDatabase db = getReadableDatabase();
        String sql = sentinelOnly
                ? "SELECT COUNT(*) FROM period WHERE kind=? AND key<'" + SENTINEL_KEY_LIMIT + "'"
                : "SELECT COUNT(*) FROM period WHERE kind=?";
        try (Cursor c = db.rawQuery(sql, new String[]{kind})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized JSONObject getStorageInfo() {
        JSONObject info = new JSONObject();
        try {
            SQLiteDatabase db = getReadableDatabase();
            File dbFile = appContext.getDatabasePath(DB_NAME);
            File walFile = new File(dbFile.getPath() + "-wal");
            info.put("dbPath", dbFile.getPath());
            info.put("dbSizeBytes", dbFile.length());
            info.put("walSizeBytes", walFile.exists() ? walFile.length() : 0);
            info.put("schemaVersion", db.getVersion());
            info.put("journalMode", pragmaString(db, "journal_mode"));
            info.put("dayRows", countPeriods(KIND_DAY, false));
            info.put("hourRows", countPeriods(KIND_HOUR, false));
            info.put("sentinelRows", countPeriods(KIND_DAY, true) + countPeriods(KIND_HOUR, true));
            info.put("totalCount", getTotalCount(db));
            try (Cursor c = db.rawQuery("SELECT MIN(key), MAX(key) FROM period WHERE kind=? AND key>=?",
                    new String[]{KIND_HOUR, SENTINEL_KEY_LIMIT})) {
                if (c.moveToFirst()) {
                    info.put("oldestHourKey", c.isNull(0) ? JSONObject.NULL : c.getString(0));
                    info.put("newestHourKey", c.isNull(1) ? JSONObject.NULL : c.getString(1));
                }
            }
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM log", null)) {
                if (c.moveToFirst()) info.put("logRows", c.getInt(0));
            }
            JSONObject meta = new JSONObject();
            try (Cursor c = db.rawQuery("SELECT key, value FROM meta ORDER BY key", null)) {
                while (c.moveToNext()) meta.put(c.getString(0), c.getString(1));
            }
            info.put("meta", meta);
        } catch (Exception ex) {
            Log.e(TAG, "getStorageInfo failed: " + ex.getMessage(), ex);
        }
        return info;
    }

    String pragmaString(SQLiteDatabase db, String pragma) {
        try (Cursor c = db.rawQuery("PRAGMA " + pragma, null)) {
            if (c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    Context getAppContext() {
        return appContext;
    }

    //endregion
}
