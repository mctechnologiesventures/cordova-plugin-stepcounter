package com.mctechnologies.cordovapluginstepcounter;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class StepCounterShutdownReceiver extends BroadcastReceiver {

    private static final String TAG = "StepCounterShutdownRx";

    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    @Override
    public void onReceive(Context context, Intent intent) {
        //This is broadcast when the device is being shut down (completely turned off, not sleeping).
        Log.i(TAG, "Device shutdown detected");
        StepCounterHelper.logToPrefs(context, "INFO", TAG, "Device shutdown detected, saving buffer");

        //Stop sensor manager to prevent race condition ...
        context.stopService(new Intent(context, StepCounterService.class));

        //Let's save today's step buffer...
        StepCounterHelper.saveDailyBuffer(context);
    }
}