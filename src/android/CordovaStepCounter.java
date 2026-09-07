package com.mctechnologies.cordovapluginstepcounter;

/*
    Copyright 2023 MCTechnologiesVentures <system@wellnessentially.com>
    Permission is hereby granted, free of charge, to any person obtaining
    a copy of this software and associated documentation files (the
    "Software"), to deal in the Software without restriction, including
    without limitation the rights to use, copy, modify, merge, publish,
    distribute, sublicense, and/or sell copies of the Software, and to
    permit persons to whom the Software is furnished to do so, subject to
    the following conditions:
    The above copyright notice and this permission notice shall be
    included in all copies or substantial portions of the Software.
    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
    EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
    MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
    NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
    LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
    OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
    WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import android.util.Log;

import java.util.Date;

public class CordovaStepCounter extends CordovaPlugin {

    private final String TAG = "CordovaStepCounter";

    private static final String ACTION_START            = "start";
    private static final String ACTION_STOP             = "stop";
    private static final String ACTION_GET_STEPS        = "get_step_count";
    private static final String ACTION_GET_TODAY_STEPS  = "get_today_step_count";
    private static final String ACTION_CAN_COUNT_STEPS  = "can_count_steps";
    private static final String ACTION_GET_HISTORY      = "get_history";
    private static final String ACTION_GET_LOGS         = "get_logs";
    private static final String ACTION_CLEAR_LOGS       = "clear_logs";
    private static final String ACTION_IS_SERVICE_RUNNING = "is_service_running";
    private static final String ACTION_GET_STORAGE_INFO = "get_storage_info";
    private static final String ACTION_STORAGE_TEST     = "storage_test";
    private static final String ACTION_IS_IGNORING_BATTERY = "is_ignoring_battery_optimizations";
    private static final String ACTION_REQUEST_IGNORE_BATTERY = "request_ignore_battery_optimizations";
    private static final String ACTION_GET_OEM_GUIDE    = "get_oem_guide";
    private static final String ACTION_OPEN_OEM_SETTINGS = "open_oem_settings";

    private static final int REQUEST_IGNORE_BATTERY = 7701;
    /** Heartbeat age under which the service is considered alive even if not listed by ActivityManager. */
    private static final long HEARTBEAT_ALIVE_MS = 15L * 60 * 1000;
    /**
     * Storage tests are refused on release builds unless the caller passes this token. The app only
     * sends it from its admin-only diagnostics panel; it is a belt-and-braces guard, not a secret.
     */
    private static final String STORAGE_TEST_ADMIN_TOKEN = "stepathon-admin-diagnostics";

    private CallbackContext batteryCallback;

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();
        try {
            // Opens the database once for the UI process and runs the legacy migration if needed.
            StepStore.getInstance(cordova.getContext());
        } catch (Exception ex) {
            Log.e(TAG, "StepStore init failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean execute(String action, JSONArray data, CallbackContext callbackContext)  {
        LOG.i(TAG, "execute() " + action);

        Context ctx = this.cordova.getContext();
        Activity activity = this.cordova.getActivity();

        StepCounterHelper.logToPrefs(ctx, "INFO", TAG, "execute() action=" + action);

        if (activity != null) {
            checkAndHandlePendingServiceStart(activity);
        }

        if (ACTION_CAN_COUNT_STEPS.equals(action)) {
            Boolean can = deviceHasStepCounter(ctx.getPackageManager());
            Log.i(TAG, "Checking if device has step counter APIS: "+ can);
            StepCounterHelper.logToPrefs(ctx, "INFO", TAG, "Device has step counter: " + can);
            callbackContext.success( can ? 1 : 0 );
        }
        else if (ACTION_START.equals(action)) {
            if (activity == null) {
                callbackContext.error("Cannot start service without Activity");
                return true;
            }
            if(!deviceHasStepCounter(activity.getPackageManager())){
                Log.i(TAG, "Step detector not supported");
                StepCounterHelper.logToPrefs(ctx, "ERROR", TAG, "Step detector not supported");
                callbackContext.error("Step detector not supported");
                return true;
            }

            Log.i(TAG, "Starting StepCounterService ...");
            StepCounterHelper.logToPrefs(ctx, "INFO", TAG, "Starting StepCounterService");

            // Mark service as running for boot receiver
            SharedPreferences prefs = ctx.getSharedPreferences("StepCounterState", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("service_was_running", true).apply();

            // Schedule JobScheduler for Android 15+ boot restart capability
            StepCounterJobService.scheduleBootRestartJob(activity);

            // Dismiss any restart notifications since we're manually starting
            StepCounterNotificationHelper.dismissServiceRestartNotification(activity);

            Intent stepCounterIntent = new Intent(activity, StepCounterService.class);
            ContextCompat.startForegroundService(activity, stepCounterIntent);
            callbackContext.success("started service");
        }
        else if (ACTION_STOP.equals(action)) {
            if (activity == null) {
                callbackContext.error("Cannot stop service without Activity");
                return true;
            }
            Log.i(TAG, "Stopping StepCounterService");
            StepCounterHelper.logToPrefs(ctx, "INFO", TAG, "Stopping StepCounterService");

            // Mark service as stopped for boot receiver
            SharedPreferences prefs = ctx.getSharedPreferences("StepCounterState", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("service_was_running", false).apply();

            // Cancel JobScheduler since service is intentionally stopped
            StepCounterJobService.cancelBootRestartJob(activity);

            // Dismiss any restart notifications
            StepCounterNotificationHelper.dismissServiceRestartNotification(activity);

            //Stop the running step counter background service...
            Intent stepCounterIntent = new Intent(activity, StepCounterService.class);
            activity.stopService(stepCounterIntent);
            callbackContext.success("stopped service");
        }
        else if (ACTION_GET_STEPS.equals(action)) {
            cordova.getThreadPool().execute(() -> {
                int steps = StepCounterHelper.getTotalCount(ctx);
                Log.i(TAG, "QUERY_TOTAL: Returning total steps: " + steps);
                StepCounterHelper.logToPrefs(ctx, "INFO", TAG, "QUERY_TOTAL: " + steps);
                callbackContext.success(steps);
            });
        }
        else if (ACTION_GET_TODAY_STEPS.equals(action)) {
            cordova.getThreadPool().execute(() -> {
                int daySteps = StepStore.getInstance(ctx).getTodaySteps(new Date());
                if (daySteps < 0) {
                    Log.w(TAG, "QUERY_TODAY: No steps recorded for today yet");
                    StepCounterHelper.logToPrefs(ctx, "WARN", TAG, "QUERY_TODAY: No steps recorded for today yet");
                } else {
                    Log.i(TAG, "QUERY_TODAY: Returning steps for today: " + daySteps);
                    StepCounterHelper.logToPrefs(ctx, "INFO", TAG, "QUERY_TODAY: " + daySteps);
                }
                callbackContext.success(daySteps);
            });
        }
        else if(ACTION_GET_HISTORY.equals(action)){
            cordova.getThreadPool().execute(() -> {
                JSONObject history = StepStore.getInstance(ctx).getHistoryJson();
                Log.i(TAG, "GET_HISTORY: " + history.length() + " hour entries");
                StepCounterHelper.logToPrefs(ctx, "INFO", TAG, "GET_HISTORY: " + history.length() + " entries");
                callbackContext.success(history.toString());
            });
        }
        else if (ACTION_GET_LOGS.equals(action)) {
            cordova.getThreadPool().execute(() -> {
                try {
                    String logs = StepCounterHelper.getLogs(ctx);
                    Log.i(TAG, "Getting persistent logs, size=" + logs.length());
                    callbackContext.success(logs);
                } catch (Exception e) {
                    Log.e(TAG, "Error getting logs: " + e.getMessage());
                    callbackContext.error("Error getting logs: " + e.getMessage());
                }
            });
        }
        else if (ACTION_CLEAR_LOGS.equals(action)) {
            cordova.getThreadPool().execute(() -> {
                try {
                    StepCounterHelper.clearLogs(ctx);
                    callbackContext.success("Logs cleared successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Error clearing logs: " + e.getMessage());
                    callbackContext.error("Error clearing logs: " + e.getMessage());
                }
            });
        }
        else if (ACTION_IS_SERVICE_RUNNING.equals(action)) {
            cordova.getThreadPool().execute(() -> callbackContext.success(serviceStatus(ctx)));
        }
        else if (ACTION_GET_STORAGE_INFO.equals(action)) {
            cordova.getThreadPool().execute(() -> {
                try {
                    JSONObject info = StepStore.getInstance(ctx).getStorageInfo();
                    info.put("legacyFileExists", StepStoreMigration.legacyFile(ctx, StepStoreMigration.LEGACY_PREFS).exists());
                    info.put("legacyFileSize", StepStoreMigration.legacyFile(ctx, StepStoreMigration.LEGACY_PREFS).length());
                    info.put("service", serviceStatus(ctx));
                    info.put("ignoringBatteryOptimizations", isIgnoringBatteryOptimizations(ctx));
                    callbackContext.success(info);
                } catch (Exception e) {
                    callbackContext.error("Error reading storage info: " + e.getMessage());
                }
            });
        }
        else if (ACTION_STORAGE_TEST.equals(action)) {
            String op = data.optString(0, "");
            JSONObject params = data.optJSONObject(1);
            final JSONObject safeParams = params == null ? new JSONObject() : params;
            if (!storageTestAllowed(ctx, safeParams)) {
                callbackContext.error("storage_test is only available on debug builds or with the admin token");
                return true;
            }
            cordova.getThreadPool().execute(() -> callbackContext.success(StepStoreTestHooks.run(ctx, op, safeParams)));
        }
        else if (ACTION_IS_IGNORING_BATTERY.equals(action)) {
            callbackContext.success(isIgnoringBatteryOptimizations(ctx) ? 1 : 0);
        }
        else if (ACTION_REQUEST_IGNORE_BATTERY.equals(action)) {
            requestIgnoreBatteryOptimizations(ctx, callbackContext);
        }
        else if (ACTION_GET_OEM_GUIDE.equals(action)) {
            callbackContext.success(OemBackgroundGuide.describe(ctx));
        }
        else if (ACTION_OPEN_OEM_SETTINGS.equals(action)) {
            String which = data.optString(0, OemBackgroundGuide.WHICH_AUTOSTART);
            try {
                callbackContext.success(OemBackgroundGuide.open(ctx, which));
            } catch (Exception e) {
                callbackContext.error("Could not open OEM settings: " + e.getMessage());
            }
        }
        else {
            Log.e(TAG, "Invalid action called on class " + TAG + ", " + action);
            StepCounterHelper.logToPrefs(ctx, "ERROR", TAG, "Invalid action: " + action);
            callbackContext.error("Invalid action called on class " + TAG + ", " + action);
        }

        return true;
    }

    //region Service status

    private JSONObject serviceStatus(Context ctx) {
        JSONObject status = new JSONObject();
        try {
            Boolean listed = isServiceListed(ctx);
            StepStore store = StepStore.getInstance(ctx);
            long now = System.currentTimeMillis();
            Long heartbeatAt = parseLong(store.getMeta(StepStore.META_HEARTBEAT_AT));
            Long lastSensorAt = parseLong(store.getMeta(StepStore.META_LAST_SENSOR_AT));
            long heartbeatAge = heartbeatAt == null ? -1 : now - heartbeatAt;
            SharedPreferences prefs = ctx.getSharedPreferences("StepCounterState", Context.MODE_PRIVATE);

            // ActivityManager is the authority: a killed service still has a recent heartbeat.
            // The heartbeat only decides when the ActivityManager query itself failed.
            boolean running = listed != null ? listed : (heartbeatAge >= 0 && heartbeatAge < HEARTBEAT_ALIVE_MS);
            status.put("running", running);
            status.put("listedByActivityManager", listed == null ? JSONObject.NULL : listed);
            status.put("heartbeatAt", heartbeatAt == null ? JSONObject.NULL : heartbeatAt);
            status.put("heartbeatAgeMs", heartbeatAge);
            status.put("lastSensorAt", lastSensorAt == null ? JSONObject.NULL : lastSensorAt);
            status.put("intendedRunning", prefs.getBoolean("service_was_running", false));
        } catch (Exception e) {
            Log.e(TAG, "serviceStatus failed: " + e.getMessage());
        }
        return status;
    }

    /** @return true/false from ActivityManager, or null when the query is unavailable. */
    @SuppressWarnings("deprecation")
    private Boolean isServiceListed(Context ctx) {
        ActivityManager manager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return null;
        try {
            // Deprecated since API 26 but still returns the caller's own services.
            for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (StepCounterService.class.getName().equals(info.service.getClassName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "getRunningServices failed: " + e.getMessage());
            return null;
        }
    }

    private static Long parseLong(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    //endregion

    //region Battery optimizations

    private boolean isIgnoringBatteryOptimizations(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    private void requestIgnoreBatteryOptimizations(Context ctx, CallbackContext callbackContext) {
        if (isIgnoringBatteryOptimizations(ctx)) {
            callbackContext.success(1);
            return;
        }
        Activity activity = cordova.getActivity();
        if (activity == null) {
            callbackContext.error("No activity");
            return;
        }
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.getPackageName()));
        if (ctx.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        }
        batteryCallback = callbackContext;
        try {
            cordova.startActivityForResult(this, intent, REQUEST_IGNORE_BATTERY);
        } catch (Exception e) {
            batteryCallback = null;
            callbackContext.error("Could not open battery optimization settings: " + e.getMessage());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == REQUEST_IGNORE_BATTERY && batteryCallback != null) {
            CallbackContext callback = batteryCallback;
            batteryCallback = null;
            boolean ignoring = isIgnoringBatteryOptimizations(cordova.getContext());
            StepCounterHelper.logToPrefs(cordova.getContext(), "INFO", TAG, "Battery optimization request result: ignoring=" + ignoring);
            callback.success(ignoring ? 1 : 0);
        }
    }

    //endregion

    private boolean storageTestAllowed(Context ctx, JSONObject params) {
        boolean debuggable = (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return debuggable || STORAGE_TEST_ADMIN_TOKEN.equals(params.optString("adminToken", ""));
    }

    private static boolean deviceHasStepCounter(PackageManager pm) {
        // Check that the device supports the step counter and detector sensors
        return Build.VERSION.SDK_INT >= 19 && pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER);
    }

    private void checkAndHandlePendingServiceStart(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences("StepCounterState", Context.MODE_PRIVATE);

        // Check if there was a boot restart attempt that may need user notification
        // Only show notification if service was actually running before boot
        if (prefs.getBoolean("boot_restart_attempted", false) &&
            !prefs.getBoolean("service_restarted_after_boot", false) &&
            prefs.getBoolean("service_was_running", false)) {

            long bootTime = prefs.getLong("last_boot_time", 0);
            long currentTime = System.currentTimeMillis();

            // If more than 2 minutes have passed since boot attempt, service likely failed to restart
            if (currentTime - bootTime > 120000) {
                Log.w(TAG, "Service may have failed to restart after boot - showing notification");
                StepCounterHelper.logToPrefs(activity, "WARN", TAG, "Service failed to restart after boot - showing notification");
                StepCounterNotificationHelper.showServiceRestartNotification(activity);

                // Clear the flag to avoid repeated notifications
                prefs.edit().putBoolean("boot_restart_attempted", false).apply();
            }
        }

        // Clear any restart success flags when app is opened
        if (prefs.getBoolean("service_restarted_after_boot", false)) {
            prefs.edit().putBoolean("service_restarted_after_boot", false).apply();
        }
    }
}
