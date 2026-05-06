package com.mdmagent.app;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class MdmDeviceAdminReceiver extends DeviceAdminReceiver {

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Warning: Disabling this will remove system security protection and may cause instability.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        // If somehow disabled, try to re-request admin on next app open
    }
}
