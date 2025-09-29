package com.mctechnologies.cordovapluginstepcounter;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Created by Digitalsunray Media GmbH. On 19.07.2018.
 */
class StepCounterHelper {

    //region Constants

    private static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd";
    private static final String DEFAULT_DATE_HISTORY_PATTERN = "yyyy-MM-dd HH";
    private static final String PREFERENCE_NAME = "UserData";
    private static final String PREF_KEY_PEDOMETER_DATA = "pedometerDayData";
    private static final String PREF_KEY_PEDOMETER_HISTORY_DATA = "pedometerHistoryData";
    private static final String PEDOMETER_DATA_STEPS = "steps";
    private static final String PEDOMETER_DATA_OFFSET = "offset";
    private static final String PEDOMETER_DATA_DAILY_BUFFER = "buffer";

    //endregion

    //region Static Methods

    static Calendar getPreviousDate(String key) {
        Calendar calendar = Calendar.getInstance();
        if (key == PREF_KEY_PEDOMETER_HISTORY_DATA) {
            calendar.add(Calendar.HOUR_OF_DAY, -1);
            return calendar;
        }
        calendar.add(Calendar.DATE, -1);
        return calendar;
    }

    static int cacheSteps(int steps, String format, String key, @NonNull Context context) {
      int oldSteps = 0;
      try {
          int newSteps;
          int offset;
          int buffer = 0;

          Date currentDate = new Date();
          SimpleDateFormat dateFormatter = new SimpleDateFormat(format , Locale.getDefault());

          String currentDateString = dateFormatter.format(currentDate);
          SharedPreferences sharedPref = CordovaStepCounter.getDefaultSharedPreferencesMultiProcess(context,
                                                                                                  PREFERENCE_NAME);

          JSONObject pData = new JSONObject();
          JSONObject newData = new JSONObject();
          if(sharedPref.contains(key)){
              String pDataString = sharedPref.getString(key, "{}");
              pData = new JSONObject(pDataString);
          }

          //Get the data previously stored for today
          if (pData.has(currentDateString)) {
              newData = pData.getJSONObject(currentDateString);
              offset = newData.getInt(PEDOMETER_DATA_OFFSET);
              oldSteps = newData.getInt(PEDOMETER_DATA_STEPS);

              if (newData.has(PEDOMETER_DATA_DAILY_BUFFER)) //Backward compatibility check!
                  buffer = newData.getInt(PEDOMETER_DATA_DAILY_BUFFER);

              //Data validation/correction and normalization...
              int delta = (steps - offset + buffer) - oldSteps;
              if(delta < 0) {
                  //We didn't save day's buffer properly!
                  buffer += (Math.abs(delta) + 1);
              }

          } else {
              Calendar calendar = getPreviousDate(key);
              String previousDateString = dateFormatter.format(calendar.getTime());

              if(pData.has(previousDateString)) {
                  //Try to fetch the offset from previous data, if any....
                  JSONObject previousData = pData.getJSONObject(previousDateString);
                  offset = previousData.getInt(PEDOMETER_DATA_OFFSET) +
                              previousData.getInt(PEDOMETER_DATA_STEPS);

                  if (previousData.has(PEDOMETER_DATA_DAILY_BUFFER))
                      buffer = previousData.getInt(PEDOMETER_DATA_DAILY_BUFFER);
              }
              else
                  //Change offset for current count...
                  offset = steps - oldSteps;
          }

          //Calculate the new steps ....
          newSteps = steps - offset + buffer;

          if(newSteps < 0) {
              Log.w("StepCounterHelper", "Calculated negative steps (" + newSteps + "), keeping old value: " + oldSteps);
              return oldSteps; // Return old value but don't save anything
          }

          //Calculate the total steps...
          int stepsCounted = getTotalCount(context);
          stepsCounted += (newSteps - oldSteps);
          setTotalCount(context, stepsCounted);

          //Save calculated values to SharedPreferences
          newData.put(PEDOMETER_DATA_STEPS, newSteps);
          newData.put(PEDOMETER_DATA_OFFSET, offset);
          newData.put(PEDOMETER_DATA_DAILY_BUFFER, buffer);
          pData.put(currentDateString, newData);

          // Only proceed if SharedPreferences save is successful
          SharedPreferences.Editor editor = sharedPref.edit();
          editor.putString(key, pData.toString());
          boolean saveSuccess = editor.commit(); // Use commit() for multi-process synchronization

          if (!saveSuccess) {
              Log.e("StepCounterHelper", "Failed to save step data to SharedPreferences");
              return oldSteps; // Return old persisted value if save failed
          }

          Log.d("StepCounterHelper", "Successfully saved steps: " + newSteps + " for date: " + currentDateString);
          return newSteps; // Only return new value if successfully saved

      } catch (Exception ex) {
          Log.e("StepCounterHelper", "Exception in cacheSteps: " + ex.getMessage(), ex);
          // Return the old persisted value if any error occurs
          return oldSteps;
      }
    }

    static int saveSteps(float sensorValue, @NonNull Context context) {
        try {
            int steps = Math.round(sensorValue);
            int daySteps = cacheSteps(steps, DEFAULT_DATE_PATTERN, PREF_KEY_PEDOMETER_DATA, context);
            cacheSteps(steps, DEFAULT_DATE_HISTORY_PATTERN, PREF_KEY_PEDOMETER_HISTORY_DATA, context);
            return daySteps;
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }

        return 0;
    }

    static int getTodaySteps(@NonNull Context context){
        Date currentDate = new Date();
        SimpleDateFormat dateFormatter = new SimpleDateFormat(DEFAULT_DATE_PATTERN, Locale.getDefault());

        String currentDateString = dateFormatter.format(currentDate);
        SharedPreferences sharedPref = CordovaStepCounter.getDefaultSharedPreferencesMultiProcess(context,
                                                                                                PREFERENCE_NAME);

        if(sharedPref.contains(PREF_KEY_PEDOMETER_DATA)){
            String pDataString = sharedPref.getString(PREF_KEY_PEDOMETER_DATA,"{}");
            try{
                JSONObject pData = new JSONObject(pDataString);
                //Get the information previously stored for today...
                if(pData.has(currentDateString))
                    return pData.getJSONObject(currentDateString).getInt(PEDOMETER_DATA_STEPS);
            }
            catch (Exception ex){
                ex.printStackTrace();
            }
        }

        return 0;
    }

    static int getTotalCount(@NonNull Context context){
        int totalCount = 0;
        SharedPreferences sharedPref = CordovaStepCounter.getDefaultSharedPreferencesMultiProcess(context,
                                                                                                PREFERENCE_NAME);

        if(sharedPref.contains("PEDOMETER_TOTAL_COUNT_PREF"))
            totalCount = sharedPref.getInt("PEDOMETER_TOTAL_COUNT_PREF", 0);

        return totalCount;
    }

    private static void setTotalCount(@NonNull Context context, Integer newValue){
        SharedPreferences sharedPref = CordovaStepCounter.getDefaultSharedPreferencesMultiProcess(context,
                                                                                                PREFERENCE_NAME);

        SharedPreferences.Editor sharedPrefEditor = sharedPref.edit();
        sharedPrefEditor.putInt("PEDOMETER_TOTAL_COUNT_PREF", newValue);
        sharedPrefEditor.commit(); // Use commit() for multi-process synchronization
    }

    static void saveDailyBuffer(@NonNull Context context) {
        try {
            //NOTE: this method MUST be used, in case of phone shutdown/reboot...
            Date currentDate = new Date();
            SimpleDateFormat dateFormatter = new SimpleDateFormat(DEFAULT_DATE_PATTERN, Locale.getDefault());

            String currentDateString = dateFormatter.format(currentDate);
            SharedPreferences sharedPref = CordovaStepCounter.getDefaultSharedPreferencesMultiProcess(context,
                                                                                                    PREFERENCE_NAME);

            SharedPreferences.Editor editor = sharedPref.edit();

            if(sharedPref.contains(PREF_KEY_PEDOMETER_DATA)){
                JSONObject data = new JSONObject(sharedPref.getString(PREF_KEY_PEDOMETER_DATA,"{}"));
                if (data.has(currentDateString)) {
                    JSONObject dayData = data.getJSONObject(currentDateString);
                    int steps = dayData.getInt(PEDOMETER_DATA_STEPS);

                    if(steps >= 0) {
                        //Save calculated values to the private preferences ...
                        dayData.put(PEDOMETER_DATA_STEPS, steps);
                        dayData.put(PEDOMETER_DATA_OFFSET, 0);
                        dayData.put(PEDOMETER_DATA_DAILY_BUFFER, steps);
                        data.put(currentDateString, dayData);

                        editor.putString(PREF_KEY_PEDOMETER_DATA, data.toString());
                        editor.commit(); // Use commit() for multi-process synchronization
                    }
                }
            }

            if(sharedPref.contains(PREF_KEY_PEDOMETER_HISTORY_DATA)){
                JSONObject data = new JSONObject(sharedPref.getString(PREF_KEY_PEDOMETER_HISTORY_DATA,"{}"));
                if (data.has(currentDateString)) {
                    JSONObject historyData = data.getJSONObject(currentDateString);
                    int steps = historyData.getInt(PEDOMETER_DATA_STEPS);

                    if(steps >= 0) {
                        //Save calculated values to the private preferences ...
                        historyData.put(PEDOMETER_DATA_STEPS, steps);
                        historyData.put(PEDOMETER_DATA_OFFSET, 0);
                        historyData.put(PEDOMETER_DATA_DAILY_BUFFER, steps);
                        data.put(currentDateString, historyData);

                        editor.putString(PREF_KEY_PEDOMETER_HISTORY_DATA, data.toString());
                        editor.commit(); // Use commit() for multi-process synchronization
                    }
                }
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    //endregion
}
