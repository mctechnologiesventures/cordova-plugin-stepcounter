package com.mctechnologies.cordovapluginstepcounter;

/*
    Copyright 2015 MCTechnologiesVentures <system@wellnessentially.com>

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

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.Locale;

public class StepCounterService extends Service implements StepChangeListener {

    //region Constants

    private static final int NOTIFICATION_ID = 777;
    //endregion

    //region Variables

    private final String TAG = "StepCounterService";
    private static boolean isRunning = false;
    private StepSensorManager stepSensorManager;
    private NotificationCompat.Builder builder;
    private StepCounterShutdownReceiver stepCounterShutdownReceiver;

    //endregion

    //region Service Method/Events

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "StepCounterService: onStartCommand is called!");
        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "StepCounterService: onCreate() is called!");

        if (isRunning /* || has no step sensors */)
            return;

        Log.i(TAG, "StepCounterService: Relaunch service in 1 hour (4.4.2 start_sticky bug ) ...");

        Intent newServiceIntent = new Intent(this,StepCounterService.class);
        AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        if(manager != null) {
            int baseFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              baseFlags |= PendingIntent.FLAG_IMMUTABLE;
            }

          PendingIntent stepIntent = PendingIntent.getService(getApplicationContext(),
                                                                10,
                                                                newServiceIntent,
            baseFlags);
            manager.set(AlarmManager.RTC, java.lang.System.currentTimeMillis() + 1000 * 60 * 60, stepIntent);
        }

        //Initialising sensors...
        doInit();

        isRunning = true;
    }

    public void doInit() {
        try {
            if (isRunning)
                return;

            Log.i(TAG, "StepCounterService: Registering STEP_DETECTOR sensor...");

            stepSensorManager = new StepSensorManager();
            stepSensorManager.start(this, this, SensorManager.SENSOR_DELAY_NORMAL);

            //Start foreground service with an sticky notification...
            startForegroundService();

            //This is broadcast when the device is being shut down (completely turned off, not sleeping).
            //Once the broadcast is complete, the final shutdown will proceed and all unsaved data lost.
            //As of Build.VERSION_CODES.P this broadcast is only sent to receivers registered through
            //Context.registerReceiver (Google said!)
            if (stepCounterShutdownReceiver == null) {
                stepCounterShutdownReceiver = new StepCounterShutdownReceiver();

                registerReceiver(   stepCounterShutdownReceiver,
                                    new IntentFilter(Intent.ACTION_SHUTDOWN));
            }
        }
        catch (Exception ex) {
            Log.w(TAG, "StepCounterService: Initialization failed. " + ex.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "StepCounterService: onUnbind() is called!");
        return super.onUnbind(intent);
    }

    @Override
    public boolean stopService(Intent intent) {
        Log.i(TAG, "StepCounterService: Received stop service: " + intent);

        if(isRunning) {
            //Stop listening to events when stop() is called
            if(stepSensorManager != null)
                stepSensorManager.stop();

            //Unregister shutdown/reboot broadcast receiver!
            if(stepCounterShutdownReceiver != null) {
                unregisterReceiver(stepCounterShutdownReceiver);
                stepCounterShutdownReceiver = null;
            }
        }

        isRunning = false;

        Log.i(TAG, "StepCounterService: Relaunch service in 10000ms ..." );

        //Auto-Relaunch the service....
        Intent newServiceIntent = new Intent(this,StepCounterService.class);
        AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {//Restart the service after around Ten seconds!
          int baseFlags = 0;
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            baseFlags |= PendingIntent.FLAG_IMMUTABLE;
          }
          manager.set(AlarmManager.RTC, System.currentTimeMillis() + 10000,
            PendingIntent.getService(this, 11, newServiceIntent, baseFlags));
        }

        return super.stopService(intent);
    }

    @Override
    public void onDestroy(){
        Log.i(TAG, "StepCounterService: onDestroy() is called!");
        super.onDestroy();
    }

    //endregion

    //region Methods

    /* Used to build and start foreground service. */
    @SuppressLint("DiscouragedApi")
    private void startForegroundService()
    {
        Log.d(TAG, "StepCounterService: Starting the foreground service...");
        PackageManager pm = getPackageManager();
        ApplicationInfo appInfo = getApplicationInfo();
        String appName = pm.getApplicationLabel(appInfo).toString();
        int appIconRes = appInfo.icon;
        int baseFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          baseFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        builder = new NotificationCompat.Builder(this, createChannel())
          .setSmallIcon(appIconRes)
          .setContentTitle(appName)   // shown if the system decides not to use your custom RemoteViews
          .setOngoing(true)
          .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
          .setPriority(NotificationCompat.PRIORITY_MAX)
          .setContentIntent(
            PendingIntent.getActivity(
              this, 1110,
              getPackageManager().getLaunchIntentForPackage(getPackageName()),
              baseFlags
            )
          );

        //custom notification UI...
        RemoteViews views = new RemoteViews(getPackageName(), getResources().getIdentifier( "sticky_notification",
                                                                                            "layout",
                                                                                            getPackageName()));
        views.setTextViewText(getResources().getIdentifier( "tvTitle",
          "id",
          getPackageName()), appName);

      int id = getResources().getIdentifier("mct_sc_notification_steps", "string", getPackageName());
      String stepsText = String.format(getString(id), 0);
      views.setTextViewText(getResources().getIdentifier( "tvSteps",
        "id",
        getPackageName()), stepsText);
      builder.setCustomContentView(views);

      Notification notification = builder.build();

      // Start foreground service...
      startForeground(NOTIFICATION_ID, notification);
    }

    @SuppressLint("DiscouragedApi")
    private void updateNotification(int steps){
        Log.d(TAG, "StepCounterService: Updating the notification ...");
        PackageManager pm = getPackageManager();
        ApplicationInfo appInfo = getApplicationInfo();
        String appName = pm.getApplicationLabel(appInfo).toString();
        RemoteViews views = new RemoteViews(getPackageName(), getResources().getIdentifier( "sticky_notification",
                                                                                            "layout",
                                                                                            getPackageName()));
        views.setTextViewText(getResources().getIdentifier( "tvTitle",
          "id",
          getPackageName()), appName);

      int id = getResources().getIdentifier("mct_sc_notification_steps", "string", getPackageName());
      String stepsText = String.format(getString(id), steps);
      views.setTextViewText(getResources().getIdentifier( "tvSteps",
        "id",
        getPackageName()), stepsText);
        builder.setCustomContentView(views);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager != null)
            manager.notify(NOTIFICATION_ID, builder.build());
    }

    @NonNull
    private String createChannel() {
      String channelId   = getPackageName() + ".steps";
      String channelName = "Step Counter Updates";

      NotificationManager mgr =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

      if (mgr != null) {
        NotificationChannel channel = new NotificationChannel(
          channelId,
          channelName,
          NotificationManager.IMPORTANCE_HIGH    // ← HIGH for heads-up
        );
        channel.enableLights(false);
        channel.setVibrationPattern(new long[]{0});
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setShowBadge(false);
        mgr.createNotificationChannel(channel);
      }

      return channelId;
    }


  //endregion

    //region Sensor Event Handlers

    @Override
    public void onChanged(float steps) {
        //Step history changed, let's save it...
        updateNotification(StepCounterHelper.saveSteps(steps, this));
    }

    //endregion

}
