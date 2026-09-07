# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Cordova plugin that provides step counting functionality for Android devices using the native step counter sensor APIs introduced in Android 4.4 KitKat. The plugin runs a foreground service to continuously track steps and maintain step history.

## Development Commands

### Testing and Installation
```bash
# Create a test Cordova project
cordova create hello com.example.helloapp Hello
cd hello

# Install the plugin (local development)
cordova plugin add /path/to/cordova-plugin-stepcounter

# Add Android platform
cordova platform add android

# Build and run
cordova run android
```

### Plugin Installation from Git
```bash
cordova plugin add https://github.com/DigitalsunrayMedia/cordova-plugin-stepcounter.git
```

## Architecture Overview

### Core Components

**JavaScript Interface (`www/stepcounter.js`)**
- Exports stepcounter module with methods: start(), stop(), getTodayStepCount(), getStepCount(), deviceCanCountSteps(), getHistory(), getLogs(), clearLogs(), isServiceRunning(), isIgnoringBatteryOptimizations(), requestIgnoreBatteryOptimizations(), getOemGuide(), openOemSettings(), getStorageInfo(), storageTest()
- All methods use cordova.exec() to communicate with native Android code
- Handles JSON parsing for history data

**Main Plugin Class (`src/android/CordovaStepCounter.java`)**
- Entry point for all JavaScript calls via execute() method
- Manages StepCounterService lifecycle (start/stop foreground service)
- Handles data retrieval from StepStore (SQLite) for step counts and history
- Validates device compatibility (Android 4.4+ with step counter sensor)

**Background Service (`src/android/StepCounterService.java`)**
- Foreground service that runs continuously to track steps
- Implements StepChangeListener interface for step updates
- Shows persistent notification with current step count
- Persists step data through StepCounterHelper -> StepStore (SQLite)
- Auto-restarts after system kills to maintain step tracking

**Step Sensor Management (`src/android/StepSensorManager.java`)**
- Handles sensor registration/unregistration
- Manages step detection using Android's TYPE_STEP_COUNTER sensor
- Calculates daily step offsets and totals

**Boot Receiver (`src/android/StepCounterBootReceiver.java`)**
- Restarts step counting service after device reboot
- Ensures continuous step tracking across device restarts

**Helper Classes**
- `StepCounterHelper.java`: Thin facade over `StepStore` for the service, receivers and plugin entry point
- `StepChangeListener.java`: Interface for step count change notifications
- `StepCounterShutdownReceiver.java`: Handles service shutdown events

### Data Storage

SQLite (`src/android/StepStore.java`, database `stepcounter.db`, WAL), shared by the UI process and
the service process:
- `period(kind, key, steps, step_offset, buffer, updated_at)` with `kind` = `day` (`yyyy-MM-dd`) or
  `hour` (`yyyy-MM-dd HH`); one transaction per sensor event updates both rows and the total
- `meta(key, value)`: `total_count`, `service_heartbeat_at`, `last_sensor_at`, `legacy_*` migration markers
- `log(id, at, level, tag, msg)`: persistent debug log (capped at 500 rows)
- `getHistory()` returns the hour rows as `{"yyyy-MM-dd HH": {"steps", "offset", "buffer"}}`

`src/android/StepStoreMigration.java` copies the pre-0.2.0 SharedPreferences store (`UserData`,
keys `pedometerDayData` / `pedometerHistoryData`) into SQLite on first open: insert-or-ignore so DB
rows always win, a legacy file that reads back empty is retried instead of treated as empty, and
the file is deleted 30 days after a successful migration. Never write to `UserData` again: a
commit from the UI process rewrites the whole file from that process's in-memory map, and an empty
read followed by a commit is what wiped devices.

`src/android/StepStoreTestHooks.java` implements the `storage_test` action (see TESTING.md);
`src/android/OemBackgroundGuide.java` resolves and opens vendor autostart/battery screens.

### Permissions and Features

Required Android permissions:
- `RECEIVE_BOOT_COMPLETED`: Auto-start after reboot
- `FOREGROUND_SERVICE`: Run background service
- `FOREGROUND_SERVICE_HEALTH`: Health-related foreground service
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: Battery optimization exemption prompt
- `android.hardware.sensor.stepcounter`: Step counter sensor feature

### Notification System

The service displays a persistent notification showing current step count with:
- Custom layout (`src/android/res/layout/sticky_notification.xml`)
- Localized strings in multiple languages (values-ro/)
- Dark theme support (values-night/)

## Android 15 (API 35) Compatibility Issues

### Critical Limitations

**BOOT_COMPLETED Receiver Restrictions**
- Android 15 prevents BOOT_COMPLETED receivers from launching `health` foreground services
- Current `StepCounterBootReceiver.java:18` will fail on Android 15 when targeting API 35
- Auto-restart after device reboot will not work without modifications

**Known Issues**
- `unregisterReceiver` crash risk in `StepCounterService.java:157` if receiver wasn't properly registered
- Service may be subject to 6-hour time limits for dataSync operations
- Stricter background execution limits for rarely used apps

### Required Changes for Android 15 Support

1. **Update Boot Receiver**: Implement version checks and alternative startup methods
2. **Service Type Declaration**: Add explicit `android:foregroundServiceType` in manifest
3. **Receiver Safety**: Add try-catch protection around receiver unregistration
4. **Alternative Strategies**: Consider JobScheduler or user-initiated service start

### Useful Links

- [Android 15 Behavior Changes](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Foreground Service Types Changes](https://developer.android.com/about/versions/15/changes/foreground-service-types)
- [Boot Completed Restrictions Discussion](https://stackoverflow.com/questions/78664990/android-15-boot-completed-receiver-is-not-able-to-launch-a-foreground-service)
- [Google Play API 35 Requirements](https://learn.buildfire.com/en/articles/11713286-how-to-comply-with-google-play-s-android-15-api-35-requirement)
- [Foreground Service Types Documentation](https://developer.android.com/develop/background-work/services/fg-service-types)

## Development Notes

- Plugin only supports Android (requires Android 4.4+ with step counter sensor)
- Service runs in separate process (`:cordovapluginstepcounter`) for stability
- Uses foreground service to comply with Android 8.0+ background execution limits
- Step counting continues even when app is closed or device is rebooted
- Historical data persists across app restarts and device reboots
- **WARNING**: Auto-start functionality may fail on Android 15+ without updates