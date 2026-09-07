# Cordova Step Counter Plugin

Uses the step counter service APIs introduced in Android 4.4 KitKat to, you guessed it, count the number of steps whomever is holding the device running your app takes.

## Anatomy of notification layout:

![notification layout](doc/anatomy.jpg)

## Using
Create a new Cordova Project

    $ cordova create hello com.example.helloapp Hello
    
Install the plugin

    $ cd hello
    $ cordova plugin add https://github.com/DigitalsunrayMedia/cordova-plugin-stepcounter.git
    

Edit `www/js/index.html` and add the following code inside `onDeviceReady`

```js
    var success = function(message) {
        alert(message);
    }

    var failure = function() {
        alert("Error calling CordovaStepCounter Plugin");
    }

    // Start the step counter
    // startingOffset will be added to the total steps counted in this session.
    // ie. say you have already recorded 150 steps for a certain activity, then
    // the step counter records 50. The getStepCount method will then return 200.
    var startingOffset = 0;
    stepcounter.start(startingOffset, success, failure);

    // Stop the step counter
    stepcounter.stop(success, failure);

    // Get the amount of steps for today (or -1 if it no data given)
    stepcounter.getTodayStepCount(success, failure);
    
    // Get the amount of steps since the service is started!
    stepcounter.getStepCount(success, failure);

    // Returns true/false if Android device is running >API level 19 && has the step counter API available
    stepcounter.deviceCanCountSteps(success, failure);

    // Get the step history (JavaScript object), one entry per hour
    // sample result :
    //{
    //  "2015-01-01 10":{"offset": 123, "steps": 456, "buffer": 0},
    //  "2015-01-01 11":{"offset": 579, "steps": 789, "buffer": 0}
    //  ...
    //}
    stepcounter.getHistory(
        function(historyData){
            success(historyData);
        },
        failure
    );

    // Persistent plugin log ([{timestamp, level, tag, message}]) and reset
    stepcounter.getLogs(success, failure);
    stepcounter.clearLogs(success, failure);

    // Is the foreground service alive? {running, listedByActivityManager, heartbeatAgeMs, lastSensorAt, intendedRunning}
    stepcounter.isServiceRunning(success, failure);

    // Battery optimization exemption (aggressive OEMs kill non-exempt foreground services)
    stepcounter.isIgnoringBatteryOptimizations(success, failure);   // boolean
    stepcounter.requestIgnoreBatteryOptimizations(success, failure); // opens the system dialog, resolves with the new state

    // Vendor "don't kill this app" screens
    stepcounter.getOemGuide(success, failure);          // {manufacturer, model, oem, autostartResolvable, batteryResolvable}
    stepcounter.openOemSettings('autostart', success, failure); // or 'battery'; falls back to the app details page

    // Storage diagnostics and test mode (see TESTING.md)
    stepcounter.getStorageInfo(success, failure);
    stepcounter.storageTest('verify', {}, success, failure);

```

Install Android platform

    cordova platform add android
    
Run the code

    cordova run

## Data storage (0.2.0+)

Steps live in SQLite, `databases/stepcounter.db`, opened by both the UI process and the
`:cordovapluginstepcounter` service process (WAL journal, one transaction per sensor event):

```sql
period(kind TEXT, key TEXT, steps INTEGER, step_offset INTEGER, buffer INTEGER, updated_at INTEGER, PRIMARY KEY(kind, key))
  -- kind = 'day' (key 'yyyy-MM-dd') | 'hour' (key 'yyyy-MM-dd HH')
meta(key TEXT PRIMARY KEY, value TEXT)   -- total_count, service_heartbeat_at, last_sensor_at, legacy_* migration markers
log(id, at, level, tag, msg)             -- persistent debug log, capped at 500 rows
```

Hour rows older than 400 days and day rows older than 800 days are pruned once a day.

Before 0.2.0 the same data was two JSON strings in the `UserData` SharedPreferences file,
rewritten in full from two processes. A single empty read in the UI process could replace the
whole file with one flag, which is how devices lost their entire history. The first open of
0.2.0 copies the legacy file into SQLite (insert-or-ignore, never overwriting DB rows), keeps the
file read-only for 30 days as a fallback, then deletes it. A legacy file that exists but reads
back empty is retried on later opens instead of being treated as "nothing to migrate".

## Changes in 0.2.1
- Notification string and colour ship as the plugin's own `res/values/mct_stepcounter.xml`.
  cordova-android 15 has no `res/values/strings.xml` / `colors.xml`, so the old config-file
  injection was skipped and the service died before `startForeground()`.
- The foreground notification always starts, falling back to a plain text notification when the
  custom layout or any of its resources is missing.
- `isServiceRunning` trusts ActivityManager; the heartbeat only decides when that query fails.

## Changes in 0.2.0
- Storage moved from SharedPreferences JSON to SQLite with a one-time migration (see above).
- `get_step_count` now adds the day delta only (it was roughly doubled before).
- Persistent logs moved into the database; `getLogs()` / `clearLogs()` keep their shape.
- New: `isServiceRunning`, `isIgnoringBatteryOptimizations`, `requestIgnoreBatteryOptimizations`,
  `getOemGuide`, `openOemSettings`, `getStorageInfo`, `storageTest`.
- New permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and a `<queries>` block for the vendor
  settings packages.
- Sensor events that repeat the last cumulative value are ignored.

## Changes in 0.0.11
- Added: FOREGROUND_SERVICE permission for API level 28+     
    
## Changes in 0.0.10
 - Replaced: Legacy background service with a foreground service (In order to tackle the Android 8.0 + background execution limits)

## Changes in 0.0.4

 - Added : Re-integrated support for getStepCount which return the step counted since app is started
 - Added : Method getTodayStepsCount for an agregated steps count for all a day (uses offset and history to calculate)
 - Fixed : Issue with phone rebooting in a middle of a day (causes negative steps for the day, due to step < offset) 

## Changes in 0.0.3

 - getHistory() and getStepCount() return parsed JSON objects.

## Changes in 0.0.2

 - The StepCounterService is now automatically relaunched when killed (and after one hour for some 4.4.2 START_STICKY Service problem).
 - The StepCounterService should be automatically launched on device boot (using StepCounterBootReceiver)

Historic note: up to 0.1.0 all the step counter data were saved in the "UserData" SharedPrefs as JSON ("day": {"offset": XXX,"steps": YYY}); see "Data storage" above for the current store.
A js function (for cordova) called getHistory() gives access to the step count history



## Compatibility

This will only work on Android devices running 4.4 (KitKat) or higher, and that have a step counter sensor. This includes Google's Nexus line of handsets, and potentially some others.

Use stepcounter.deviceCanCountSteps() to see if a device meets these requirements before trying to use it any further.

## Here be dragons

The quality, usefulness and functionality of this code is in no way guaranteed.
This is far from production ready stuff you're looking at, and definitely has a few hairy parts that need combing over.
If you'd like to help, I'd love to hear from you!

## More Info

For more information on setting up Cordova see [the documentation](http://cordova.apache.org/docs/en/4.0.0/guide_cli_index.md.html#The%20Command-Line%20Interface)
For more info on plugins see the [Plugin Development Guide](http://cordova.apache.org/docs/en/4.0.0/guide_hybrid_plugins_index.md.html#Plugin%20Development%20Guide)
