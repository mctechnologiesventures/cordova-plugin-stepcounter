package com.mctechnologies.cordovapluginstepcounter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import androidx.annotation.NonNull;
import android.util.Log;

import static android.content.Context.SENSOR_SERVICE;

/**
 * Created by Digitalsunray Media GmbH. On 20.07.2018.
 */
public class StepSensorManager implements SensorEventListener {

    //region Variables

    private final  String TAG = "StepSensorManager";
    private StepChangeListener listener;
    private SensorManager manager;
    private boolean isStarted;
    private float lastSensorValue = -1;
    private Context appContext;

    //endregion

    //region Methods

    @SuppressLint("InlinedApi")
    public void start(@NonNull StepChangeListener listener, @NonNull Context context, int samplingPeriodUs) {
        try {
            this.listener = listener;
            this.appContext = context.getApplicationContext();

            if(isStarted) {
                Log.w(TAG, "StepSensorManager is already started!");
                StepCounterHelper.logToPrefs(appContext, "WARN", TAG, "start: Already started");
                return;
            }

            //Let's start the step detector sensor...
            manager = (SensorManager) context.getSystemService(SENSOR_SERVICE);
            if(manager != null) {
                Sensor mStepSensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
                manager.registerListener(this, mStepSensor, samplingPeriodUs);

                isStarted = true;
                StepCounterHelper.logToPrefs(appContext, "INFO", TAG, "start: Sensor registered successfully");
            }
            else {
                Log.i(TAG, "Could not register TYPE_STEP_COUNTER sensor!");
                StepCounterHelper.logToPrefs(appContext, "ERROR", TAG, "start: Failed to get SensorManager");
            }
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
            if (appContext != null) {
                StepCounterHelper.logToPrefs(appContext, "ERROR", TAG, "start exception: " + throwable.getMessage());
            }
        }
    }

    void stop() {
        try {
             /*
            NOTE:
             If you want to continuously track the number of steps over a long
             period of time, do NOT unregister for this sensor, so that it keeps counting steps in the
             background even when the AP is in suspend mode and report the aggregate count when the AP
             is awake. Application needs to stay registered for this sensor because step counter does not
             count steps if it is not activated.
             */

            isStarted = false;
            listener = null;

            //Let's stop the step detector sensor :(
            if(manager != null) {
                manager.unregisterListener(this);
                Log.i(TAG, "STEP_DETECTOR sensor is unregistered!");
                if (appContext != null) {
                    StepCounterHelper.logToPrefs(appContext, "INFO", TAG, "stop: Sensor unregistered");
                }
            }
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
            if (appContext != null) {
                StepCounterHelper.logToPrefs(appContext, "ERROR", TAG, "stop exception: " + throwable.getMessage());
            }
        }
    }

    //endregion

    //region Event Handlers

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(listener != null) {
            float sensorValue = event.values[0];

            // Detect potential device reboot: sensor value decreased significantly
            if (lastSensorValue > 0 && sensorValue < lastSensorValue) {
                Log.w(TAG, "SENSOR_RESET: Sensor value decreased. last=" + lastSensorValue +
                      " current=" + sensorValue + " delta=" + (sensorValue - lastSensorValue) +
                      " POSSIBLE_REBOOT");
                if (appContext != null) {
                    StepCounterHelper.logToPrefs(appContext, "WARN", TAG,
                        "SENSOR_RESET: last=" + lastSensorValue + " current=" + sensorValue + " POSSIBLE_REBOOT");
                }
            }

            lastSensorValue = sensorValue;
            Log.d(TAG, "SENSOR_EVENT: value=" + sensorValue + " timestamp=" + event.timestamp);
            listener.onChanged(sensorValue);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.i(TAG, "onAccuracyChanged: " + accuracy);
        if (appContext != null) {
            StepCounterHelper.logToPrefs(appContext, "INFO", TAG, "onAccuracyChanged: " + accuracy);
        }
    }

    //endregion
}