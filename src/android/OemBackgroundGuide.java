package com.mctechnologies.cordovapluginstepcounter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Per-OEM "don't kill this app" helpers: detects the vendor, checks which of its autostart /
 * battery screens exist on this build, and opens them. Falls back to the app's own settings page.
 *
 * targetSdk 30+ package visibility: resolveActivity() only sees packages listed in the
 * <queries> block of the manifest (declared in plugin.xml).
 */
class OemBackgroundGuide {

    private static final String TAG = "OemBackgroundGuide";

    static final String WHICH_AUTOSTART = "autostart";
    static final String WHICH_BATTERY = "battery";

    private static final String[][] XIAOMI_AUTOSTART = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
    };
    private static final String[][] XIAOMI_BATTERY = {
            {"com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"},
            {"com.miui.securitycenter", "com.miui.powercenter.PowerSettings"},
    };
    private static final String[][] HUAWEI_AUTOSTART = {
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
    };
    private static final String[][] HUAWEI_BATTERY = {
            {"com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"},
    };
    private static final String[][] SAMSUNG_BATTERY = {
            {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"},
    };
    private static final String[][] OPPO_AUTOSTART = {
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
    };
    private static final String[][] VIVO_AUTOSTART = {
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
    };
    private static final String[][] ONEPLUS_AUTOSTART = {
            {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
    };
    private static final String[][] ASUS_AUTOSTART = {
            {"com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"},
            {"com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"},
    };
    private static final String[][] ASUS_BATTERY = {
            {"com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"},
    };

    static String detectOem() {
        String manufacturer = String.valueOf(Build.MANUFACTURER).toLowerCase(Locale.US);
        String brand = String.valueOf(Build.BRAND).toLowerCase(Locale.US);
        String all = manufacturer + " " + brand;
        if (all.contains("xiaomi") || all.contains("redmi") || all.contains("poco")) return "xiaomi";
        if (all.contains("huawei") || all.contains("honor")) return "huawei";
        if (all.contains("samsung")) return "samsung";
        if (all.contains("oppo") || all.contains("realme")) return "oppo";
        if (all.contains("vivo") || all.contains("iqoo")) return "vivo";
        if (all.contains("oneplus")) return "oneplus";
        if (all.contains("asus")) return "asus";
        return "other";
    }

    private static String[][] table(String oem, String which) {
        boolean autostart = WHICH_AUTOSTART.equals(which);
        switch (oem) {
            case "xiaomi":
                return autostart ? XIAOMI_AUTOSTART : XIAOMI_BATTERY;
            case "huawei":
                return autostart ? HUAWEI_AUTOSTART : HUAWEI_BATTERY;
            case "samsung":
                return autostart ? new String[0][] : SAMSUNG_BATTERY;
            case "oppo":
                return autostart ? OPPO_AUTOSTART : new String[0][];
            case "vivo":
                return autostart ? VIVO_AUTOSTART : new String[0][];
            case "oneplus":
                return autostart ? ONEPLUS_AUTOSTART : new String[0][];
            case "asus":
                return autostart ? ASUS_AUTOSTART : ASUS_BATTERY;
            default:
                return new String[0][];
        }
    }

    @Nullable
    private static Intent resolvable(Context context, String[][] candidates) {
        PackageManager pm = context.getPackageManager();
        for (String[] candidate : candidates) {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(candidate[0], candidate[1]));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    return intent;
                }
            } catch (Exception ex) {
                Log.d(TAG, "resolveActivity failed for " + candidate[1] + ": " + ex.getMessage());
            }
        }
        return null;
    }

    static JSONObject describe(@NonNull Context context) {
        JSONObject result = new JSONObject();
        String oem = detectOem();
        try {
            result.put("manufacturer", Build.MANUFACTURER);
            result.put("brand", Build.BRAND);
            result.put("model", Build.MODEL);
            result.put("sdkInt", Build.VERSION.SDK_INT);
            result.put("oem", oem);
            result.put("autostartResolvable", resolvable(context, table(oem, WHICH_AUTOSTART)) != null);
            result.put("batteryResolvable", resolvable(context, table(oem, WHICH_BATTERY)) != null);
        } catch (Exception ex) {
            Log.e(TAG, "describe failed: " + ex.getMessage());
        }
        return result;
    }

    /** @return which screen was opened: "autostart", "battery", or "app_details" for the fallback. */
    static String open(@NonNull Context context, @NonNull String which) {
        String oem = detectOem();
        Intent intent = resolvable(context, table(oem, which));
        String opened = which;
        if (intent == null) {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            opened = "app_details";
        }
        try {
            context.startActivity(intent);
        } catch (Exception ex) {
            Log.w(TAG, "startActivity failed for " + opened + ": " + ex.getMessage());
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName()));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
            opened = "app_details";
        }
        return opened;
    }
}
