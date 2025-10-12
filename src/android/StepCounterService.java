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
        StepCounterHelper.logToPrefs(this, "INFO", TAG, "onStartCommand called, flags=" + flags + " startId=" + startId);
        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "StepCounterService: onCreate() is called!");
        StepCounterHelper.logToPrefs(this, "INFO", TAG, "onCreate called, isRunning=" + isRunning);

        if (isRunning /* || has no step sensors */)
            return;

        Log.i(TAG, "StepCounterService: Relaunch service in 1 hour (4.4.2 start_sticky bug ) ...");
        StepCounterHelper.logToPrefs(this, "INFO", TAG, "Scheduling service relaunch in 1 hour");

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
            StepCounterHelper.logToPrefs(this, "INFO", TAG, "doInit: Registering step sensor");

            stepSensorManager = new StepSensorManager();
            stepSensorManager.start(this, this, SensorManager.SENSOR_DELAY_NORMAL);

            //Start foreground service with an sticky notification...
            startForegroundService();
            StepCounterHelper.logToPrefs(this, "INFO", TAG, "doInit: Foreground service started");

            //This is broadcast when the device is being shut down (completely turned off, not sleeping).
            //Once the broadcast is complete, the final shutdown will proceed and all unsaved data lost.
            //As of Build.VERSION_CODES.P this broadcast is only sent to receivers registered through
            //Context.registerReceiver (Google said!)
            if (stepCounterShutdownReceiver == null) {
                stepCounterShutdownReceiver = new StepCounterShutdownReceiver();

                registerReceiver(   stepCounterShutdownReceiver,
                                    new IntentFilter(Intent.ACTION_SHUTDOWN));
                StepCounterHelper.logToPrefs(this, "INFO", TAG, "doInit: Shutdown receiver registered");
            }
        }
        catch (Exception ex) {
            Log.w(TAG, "StepCounterService: Initialization failed. " + ex.getMessage());
            StepCounterHelper.logToPrefs(this, "ERROR", TAG, "doInit failed: " + ex.getMessage());
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
        StepCounterHelper.logToPrefs(this, "INFO", TAG, "stopService called");

        if(isRunning) {
            //Stop listening to events when stop() is called
            if(stepSensorManager != null)
                stepSensorManager.stop();

            //Unregister shutdown/reboot broadcast receiver!
            if(stepCounterShutdownReceiver != null) {
                try {
                    unregisterReceiver(stepCounterShutdownReceiver);
                    StepCounterHelper.logToPrefs(this, "INFO", TAG, "stopService: Shutdown receiver unregistered");
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "StepCounterService: Receiver was not registered or already unregistered: " + e.getMessage());
                    StepCounterHelper.logToPrefs(this, "WARN", TAG, "stopService: Receiver unregister failed: " + e.getMessage());
                }
                stepCounterShutdownReceiver = null;
            }
        }

        isRunning = false;

        Log.i(TAG, "StepCounterService: Relaunch service in 10000ms ..." );
        StepCounterHelper.logToPrefs(this, "INFO", TAG, "stopService: Scheduling relaunch in 10s");

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
        int appIconRes = getResources().getIdentifier("ic_notification", "drawable", getPackageName());
        int baseFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          baseFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        builder = new NotificationCompat.Builder(this, createChannel())
          .setSmallIcon(appIconRes)
          .setContentTitle(appName)   // shown if the system decides not to use your custom RemoteViews
          .setContentText("")
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
      int id = getResources().getIdentifier("mct_sc_notification_steps", "string", getPackageName());
      String stepsText = String.format(getString(id), 0);
      views.setTextViewText(getResources().getIdentifier( "tvSteps",
        "id",
        getPackageName()), stepsText);
      builder
        .setCustomContentView(views)
        .setCustomBigContentView(views)
        .setStyle(new NotificationCompat.DecoratedCustomViewStyle());

      Notification notification = builder.build();

      // Start foreground service...
      startForeground(NOTIFICATION_ID, notification);
    }

    @SuppressLint("DiscouragedApi")
    private void updateNotification(int steps){
        Log.d(TAG, "StepCounterService: Updating the notification ...");
        RemoteViews views = new RemoteViews(getPackageName(), getResources().getIdentifier( "sticky_notification",
                                                                                            "layout",
                                                                                            getPackageName()));
      int id = getResources().getIdentifier("mct_sc_notification_steps", "string", getPackageName());
      String stepsText = String.format(getString(id), steps);
      views.setTextViewText(getResources().getIdentifier( "tvSteps",
        "id",
        getPackageName()), stepsText);
      builder
        .setCustomContentView(views)
        .setCustomBigContentView(views)
        .setStyle(new NotificationCompat.DecoratedCustomViewStyle());

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
        int savedSteps = StepCounterHelper.saveSteps(steps, this);
        Log.i(TAG, "STEP_UPDATE: Sensor=" + steps + " Daily=" + savedSteps);
        StepCounterHelper.logToPrefs(this, "INFO", TAG, "onChanged: Sensor=" + steps + " Daily=" + savedSteps);
        updateNotification(savedSteps);
    }

    //endregion

}
