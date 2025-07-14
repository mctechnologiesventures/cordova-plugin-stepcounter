package com.mctechnologies.cordovapluginstepcounter;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class StepCounterJobService extends JobService {

    private static final String TAG = "StepCounterJobService";
    private static final int BOOT_RESTART_JOB_ID = 1000;
    private static final long BOOT_DELAY_MS = 30000; // 30 seconds after boot

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "JobService started with job ID: " + params.getJobId());
        
        if (params.getJobId() == BOOT_RESTART_JOB_ID) {
            handleBootRestartJob(params);
            return false; // Job completed immediately
        }
        
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "JobService stopped with job ID: " + params.getJobId());
        return false; // Don't reschedule
    }

    private void handleBootRestartJob(JobParameters params) {
        SharedPreferences prefs = getSharedPreferences("StepCounterState", Context.MODE_PRIVATE);
        boolean wasRunning = prefs.getBoolean("service_was_running", false);
        
        if (!wasRunning) {
            Log.i(TAG, "Step counter service was not running before boot, skipping restart");
            jobFinished(params, false);
            return;
        }

        // Check if device supports step counting
        if (!deviceHasStepCounter(getPackageManager())) {
            Log.w(TAG, "Device does not support step counting, skipping restart");
            prefs.edit().putBoolean("service_was_running", false).apply();
            jobFinished(params, false);
            return;
        }

        try {
            Log.i(TAG, "Restarting step counter service after boot via JobScheduler");
            Intent stepCounterIntent = new Intent(this, StepCounterService.class);
            ContextCompat.startForegroundService(this, stepCounterIntent);
            
            // Mark as successfully restarted
            prefs.edit()
                .putBoolean("service_restarted_after_boot", true)
                .putLong("last_boot_restart", System.currentTimeMillis())
                .apply();
                
            Log.i(TAG, "Step counter service successfully restarted after boot");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart step counter service after boot: " + e.getMessage());
            
            // Schedule a notification to inform user
            StepCounterNotificationHelper.showServiceRestartNotification(this);
        }
        
        jobFinished(params, false);
    }

    private static boolean deviceHasStepCounter(PackageManager pm) {
        return Build.VERSION.SDK_INT >= 19 && pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER);
    }

    /**
     * Schedule a job to restart the step counter service after boot
     */
    public static void scheduleBootRestartJob(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "JobScheduler not available on this Android version");
            return;
        }

        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) {
            Log.e(TAG, "JobScheduler service not available");
            return;
        }

        ComponentName serviceComponent = new ComponentName(context, StepCounterJobService.class);
        
        JobInfo.Builder builder = new JobInfo.Builder(BOOT_RESTART_JOB_ID, serviceComponent)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true) // Survive reboots
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false);

        // Android 7.0+ specific settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setRequiresDeviceIdle(false);
        }

        // Android 9.0+ specific settings  
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setImportantWhileForeground(true);
        } else {
            // Only set delays for pre-Android 9.0 devices
            builder.setMinimumLatency(BOOT_DELAY_MS) // Wait 30 seconds after boot
                   .setOverrideDeadline(BOOT_DELAY_MS + 10000); // Must run within 40 seconds
        }

        JobInfo jobInfo = builder.build();
        int result = jobScheduler.schedule(jobInfo);
        
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.i(TAG, "Boot restart job scheduled successfully");
        } else {
            Log.e(TAG, "Failed to schedule boot restart job");
        }
    }

    /**
     * Cancel the boot restart job
     */
    public static void cancelBootRestartJob(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler != null) {
            jobScheduler.cancel(BOOT_RESTART_JOB_ID);
            Log.i(TAG, "Boot restart job cancelled");
        }
    }
}