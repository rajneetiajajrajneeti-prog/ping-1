package com.mdmagent.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MdmPlugin.class);
        registerPlugin(NativeSocketPlugin.class);
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, MdmForegroundService.class));
        requestMissingPermissions();
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missing.toArray(new String[0]),
                PERM_REQUEST_CODE
            );
        }
    }
}
