package com.mctechnologies.cordovapluginstepcounter;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;

import java.util.Date;

/**
 * Thin facade over StepStore, kept so the service, receivers and plugin entry point keep
 * their call sites. All persistence lives in StepStore (SQLite); nothing here touches
 * SharedPreferences any more.
 *
 * Created by Digitalsunray Media GmbH. On 19.07.2018. Rewritten for SQLite storage in 0.2.0.
 */
class StepCounterHelper {

    private static final String TAG = "StepCounterHelper";

    /** Records one sensor value and returns today's steps (see StepStore.recordSensorValue). */
    static int saveSteps(float sensorValue, @NonNull Context context) {
        try {
            return StepStore.getInstance(context).recordSensorValue(Math.round(sensorValue), new Date());
        } catch (Exception ex) {
            Log.e(TAG, "saveSteps failed: " + ex.getMessage(), ex);
            return 0;
        }
    }

    /** Today's steps, 0 when there is no row yet (internal use). */
    static int getTodaySteps(@NonNull Context context) {
        int steps = StepStore.getInstance(context).getTodaySteps(new Date());
        return Math.max(steps, 0);
    }

    static int getTotalCount(@NonNull Context context) {
        return StepStore.getInstance(context).getTotalCount();
    }

    /**
     * Shutdown handler: freezes today's day/hour rows so the post-reboot sensor reset
     * (counter back to 0) does not lose the steps already counted.
     */
    static void saveDailyBuffer(@NonNull Context context) {
        try {
            StepStore.getInstance(context).freezeCurrentPeriods(new Date());
        } catch (Exception ex) {
            Log.e(TAG, "saveDailyBuffer failed: " + ex.getMessage(), ex);
        }
    }

    /** Persistent debug log (SQLite `log` table, capped at 500 rows) plus logcat. */
    static void logToPrefs(@NonNull Context context, String level, String tag, String message) {
        try {
            StepStore.getInstance(context).log(level, tag, message);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to write persistent log: " + ex.getMessage());
        }
        Log.d(tag, "[" + level + "] " + message);
    }

    static String getLogs(@NonNull Context context) {
        try {
            return StepStore.getInstance(context).getLogsJson();
        } catch (Exception ex) {
            Log.e(TAG, "Failed to read persistent logs: " + ex.getMessage(), ex);
            return "[]";
        }
    }

    static void clearLogs(@NonNull Context context) {
        try {
            StepStore.getInstance(context).clearLogs();
            Log.d(TAG, "Persistent logs cleared");
        } catch (Exception ex) {
            Log.e(TAG, "Failed to clear persistent logs: " + ex.getMessage(), ex);
        }
    }
}
