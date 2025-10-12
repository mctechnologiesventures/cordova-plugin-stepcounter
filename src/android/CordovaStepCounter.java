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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CordovaStepCounter extends CordovaPlugin {

    private final String TAG = "CordovaStepCounter";

    //private final String ACTION_CONFIGURE        = "configure";
    private final String ACTION_START            = "start";
    private final String ACTION_STOP             = "stop";
    private final String ACTION_GET_STEPS        = "get_step_count";
    private final String ACTION_GET_TODAY_STEPS  = "get_today_step_count";
    private final String ACTION_CAN_COUNT_STEPS  = "can_count_steps";
    private final String ACTION_GET_HISTORY      = "get_history";
    private final String ACTION_GET_LOGS         = "get_logs";
    private final String ACTION_CLEAR_LOGS       = "clear_logs";


    @Override
    public boolean execute(String action, JSONArray data, CallbackContext callbackContext)  {
        LOG.i(TAG, "execute()");
        StepCounterHelper.logToPrefs(this.cordova.getActivity(), "INFO", TAG, "execute() action=" + action);

        Activity activity = this.cordova.getActivity();
        Intent stepCounterIntent = new Intent(activity, StepCounterService.class);

        // Check for pending service start on Android 15+ (auto-start after app launch)
        checkAndHandlePendingServiceStart(activity);

        if (ACTION_CAN_COUNT_STEPS.equals(action)) {
            Boolean can = deviceHasStepCounter(activity.getPackageManager());
            Log.i(TAG, "Checking if device has step counter APIS: "+ can);
            StepCounterHelper.logToPrefs(activity, "INFO", TAG, "Device has step counter: " + can);
            callbackContext.success( can ? 1 : 0 );
        }
        else if (ACTION_START.equals(action)) {
            if(!deviceHasStepCounter(activity.getPackageManager())){
                Log.i(TAG, "Step detector not supported");
                StepCounterHelper.logToPrefs(activity, "ERROR", TAG, "Step detector not supported");
                callbackContext.error("Step detector not supported");
                return true;
            }

            Log.i(TAG, "Starting StepCounterService ...");
            StepCounterHelper.logToPrefs(activity, "INFO", TAG, "Starting StepCounterService");

            // Mark service as running for boot receiver
            SharedPreferences prefs = activity.getSharedPreferences("StepCounterState", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("service_was_running", true).apply();

            // Schedule JobScheduler for Android 15+ boot restart capability
            StepCounterJobService.scheduleBootRestartJob(activity);

            // Dismiss any restart notifications since we're manually starting
            StepCounterNotificationHelper.dismissServiceRestartNotification(activity);

            ContextCompat.startForegroundService(activity, stepCounterIntent);
            callbackContext.success("started service");
        }
        else if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "Stopping StepCounterService");
            StepCounterHelper.logToPrefs(activity, "INFO", TAG, "Stopping StepCounterService");

            // Mark service as stopped for boot receiver
            SharedPreferences prefs = activity.getSharedPreferences("StepCounterState", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("service_was_running", false).apply();

            // Cancel JobScheduler since service is intentionally stopped
            StepCounterJobService.cancelBootRestartJob(activity);

            // Dismiss any restart notifications
            StepCounterNotificationHelper.dismissServiceRestartNotification(activity);

            //Stop the running step counter background service...
            activity.stopService(stepCounterIntent);
            callbackContext.success("stopped service");
        }
        else if (ACTION_GET_STEPS.equals(action)) {
            Integer steps = StepCounterHelper.getTotalCount(activity);
            Log.i(TAG, "QUERY_TOTAL: Returning total steps: " + steps);
            StepCounterHelper.logToPrefs(activity, "INFO", TAG, "QUERY_TOTAL: " + steps);
            callbackContext.success(steps);
        }
        else if (ACTION_GET_TODAY_STEPS.equals(action)) {
            SharedPreferences sharedPref = getDefaultSharedPreferencesMultiProcess(activity,"UserData");
            if(sharedPref.contains("pedometerDayData")){
                String pDataString = sharedPref.getString("pedometerDayData", "{}");

                Date currentDate = new Date();
                SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                String currentDateString = dateFormatter.format(currentDate);

                JSONObject pData = new JSONObject();
                JSONObject dayData;
                Integer daySteps = -1;
                try{
                    pData = new JSONObject(pDataString);
                    Log.d(TAG," got json shared prefs "+pData.toString());
                }catch (JSONException err){
                    Log.e(TAG," DATA_PARSE_ERROR: Exception while parsing json string : "+pDataString);
                    StepCounterHelper.logToPrefs(activity, "ERROR", TAG, "DATA_PARSE_ERROR: " + err.getMessage());
                }

                if(pData.has(currentDateString)){
                    try {
                        dayData = pData.getJSONObject(currentDateString);
                        daySteps = dayData.getInt("steps");
                    }catch(JSONException err){
                        Log.e(TAG,"DATA_PARSE_ERROR: Exception while getting Object from JSON for "+currentDateString);
                        StepCounterHelper.logToPrefs(activity, "ERROR", TAG, "DATA_PARSE_ERROR for date " + currentDateString + ": " + err.getMessage());
                    }
                }

                Log.i(TAG, "QUERY_TODAY: Returning steps for today: " + daySteps + " date=" + currentDateString);
                StepCounterHelper.logToPrefs(activity, "INFO", TAG, "QUERY_TODAY: " + daySteps + " date=" + currentDateString);
                callbackContext.success(daySteps);
            }else{
                Log.w(TAG, "QUERY_TODAY: No steps history found in stepCounterService!");
                StepCounterHelper.logToPrefs(activity, "WARN", TAG, "QUERY_TODAY: No steps history found");
                callbackContext.success(-1);
            }
        } else if(ACTION_GET_HISTORY.equals(action)){
            SharedPreferences sharedPref = getDefaultSharedPreferencesMultiProcess(activity,"UserData");
            if(sharedPref.contains("pedometerHistoryData")){
                String pDataString = sharedPref.getString("pedometerHistoryData","{}");
                Log.i(TAG, "Getting steps history from stepCounterService: " + pDataString);
                StepCounterHelper.logToPrefs(activity, "INFO", TAG, "GET_HISTORY: Retrieved history data");
                callbackContext.success(pDataString);
            }else{
                Log.i(TAG, "No steps history found in stepCounterService !");
                StepCounterHelper.logToPrefs(activity, "INFO", TAG, "GET_HISTORY: No history found");
                callbackContext.success("{}");
            }
        }
        else if (ACTION_GET_LOGS.equals(action)) {
            try {
                String logs = StepCounterHelper.getLogs(activity);
                Log.i(TAG, "Getting persistent logs, size=" + logs.length());
                callbackContext.success(logs);
            } catch (Exception e) {
                Log.e(TAG, "Error getting logs: " + e.getMessage());
                StepCounterHelper.logToPrefs(activity, "ERROR", TAG, "Error getting logs: " + e.getMessage());
                callbackContext.error("Error getting logs: " + e.getMessage());
            }
        }
        else if (ACTION_CLEAR_LOGS.equals(action)) {
            try {
                StepCounterHelper.clearLogs(activity);
                Log.i(TAG, "Persistent logs cleared");
                callbackContext.success("Logs cleared successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error clearing logs: " + e.getMessage());
                StepCounterHelper.logToPrefs(activity, "ERROR", TAG, "Error clearing logs: " + e.getMessage());
                callbackContext.error("Error clearing logs: " + e.getMessage());
            }
        }
        else {
            Log.e(TAG, "Invalid action called on class " + TAG + ", " + action);
            StepCounterHelper.logToPrefs(activity, "ERROR", TAG, "Invalid action: " + action);
            callbackContext.error("Invalid action called on class " + TAG + ", " + action);
        }

        return true;
    }

    private static boolean deviceHasStepCounter(PackageManager pm) {
        // Check that the device supports the step counter and detector sensors
        return Build.VERSION.SDK_INT >= 19 && pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER);
    }

    static SharedPreferences getDefaultSharedPreferencesMultiProcess(   @NonNull Context context,
                                                                        @NonNull String key) {
        //NOTE: We need to set MODE_MULTI_PROCESS when accessing the SharedPreferences both in the
        // StepCounter sensor and in the UI process. Because we have specified the service to run
        // in its own process in the AndroidManifest.xml.
        return context.getSharedPreferences(key, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
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