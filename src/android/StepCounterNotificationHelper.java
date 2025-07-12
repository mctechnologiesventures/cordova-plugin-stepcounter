package com.mctechnologies.cordovapluginstepcounter;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import android.util.Log;

public class StepCounterNotificationHelper {

    private static final String TAG = "StepCounterNotificationHelper";
    private static final String RESTART_CHANNEL_ID = "step_counter_restart";
    private static final int RESTART_NOTIFICATION_ID = 778;

    /**
     * Show a notification to inform user that step counter service needs manual restart
     */
    @SuppressLint("DiscouragedApi")
    public static void showServiceRestartNotification(Context context) {
        NotificationManager notificationManager = 
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (notificationManager == null) {
            Log.e(TAG, "NotificationManager not available");
            return;
        }

        // Create notification channel for Android 8.0+
        createRestartNotificationChannel(context, notificationManager);

        PackageManager pm = context.getPackageManager();
        ApplicationInfo appInfo = context.getApplicationInfo();
        String appName = pm.getApplicationLabel(appInfo).toString();
        
        // Get app icon
        int appIconRes = context.getResources().getIdentifier("ic_notification", "drawable", context.getPackageName());
        if (appIconRes == 0) {
            appIconRes = android.R.drawable.ic_dialog_info; // fallback icon
        }

        // Create intent to launch the app
        Intent launchIntent = pm.getLaunchIntentForPackage(context.getPackageName());
        int baseFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            baseFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, 
            RESTART_NOTIFICATION_ID, 
            launchIntent, 
            baseFlags
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, RESTART_CHANNEL_ID)
                .setSmallIcon(appIconRes)
                .setContentTitle("Step Counter Service")
                .setContentText("Tap to restart step counting after device restart")
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText("Your step counter service was stopped after device restart. " +
                             "Due to Android 15 restrictions, please tap here to restart step counting."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        try {
            notificationManager.notify(RESTART_NOTIFICATION_ID, builder.build());
            Log.i(TAG, "Service restart notification shown");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show service restart notification: " + e.getMessage());
        }
    }

    /**
     * Dismiss the service restart notification
     */
    public static void dismissServiceRestartNotification(Context context) {
        NotificationManager notificationManager = 
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (notificationManager != null) {
            notificationManager.cancel(RESTART_NOTIFICATION_ID);
            Log.i(TAG, "Service restart notification dismissed");
        }
    }

    @NonNull
    private static void createRestartNotificationChannel(Context context, NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelName = "Step Counter Restart";
            String channelDescription = "Notifications for step counter service restart after device reboot";
            
            NotificationChannel channel = new NotificationChannel(
                RESTART_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            );
            
            channel.setDescription(channelDescription);
            channel.enableLights(true);
            channel.setVibrationPattern(new long[]{500, 500});
            channel.enableVibration(true);
            channel.setShowBadge(true);
            
            notificationManager.createNotificationChannel(channel);
        }
    }
}