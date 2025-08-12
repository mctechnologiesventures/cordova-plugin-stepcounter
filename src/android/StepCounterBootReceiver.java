package com.mctechnologies.cordovapluginstepcounter;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.os.Build;
import android.content.SharedPreferences;

public class StepCounterBootReceiver extends BroadcastReceiver {

    private static final String TAG = "StepCounterBootReceiver";

    //This method is called after device booting is complete!
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    public void onReceive(final Context context, Intent intent) {
        try {
            String action = intent.getAction();
            Log.i(TAG, "Boot receiver triggered with action: " + action);
            
            // Check if the service was previously running by checking SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences("StepCounterState", Context.MODE_PRIVATE);
            boolean wasRunning = prefs.getBoolean("service_was_running", false);
            
            if (!wasRunning) {
                Log.i(TAG, "Step counter service was not running before boot, skipping auto-start");
                return;
            }

            // Android 15+ restrictions: BOOT_COMPLETED receivers cannot start health foreground services
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                Log.i(TAG, "Android 15+ detected: Using JobScheduler for service restart after boot");
                
                // Use JobScheduler to restart service after a delay
                StepCounterJobService.scheduleBootRestartJob(context);
                
                // Mark boot time for reference
                prefs.edit()
                    .putLong("last_boot_time", System.currentTimeMillis())
                    .putBoolean("boot_restart_attempted", true)
                    .apply();
                    
            } else {
                // Android 14 and below: Start service directly
                Log.i(TAG, "Starting step counter service directly after boot (Android 14 and below)");
                Intent stepCounterIntent = new Intent(context, StepCounterService.class);
                ContextCompat.startForegroundService(context, stepCounterIntent);
                
                // Mark successful restart
                prefs.edit()
                    .putBoolean("service_restarted_after_boot", true)
                    .putLong("last_boot_restart", System.currentTimeMillis())
                    .apply();
            }

        } catch (Exception e){
            Log.e(TAG, "StepCounterBootReceiver error: " + e.getMessage());
            
            // Only show notification if service was actually running before
            SharedPreferences prefs = context.getSharedPreferences("StepCounterState", Context.MODE_PRIVATE);
            boolean wasRunning = prefs.getBoolean("service_was_running", false);
            
            if (wasRunning) {
                // Fallback: show notification for manual restart
                StepCounterNotificationHelper.showServiceRestartNotification(context);
            } else {
                Log.i(TAG, "Service wasn't running before, skipping restart notification");
            }
        }
    }
}
